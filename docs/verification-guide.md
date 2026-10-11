# Roadmap Verification Guide

[Roadmap](roadmap.md) | [Building & Testing](building.md) | [User Guide](user-guide.md) | [Security Review](security-review.md)

This guide covers every item under the roadmap's **Unverified** heading. It is a test plan, not a record of successful testing. Use your own devices, consenting participants, synthetic messages, and a private test Ring. Do not test replay, impersonation, or request floods against other people's devices.

## Recording Results

Use **Pass**, **Fail**, **Blocked**, or **Not run** for each subcase. Unsupported hardware is an expected negative case, not a successful positive test. A compiled UI test, a scripted range, or a screenshot alone does not prove radio delivery, cryptographic rejection, or battery behavior.

Record a run with this template:

```text
Case ID and subcase:
Date / tester:
Commit / APK SHA-256 / versionCode / signing fingerprint:
Devices: exact models, Android builds, Play Services versions:
Hardware flags: UWB, extended BLE advertising, Wi-Fi Aware, RTT:
Settings: transport, permissions, location, radios, animation scales:
Topology: host, guests, actual links, selected tracking targets:
Environment: indoor/outdoor, distance, obstacles, phone placement:
Steps and timestamps:
Expected / observed:
Result: Pass / Fail / Blocked / Not run
Evidence: screenshots, video, sanitized logs, measurements:
Issue / retest reference:
Restoration of changed device settings:
```

Keep private coordinates, invite QR contents, signing secrets, STS keys, and unrelated device logs out of issues and committed evidence. Use local recordings for raw diagnostic data and redact before sharing. Maintain a device matrix; one working phone pair does not establish compatibility for all models.

## Equipment And Order

- **One phone** supports settings, accessibility, background/foreground, and unsupported-hardware checks. A base Pixel 10 has no UWB; use it for the negative UWB case, not as the positive ranging device.
- **Two phones** support normal join, chat, beacons, BLE, recovery, and leave checks. RTT needs two phones with compatible, available Wi-Fi Aware and RTT hardware. A positive UWB test needs two UWB-capable phones.
- **Three phones** are the minimum for guest-to-guest UWB and a real relay hop: host H and guests A/B. H may lack UWB, but A/B must support it.
- **Eight phones** are required to claim the full physical 8-member mesh/UWB load case. Refusing a ninth participant needs another requester or an authorized test harness.
- **Special tooling** is needed to prove captured-advertisement replay rejection, delayed nonce replies, malformed signed payload rejection, controlled topology, or exact packet metadata. No ready-made radio attack/fault-injection harness is supplied by this guide. Mark those subcases Blocked until one is available.
- **A trusted development LAN and PC** support debug-relay teardown. The LAN transport and scripted radar are not substitutes for real Nearby/BLE/UWB/RTT tests.

Start with V06/V08 (join and BLE capabilities), then V01/V03/V04 (ranging), V10 (mesh), V13-V15 (loss and recovery), V11/V12 (additional sources), V16 (accessibility), V07 (power), V09/V17 (security), and V18 (release readiness). V05 is an independent debug-transport exercise.

## Baseline Setup

1. Record the commit and APK hash. Install the same build on all test devices. Use debug builds for instrumentation, then repeat release-critical scenarios with the signed release candidate.
2. Turn **LAN Debug Transport off** for every production radio test. Enable Bluetooth, Wi-Fi, location services, and relevant permissions. Start a fresh Ring for each independent case.
3. There is no Track Host or Share GPS control any more; note that a guest can range another guest and that GPS publishes for the whole Ring. Record UWB settings. Confirm the source label: a mesh-only member is not a local distance measurement.
4. Record Android animation scales, Battery Saver, app accessibility preferences, notification preferences, and screen state. Do not silently change someone else's device-wide settings. Restore changes afterward.
5. Use tape-measured distances or marked positions as ground truth. Keep orientation and placement consistent. Repeat radio measurements at least three times in both directions.

### Windows Commands

Run from the repository root. These examples assume the usual SDK location; adjust it if needed. Replace `DEVICE_SERIAL` with a serial from `adb devices -l` and select a device explicitly when several are connected.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
$serial = 'DEVICE_SERIAL'
git rev-parse HEAD
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
Get-FileHash '.\app\build\outputs\apk\debug\app-debug.apk' -Algorithm SHA256
& $adb -s $serial shell getprop ro.product.model
& $adb -s $serial shell getprop ro.build.fingerprint
& $adb -s $serial shell pm list features | Select-String 'android.hardware.uwb|android.hardware.wifi.aware|android.hardware.wifi.rtt'
& $adb -s $serial shell settings get global animator_duration_scale
& $adb -s $serial shell settings get global transition_animation_scale
& $adb -s $serial shell settings get global window_animation_scale
```

Run device tests only on a device you can interrupt; installation/test execution can restart the app and disrupt a live Ring. Connect only the intended test device, or set `ANDROID_SERIAL` for the run:

```powershell
$env:ANDROID_SERIAL = $serial
.\gradlew.bat :app:connectedDebugAndroidTest
```

`compileDebugAndroidTestKotlin` only compiles tests. Read the actual device-test report to determine whether tests passed. Check animation scales afterward: external test tooling or developer settings can leave them at zero. Keep the original values rather than assuming everyone wants `1x`.

For a timed radio run, create a local evidence directory and collect filtered logs in a separate terminal. Stop collection with Ctrl+C:

```powershell
New-Item -ItemType Directory -Force '.\verification-evidence'
& $adb -s $serial logcat -v threadtime 'DiscoveryRepository:D' 'NearbyConnections:D' 'PingRingService:I' '*:S' > '.\verification-evidence\radio-run.txt'
```

Logs may be incomplete and do not inherently prove the topology or a rejected packet. Screenshot/video and packet-level observations are separate evidence. Sanitize files and keep them outside commits. Do not clear existing device logs without the owner's consent.

## Timing Reference

These are implementation windows for the tested build, not guarantees of radio delivery. Note the underlying event time as well as when the UI changed. Scheduled cleanup can add up to approximately one five-second sweep; Android suspension can delay work further and should be recorded separately.

| Behavior | Current window |
| --- | --- |
| Local peer freshness without retained ranging/mesh refresh | 15 s, evaluated on a 5 s cleanup sweep |
| Live-key proof validity / nonce response deadline | 10 s / 15 s |
| Mesh presence heartbeat | 5 s; not queued across partitions |
| Missing join proof or unanswered host approval | 45 s for its respective stage |
| Guest join wait before reporting failure | Configurable 10-120 s, default 60 s; below the 45 s approval window a guest gives up while the host is still deciding |
| Focused UWB transport retention | 45 s or 20 s according to the actual radio plan |
| UWB bearing becomes last known / separate marker lifetime | 2 s since the last fix / 120 s since marker recording |
| RTT result freshness / peer-address freshness | 5 s / 20 s |
| GPS publication interval / maximum fix age | 10 s / 30 s; actual GPS fixes may arrive less often |
| Recovery prompt delay / snooze | Configurable 1-10 min, default 2 min / 2 min |
| Spoken update stability / minimum speech interval | 1.5 s / 10 s |
| Debug LAN host reconnect grace | 5 s in the current relay; confirm its configured value |

## V01 - Guest-To-Guest UWB

**Needs:** H plus UWB-capable A/B, UWB permission, location enabled.

**Steps:** Admit A/B directly through H. Have A track B while B tracks A. Measure at marked separations and rotate the phones. Switch each focus away and back. Repeat after dropping and restoring a transport link. For relayed coordination, establish and document an actual relay path before negotiating a new UWB session; nearby phones often create direct links automatically.

**Pass:** Offers reach only the named guest as a delivered ranging offer; the correct two phones establish a session and report their own measured distance/bearing when supported. Simultaneous focus changes do not deadlock, leak sessions, or attribute one phone's distance to another. Failure falls back without inventing bearing. Host focus stays independent.

**Evidence:** Roles, selected targets, measured positions, source labels, session start/end logs, and route evidence. A direct-link success does not close the multiple-hop subcase. The product currently selects one target per device: investigate controller resource/concurrency limits separately, without treating simultaneous ranging of two targets as a promised feature.

## V02 - UWB With Eight Members

**Needs:** Eight physical phones, with enough UWB-capable members to exercise multiple real sessions.

**Steps:** Admit seven guests. Assign different focused targets; change them in a recorded sequence. Run chat and beacons while ranging. Repeat screen-on, backgrounded, and after one link drops/reforms. Run a sustained session rather than only checking initial join.

**Pass:** The host does not admit beyond eight members. Each device uses the intended focused ranging session, releases the previous session on focus changes, and remains responsive without crashes or wrong-peer measurements. Unsupported members retain valid non-UWB behavior. Record performance rather than assuming an unmeasured latency target.

**Evidence:** Full device/topology matrix, session counts or instrumented traces, focus-change timing, delivery results, thermal and battery observations. Partial hardware participation must be stated; an 8-member Ring with only one UWB pair does not prove eight-device UWB contention behavior.

## V03 - BLE Reach

**Needs:** Two extended-advertising-capable phones with admitted keys and live-key proof. Marked indoor and outdoor routes.

**Steps:** Disable UWB and GPS; use a pair without active RTT or verify the BLE source. Walk apart in line of sight, through a doorway, and with body obstruction. Stop at measured intervals; record RSSI, source label, last local observation, and where updates stop. Walk back and repeat with reversed roles and phone placement.

**Pass:** Local RSSI updates stop when adverts/proof are unavailable and return only after valid observations/proof. Weak-signal notices describe BLE proximity, not mesh health. A mesh-only member may remain visible with range unavailable; do not use continued mesh presence as a BLE range measurement.

**Evidence:** Distance/RSSI/time table, source labels, drop-out and reacquisition distances, orientation/obstacle notes. The roadmap's approximate reach figures are hypotheses, not acceptance thresholds or guaranteed range. Compare scan passes only when the recorded radio plan actually changes.

## V04 - RSSI Distance Accuracy

**Needs:** Two capable phones and tape-measured positions; a compatible UWB pair is useful for comparison.

**Steps:** At several known separations, hold placement stable for at least 20 seconds after settling. Record the smoothed RSSI and displayed BLE estimate, not just one packet. Repeat front/back pocket, hand-held, line-of-sight, and obstructed placements. Repeat for different models and reverse sender/receiver roles.

**Pass:** Numbers are finite, attributed to the correct member, and clearly estimates. Record bias and spread; there is no pre-approved accuracy threshold. Do not claim UWB is perfect ground truth or expect a single RSSI calibration to fit every handset/obstruction.

**Analysis:** Fit `RSSI = A - 10n log10(distance)` using measured distance. Compare fitted A/n with the shipped defaults (-59 dBm and 2.2); report median absolute error, large outliers, and conditions. A repeatable large bias is a calibration finding, not evidence that more smoothing will fix it.

**Calibration evaluation (not an in-app feature):** Collect repeated readings at 1 m, 3 m, and 5 m in the target room with consistent phone placement. Fit A/n using RSSI versus `log10(distance)` and validate on a separate set of known-distance measurements. Record device pair, room, orientation, fitted parameters, and error distribution; repeat after changing these conditions. Evaluate the current RSSI Kalman filter against a recorded 1-second moving-average baseline using the same traces, checking movement lag as well as stationary jitter; see [V20](#v20---rssi-kalman-filter-evaluation). Neither filter is an accuracy guarantee. Fixed-anchor trilateration and BLE AoA need separate hardware/setup evaluation; see [tracking measurement quality proposals](roadmap.md#tracking-measurement-quality-documented-proposals-not-implemented).

## V05 - LAN Relay Teardown

**Needs:** PC plus two clients on a trusted development LAN; see [LAN setup](building.md#-lan-debug-transport-emulator--physical-device).

**Steps:** Start the debug relay and a Ring; exchange messages. Stop the relay with Ctrl+C and immediately restart it on the same port. Rejoin. Separately drop a guest socket, briefly interrupt the host socket, and keep it down beyond the relay's configured reconnect grace. Finally end normally from the host.

**Pass:** Ctrl+C closes listener/clients without an unexpected traceback and permits immediate port reuse. Guest loss is distinguished from host closure. Host reconnection within grace and loss beyond grace follow the configured relay behavior. Normal End reaches every attached guest. The LAN relay's host-grace behavior is not proof of production Nearby closure semantics.

**Evidence:** Relay console output, client state before/after, actual grace setting and timestamps. Run `python tools/test_lan_debug_relay.py` separately as the automated baseline; it does not replace these real socket/process checks.

**Background testing caveat:** With a wireless-ADB host, backgrounding the app or allowing the phone to sleep can drop wireless ADB and its reverse tunnel. The relay closes the event after 5 seconds without the host, so guests can return to Home even while the host's foreground service is still running. Check `adb devices`, the phone's reverse-tunnel list, foreground-service state, and relay closure logs before attributing this to app lifecycle handling. Prefer USB ADB for this test; verify production background behavior separately with two physical phones and LAN Debug Transport disabled. See the [known LAN background-testing limitation](building.md#-lan-debug-transport-emulator--physical-device) for recovery steps.

**Join regression:** Join by typed code with a saved guest username. The host's approval card must retain that username when repeated join proofs arrive, never replacing it with the routing ID. Approve once and verify the guest's name on the host's radar. Deny a fresh attempt and verify exactly one failure toast on the guest. Back during a pending join must cancel silently; an unanswered attempt must still show one failure toast at the configured timeout.

**Wi-Fi RSSI proxy:** With the physical phone connected to Wi-Fi, admit an emulator peer and move the phone relative to its access point. On the phone, the connected peer's dBm and RSSI-derived distance must respond to the phone's Wi-Fi signal, without changing its username. Repeat with the phone as guest and as host. Pending/rejected peers must not appear through sampling. Disable Wi-Fi and confirm a diagnostic warning and no fabricated random samples; reconnect and confirm sampling resumes. Inject a scripted report and verify automatic samples no longer overwrite that peer. This is a local debug proxy, not a measurement of the emulator-to-phone link.

Stand 1 m from the access point for admission and the first valid sample: the heading must initially show about 1 m even for a strong signal such as -41 dBm. Walk away and verify weakening signal increases distance instead of remaining at 0.3 m; return to the calibration position and verify approximately 1 m again. Peer departure/rejoin must establish a new baseline. Wi-Fi interruption must preserve the existing baseline. No directional bearing may be inferred from these RSSI samples.

## V06 - Discovery, Approval And Capacity

**Needs:** H/A, plus sufficient devices or a harness for capacity testing.

**Steps:** Confirm no Nearby PingRings browse list appears. With **Verify Nearby connections** off on both devices, join by QR and by typed code; confirm the host approval card still appears for code-only joins. Turn the switch on for both devices and retry: each new Nearby connection must show the six-digit comparison prompt, and rejecting or dismissing it must prevent connection. Compare matching digits and confirm; verify subsequent QR proof or host approval is still required as appropriate. Confirm LAN debug does not show this dialog. Allow one code-only request, deny another, hold one for 20 seconds, and leave one unanswered past 45 seconds. Disconnect a requester while held. Turn **Prevent New Users From Joining** on, attempt both kinds of join, turn it off, retry. Fill the Ring and attempt a ninth participant.

**Pass:** No PingRing appears on a phone that was not given its code or shown its QR. A QR join is admitted without a second decision; a code-only join waits for the host and is refused if unanswered. No guest traffic is authorized before admission. Denied/disconnected/expired requests leave no orphan approval. Closed/full Rings reject new members. A closed ring refuses both kinds of join while it is closed.

**Pass:** QR-secret joins follow host policy; code-only joins still require approval. No guest traffic is authorized before admission. Denied/disconnected/expired requests leave no orphan approval. Unanswered requests are refused after the timeout with callback delivery recorded. Closed/full Rings reject new members. Discovery off prevents browse listing, not otherwise-valid code joining.

**Evidence:** Screenshots on both sides without invitation secrets, request/decision timestamps, and admission logs. Test a missing join-proof timeout separately from the subsequent host-approval timeout.

**NFC tap-to-pair:** On two unlocked NFC phones, keep the host's invitation view open. On the guest, start **Tap to Join with NFC** and tap the phones together. Verify the invite reaches the same QR-secret join callback and is admitted under the same host policy; the event code alone must never be sufficient. Repeat with NFC disabled on either phone, no NFC/HCE host hardware, a malformed APDU response, and the wrong phone/tag; each must fail closed and leave QR/code joining available. Turn the host screen away and verify it no longer serves the invitation. Do not record or share raw NFC responses or invite secrets.

**Pass:** Valid NFC invitations produce the same parsed event code and secret as the host QR and follow the QR-secret admission flow. Invalid or absent invitation data does not call the join path. Unsupported/disabled NFC has a visible fallback, and ending the invitation view removes the in-memory host payload.

**Member controls:** Verify the Members drawer has selectable rows and no tracking dropdown. Selecting another row changes tracking; selecting the same row cannot clear it. In Chat Settings, save a new username and verify the local session, remote member rows, subsequent messages and future-session prefill use the new name. Reject blank/whitespace-only and case-insensitive duplicate names; repeat sanitization/length checks from hosting/joining. Closing without Save Changes must not rename the user.

## V07 - Radio Power And Battery

**Needs:** Representative hardware; repeatable placements and activity. Battery statistics or an external power meter improve confidence.

**Steps:** Compare idle baseline with matched-duration Ring runs: few/many members, UWB on/off, GPS sharing on/off, compatible RTT available/unavailable, screen-on/background, and plugged/unplugged. Record the actual `RadioPowerPolicy` plan and session activity; do not assume member count alone changes every policy input. Use the same brightness, starting charge, temperature, and network conditions. Prefer 30-60 minute runs with repetitions over short percentage observations.

**Pass:** Recorded scan effort/pass counts and UWB retention agree with the selected plan. No uncontrolled session growth or repeated failed attach loop causes unacceptable load. The 45 s versus 20 s retention cases must be observed under the inputs that actually select them. Some plan branches may need a harness because Nearby transport/hotspot use is not app-controlled.

**Evidence:** Battery drain per hour or energy, device temperature, discovery/recovery latency, packet delivery, plan logs, and session counts. Establish product battery/latency targets from measurements before declaring performance acceptable; no numeric battery budget is currently approved.

## V08 - V5 Extended-Advertising Matrix

**Needs:** Several controller models including an unsupported device, not only Android-version variation.

**Steps:** Register members through the confirmed host link, distribute keys to another guest, and verify advertisements on both receivers. Exercise controller service-data capacity for the 98-byte V5 packet. Repeat join/rejoin and key changes. Test a device without signed extended-advertising support, and a device capable of receiving adverts whose own controller cannot provide the required support.

**Pass:** Supported devices transmit/accept valid V5 member adverts. Missing keys, invalid signatures, and unsupported controllers do not produce local RSSI presence. There is no V4 member-presence fallback. Nearby messaging and any independently supported ranging can still work. Prejoin locator hints never become authenticated member presence by themselves.

**Evidence:** Exact controller/device matrix, capability/advertising callbacks, accepted source labels, and capture when available. Android's UWB/RTT feature flags do not establish extended-advertising capacity. Invalid-packet and legacy-fallback subcases need packet/harness evidence, not only a warning screenshot.

## V09 - Live Key Control For BLE Presence

**Needs:** Admitted member A, receiver B, an authorized capture/replay and nonce-delay harness for complete coverage.

**Steps:** First obtain a normal fresh nonce response and confirm BLE updates. Replay a valid captured V5 advert to a fresh receiver with no verified proof, then after an existing proof expires without a successful renewal. Delay a nonce response beyond fifteen seconds; replay an old response against a new nonce. Change the registered key, disconnect the link, end/rejoin the Ring, and repeat. Keep Nearby unavailable while allowing captured BLE packets to arrive.

**Pass:** A captured advert alone never creates/extends the ten-second proof window or advances the accepted sequence without proof. Old/late/wrong-key responses cannot renew it. Disconnect/key change/Ring teardown invalidate proof. Valid proof permits subsequent fresh-sequence BLE updates. Existing display state may persist until its normal stale timeout; lack of an immediate disappearance is not proof of an accepted replay.

**Evidence:** Receiver proof/sequence observations with monotonic timestamps, nonce-response outcomes, and packet provenance without exposing private keys. A previously unseen higher-sequence advert can still be accepted inside a valid proof window. Relay-assisted proof is within the accepted key-control threat model; this test must not claim proof of physical proximity.

## V10 - Bounded Peer Mesh

**Needs:** Three phones for one relay hop; eight for seven-hop physical coverage. Controlled topology/instrumentation is needed to exclude direct shortcuts.

**Steps:** Admit all guests directly through H first. Form/document a chain and remove shortcut links. Send uniquely numbered chat messages and beacons from both ends; verify mesh-only presence and targeted UWB coordination. Break a middle link, send a known set of messages within queue limits, restore a route, and observe replay. Repeat with an alternative route. Attempt a new join that can reach only a relay. Exercise duplicates, seventh/eighth hop, and queue overflow with an authorized harness if radio placement cannot force them reliably.

**Pass:** Already-admitted links authenticate with registered keys without another admission prompt. New members cannot join through relays. Connected paths deliver correct-member traffic best effort, without duplicate deliveries or loops. Hop count never exceeds seven. The queue stays bounded at 512 messages / 1 MiB with FIFO eviction; teardown/process death does not persist it. Presence/ranging metadata is ephemeral and is not replayed as fresh data after a partition. Relayed presence never invents local RSSI, range, or bearing.

**Evidence:** Actual authorized-link graph, origin/message IDs, delivery and replay results, hop observations, queue bounds, and recovery times. Record losses: send acceptance is not an end-to-end receipt, and best effort is not guaranteed delivery. Do not infer a relay route from BLE separation alone. If the graph cannot be verified, mark the multiple-hop/hop-bound subcases Blocked.

## V11 - Phone-To-Phone Wi-Fi RTT

**Needs:** Two phones with available Wi-Fi Aware and RTT, required permissions, location enabled, no venue access point dependency.

**Steps:** Establish an admitted Ring; disable UWB and GPS so the source is unambiguous. Confirm ranging-enabled Aware sessions and exchange fresh signed addresses. Focus A on B, measure at known positions, then change focus to another member if available. Toggle Wi-Fi/location or revoke the relevant permission on a test device. Restore availability, rotate the Aware address/session, and try again. Exercise Nearby coexistence and a pair without compatible RTT.

**Pass:** Successful results match the focused member's recent signed address, have successful status and a usable distance/deviation, and are rejected when more than five seconds old. The RTT label appears only for an actual measurement. No bearing is inferred. Unavailable hardware, permission, or coexistence gracefully falls back without a fictitious measurement. A stale address or result cannot be silently attributed to a different member.

**Evidence:** Hardware flags, service availability, session/result status, matched address binding, timestamps, measured/displayed distance, and coexistence outcomes. Signed address metadata does not authenticate FTM distance against a malicious admitted member; do not describe it as secure distance bounding.

## V12 - GNSS Sharing

**Needs:** H/A with GPS and precise location permission, outdoors for a positive fix; indoors/disabled provider for negative cases.

**Steps:** Sharing is unconditional while a Ring runs, so start by confirming there is no control to find: open PingRing Settings on H and on A and check no GPS switch exists. Confirm coordinates begin publishing on both phones without any action. At measured outdoor positions, wait for fixes and observe source/uncertainty. Confirm approximately 10 m quantization and ten-second publish cadence using a private instrumented observer. Try no fix, accuracy worse than 50 m before quantization, invalid coordinates, and fixes older than thirty seconds. Then background/return, revoke permission, partition/reconnect, and end the Ring.

**Pass:** Fixes are validated and quantized and uncertainty includes device error plus quantization allowance; 10 m is not an accuracy guarantee. GPS distance is labelled an estimate and never triggers near-member haptics. A separate GPS direction supplement is allowed only when no fresh measured bearing is available, both fixes are at most ten seconds old, a reliable compass heading is available, separation is at most 100 m, and combined position/angular error plus the heading allowance is at most 30°. Stale fixes do not become fresh through recomputation/replay. Revoking permission or ending the Ring stops publishing and clears GPS-derived estimates and retained metadata. Valid UWB/RTT distances and measured bearings remain higher priority. Note that a member can no longer decline to share: record the battery cost of publishing on every device rather than only on one.

**Supplementary direction regression:** Outdoors, test fresh good fixes with roughly 80-100 m separation and absent UWB bearing (including distance-only UWB). Verify an estimated-direction label and uncertainty in both sighted UI and TalkBack; turn the phone and test portrait/landscape relative to a fixed landmark. Check that GPS supplementation changes neither radio distance nor RSSI, and that fresh UWB angle immediately takes priority. Bring the phones close enough that their position-error regions overlap; degrade compass accuracy, pause sensors, stop GPS updates, revoke location permission, and verify the estimate disappears rather than becoming a confident arrow. Quantization/accuracy thresholds may prevent an estimate even outdoors; record actual inputs and eligibility, not merely whether an arrow appeared. No GPS estimate may become a last-known UWB marker. Physical direction accuracy remains unverified until these hardware cases pass.

**Evidence:** Source labels, uncertainty, sanitized cadence/quantization observations, stale-fix outcomes, and battery results measured with sharing on throughout. Raw coordinates are sensitive; do not place them in production logs or public evidence. UI alone cannot prove that nothing was sent or that coordinate quantization is correct: mark those subcases Blocked without instrumentation.

## V13 - Connection Recovery Prompt

**Needs:** H/A; three phones for testing host reachability through a relay.

**Steps:** Enable recovery and set the delay to one minute for a quick run; also test the default two minutes and the ten-minute limit. Lose all authenticated paths to H, note detection time, and remain on foreground Radar. Reconnect before the deadline, then repeat with Radar covered/backgrounded. Exercise Leave, two-minute snooze, silence for this Ring, the setting off, and a new Ring. End H while A is disconnected, then restore contact. Separately lose only the direct host link while a fresh signed host heartbeat still arrives by another route.

**Pass:** The deadline is based on detected loss of host contact, not an assumed exact radio-off instant. Foreground Radar prompts only when due; reconnection cancels it. No prompt appears while covered/backgrounded. Snooze and Ring-only silence work; a new Ring resets Ring-only silence. Wording does not assert that an unreachable host ended the Ring. A delivered authenticated closure ends it; continued signed host presence through a relay prevents false loss prompting.

**Evidence:** Loss detection/deadline/reconnect times, route/heartbeat evidence, screen/lifecycle state, dialog wording, and action outcomes. Avoid relying on a single Wi-Fi toggle: Nearby can choose a different transport, and BLE/GPS sightings are not proof of an authenticated host path.

## V14 - Graceful Leave Versus Unreachable

**Needs:** H/A/B.

**Steps:** With A connected, choose Leave and confirm. Observe H/B before and after A tears down. Rejoin fresh and repeat by force-stopping A or removing all its transport paths without choosing Leave. Repeat normal leave under weak/interrupted connectivity. Inject a forged departure identity through an authorized harness for the rejection subcase.

**Pass:** A delivered authenticated notice confirms departure and removes A without retaining its last-known marker. A failed send, crash, or silent disconnect is not evidence of confirmed departure. Notice handling does not mistake the immediate relay for the departing origin. Peers that never received the notice must not claim delivery. Forged/unmatched claims are rejected.

**Evidence:** Notice origin/verification and reception timestamps on H/B, sender teardown timing, resulting member/marker state, and failed-send outcomes. Waiting for a send attempt does not guarantee every peer received the notice. A graceful-leave notice is not an end-of-Ring announcement.

## V15 - Last-Known UWB Direction

**Needs:** Two UWB-capable phones, recorded heading/movement, plus a BLE-only peer for the negative case.

**Steps:** Acquire a genuine UWB bearing, walk/turn, then stop new fixes without confirming Leave. Record the last fresh-fix time. After two seconds, observe the stale bearing and age. Rotate the observing phone while keeping the real-world reference fixed. Reconnect/acquire a new fix and repeat until the two-minute marker window expires. Test normal retention and the actual shorter-retention policy. Repeat with a peer that never supplied UWB.

**Pass:** A last-known bearing is frozen as a world-space observation, not recomputed from RSSI or motion sensors. Its screen representation may rotate as the observing phone turns; its world direction must not move with the phone. Age continues increasing, fresh sightings/fixes clear the marker according to the implemented policy, and the separate marker expires two minutes after being recorded. No marker is inferred for BLE-only/GPS/RTT-only peers. Transport retention (20/45 s) and marker lifetime (120 s) are distinct timers.

**Evidence:** Video of phone rotation against a fixed reference, fresh/stale/recorded timestamps, displayed ages, reconnection and expiry. A last-known observation is not a prediction of where the person moved.

## V16 - Accessibility

**Needs:** Physical device with TalkBack and Google's Accessibility Scanner; light/dark themes and large font/display settings.

**Steps:** Follow the [device audit checklist](building.md#accessibility-device-audit). Navigate host/join, approval cards, row-only tracking, chat, username settings, beacons, settings, recovery, and Leave/End without relying on sight. Check icon names, decorative graphics skipped, switch state/disabled labels, colour-selection state, 48 x 48 dp targets, focus restoration, and text contrast. Test Reduce Motion on/off with Android animation scales both enabled and disabled. Test independent Proximity Haptics, beacon vibration, and notification vibration. Test the focusable radar summary separately from Spoken Radar Updates.

**Pass:** No essential action relies only on colour, an unlabeled icon, or a too-small target. Large text remains usable. GPS speech includes estimate/uncertainty; RTT does not imply direction. Motion returns when both app reduction is off and system animations are enabled; static beacon indicators remain when reduced. Spoken changes are polite, stable for about 1.5 s, at least ten seconds apart, and stop on background/covered Radar/dialogs. Disabling proximity haptics does not silence unrelated alerts.

**Evidence:** Scanner report, TalkBack version, per-screen findings, animation scales before/after, recordings of focus/speech, and independent vibration results. Do not mark this Pass from test compilation alone. Actual speech delivery, contrast, and focus order require device observation.

## V17 - Security Hardening

**Needs:** Owned phones, synthetic test data, and an authorized replay/fault-injection harness for active protocol checks.

**Steps:** Verify automatic join control-channel acceptance does not authorize ordinary traffic before QR-secret proof or host Allow. Deny/cancel/expire code joins and confirm no orphan state. Exercise the V08/V09 replay/key cases. Attempt a guest's sender/roster/host-control impersonation with deliberately signed test payloads from the wrong registered member. Test altered envelope body/target/origin and nonmember links. Exercise repeated connection attempts and held requests. Review production logcat while sending synthetic chat/beacons, provisioning an invite, and exchanging UWB/GPS metadata.

**Pass:** No endpoint is admitted because the transport is merely encrypted. With digit comparison enabled, only an explicitly confirmed connection advances; rejection and timeout clean up pending state. With it disabled, QR proof or host approval continues to gate ordinary traffic. Wrong registered-member/host claims and tampered origin signatures are rejected. Request queues remain bounded (eight pending control channels/approval requests per respective queue, twelve connection attempts per minute); rejected attempts do not create unbounded work or orphan state. Logs contain no chat text, beacon titles, invite secrets, raw payload JSON, coordinates, private signing material, or STS keys. No private radio API is used to bypass platform constraints. Note that initial peer impersonation/interception remains possible when digit comparison is off.

**Evidence:** Positive and negative verification outcomes, rejected-request counts/timestamps, sanitized logs, and memory/session observations under bounded load. No packet harness means the corresponding active subcases are Blocked, not Pass. Accepted boundaries remain: trusted admitted relays can read traffic, debug LAN is plaintext, live key control is not proximity proof, and signed GPS/address claims are not physical-location attestation.

## V18 - Release Key And Upgrade Readiness

**Needs:** Owner-controlled signing key, two encrypted offline backups, authorized CI configuration, and a disposable upgrade test device with synthetic data. See [Signing](building.md#signing).

**Steps:** Generate/provision the key privately, never in the repository. Restore an encrypted backup to a secure temporary location; compare its certificate fingerprint with the original. Configure all four signing secrets and inspect the signed CI artifact's `apksigner verify --verbose --print-certs` result. Separately verify no-secret CI behavior and partial-secret rejection. Install an earlier release-signed APK, populate synthetic preferences/chat state as appropriate, then install a higher-version APK signed by the same key without uninstalling. Reopen and check data. Only after these checks consider the versioned release workflow.

**Pass:** Backup restoration yields the same certificate. CI's signed artifact verifies and matches the intended fingerprint. All four secrets are required together; a tagged versioned release cannot silently fall back to debug signing. Same-key upgrades install without uninstall/signature errors and preserve applicable stored data. Ring-lifetime data is not expected to survive process death merely because installation succeeds. A debug-signed baseline cannot prove release-key upgradeability.

**Evidence:** Non-secret fingerprints, versionCodes, CI run/artifact references, verification output, and before/after preference/data checks. Do not publish keystore files, passwords, base64 contents, or temporary restored copies. Do not test destructive uninstall/different-key replacement on a phone containing real data. Restore/delete temporary test material using the owner's secure-handling process.

## V19 - Nearby Delivery Versus Radar Reach

**Needs:** At least two physical phones for direct-link tests; three phones for a relay path; representative supported Android/device combinations; synchronized timestamps or a test harness able to record sends and receipts. Use synthetic chat/beacon content and a safe, controlled venue.

**Steps:** For each device pair, record model, Android/Google Play services versions, Wi-Fi/Bluetooth state, placement, obstacles, and whether the selected peer's distance/direction is fresh, retained, or stale. At repeatable distances and placements, send uniquely identified chat messages and beacons in both directions. Correlate the sender's `sendPayload` result with the recipient's actual displayed/accepted message and the sender's per-recipient **Delivered** receipt; they are different observations. Repeat with a forced multi-hop topology and after a short partition/recovery, recording route changes, missing/duplicate messages, delivery latency, and receipt recovery. Include control runs where radar proximity is absent or stale. Repeat each condition enough times to distinguish a reproducible gap from a transient radio loss; do not use BLE RSSI or a lingering UWB direction alone as evidence that Nearby should still deliver.

**Pass:** Report delivery success rate and latency by pair, distance, topology, and condition. Identify every case where a member has a fresh proximity reading but a message remains **Sent · delivery not confirmed**, and distinguish that from a fresh receipt arriving later. Record reproducible delivery gaps as evidence for improving delivery handling or user-facing reachability status; do not infer message delivery from the ranging technologies.

**Evidence:** Sanitized per-message timestamps/IDs, sender transport-send outcomes, recipient receive/display observations, delivery receipts, proximity source and freshness, topology, device matrix, and repeated-run counts. Never record chat contents or invite secrets. Mark unsupported radios or an unverifiable route Blocked rather than assuming a result.

## V20 - RSSI Kalman Filter Evaluation

**Needs:** Two phones that support signed BLE presence; a repeatable way to capture timestamped RSSI reports and displayed estimates; tape-measured positions or a marked walking path. Use the same phone pair, placement, and path for each filtering comparison. UWB may provide an additional reference when both phones support it, but do not treat it as error-free ground truth.

**Steps:** Record stationary traces at several known separations, then record repeatable approach/retreat movement and turns with consistent phone placement. Capture raw RSSI, filtered RSSI, displayed distance, timestamps, and any fresh UWB distance. Include hand-held, front/back pocket, line-of-sight, and body-obstructed conditions. Replay each trace through the scalar Kalman implementation and, where available, the prior rolling-mean baseline using the same RSSI samples. Record the configured process/measurement variances and do not tune on the validation traces.

The shipped defaults are process variance `9 dBm²/s` and measurement variance `36 dBm²`; these are initial engineering values, not fitted handset statistics.

**Pass:** The filter produces finite, correctly attributed estimates; document rather than assume a benefit. Report stationary spread/error, response lag and overshoot after movement, and sensitivity to isolated strong reflections for both filters. Keep the Kalman implementation only if the measured trade-off meets an explicitly stated product threshold; otherwise adjust or revert it based on evidence. Passing a synthetic unit test or scripted trace alone does not establish radio accuracy or field improvement.

**Evidence:** Sanitized timestamped RSSI/distance traces, device models and builds, placement/environment, parameter values, analysis method, and per-trace comparison results. Do not include identifiers, chat content, invite secrets, or private coordinates.

## Closing A Roadmap Item

1. Attach results for every applicable subcase, including negative and recovery cases, to the exact tested build/device matrix.
2. File failures with reproducible steps and sanitized evidence. A fix needs a new run; a code change alone does not convert Fail to Pass.
3. Keep unavailable hardware, missing harnesses, and unverified topology marked Blocked. State partial coverage explicitly.
4. Move an item out of Unverified only when its required scenarios have observed Pass results. Keep platform/trust constraints under Known limitations; never convert them into invented feature guarantees.
5. Retest affected cases after changes to transport, key registration, timing, sensor libraries, permissions, or Android/device software. Preserve the measurement baseline rather than replacing it with a generic green build status.
