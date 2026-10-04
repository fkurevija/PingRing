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
| Unanswered SAS or host-approval request | 45 s for its respective stage |
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

## V05 - LAN Relay Teardown

**Needs:** PC plus two clients on a trusted development LAN; see [LAN setup](building.md#-lan-debug-transport-emulator--physical-device).

**Steps:** Start the debug relay and a Ring; exchange messages. Stop the relay with Ctrl+C and immediately restart it on the same port. Rejoin. Separately drop a guest socket, briefly interrupt the host socket, and keep it down beyond the relay's configured reconnect grace. Finally end normally from the host.

**Pass:** Ctrl+C closes listener/clients without an unexpected traceback and permits immediate port reuse. Guest loss is distinguished from host closure. Host reconnection within grace and loss beyond grace follow the configured relay behavior. Normal End reaches every attached guest. The LAN relay's host-grace behavior is not proof of production Nearby closure semantics.

**Evidence:** Relay console output, client state before/after, actual grace setting and timestamps. Run `python tools/test_lan_debug_relay.py` separately as the automated baseline; it does not replace these real socket/process checks.

## V06 - Discovery, Approval And Capacity

**Needs:** H/A, plus sufficient devices or a harness for capacity testing.

**Steps:** Confirm no session is ever advertised as findable: host on H, join screen on A, and check there is no Nearby PingRings heading or list at any point. Join by QR from A and confirm no approval card appears on H — showing the QR is the host's decision. Then join by typed code from A; confirm H gets the approval card, allow one, deny another, hold one about a minute, and leave one unanswered past two minutes. Disconnect a requester while held. Turn **Prevent New Users From Joining** on, attempt both kinds of join, turn it off, retry. Fill the Ring and attempt a ninth participant. Compare SAS digits before confirmation.

**Pass:** No PingRing appears on a phone that was not given its code or shown its QR. A QR join is admitted without a second decision; a code-only join waits for the host and is refused if unanswered. No guest traffic is authorized before admission. Denied/disconnected/expired requests leave no orphan approval. Closed/full Rings reject new members. A closed ring refuses both kinds of join while it is closed.

**Pass:** QR-secret joins follow host policy; code-only joins still require approval. No guest traffic is authorized before admission. Denied/disconnected/expired requests leave no orphan approval. Unanswered requests are refused after the timeout with callback delivery recorded. Closed/full Rings reject new members. Discovery off prevents browse listing, not otherwise-valid code joining.

**Evidence:** Screenshots on both sides, actual digits comparison outcome without storing invitation secrets, request/decision timestamps, and admission logs. Test SAS timeout separately from the subsequent host-approval timeout.

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

**Pass:** Already-admitted links authenticate with registered keys without repeated SAS admission prompts. New members cannot join through relays. Connected paths deliver correct-member traffic best effort, without duplicate deliveries or loops. Hop count never exceeds seven. The queue stays bounded at 512 messages / 1 MiB with FIFO eviction; teardown/process death does not persist it. Presence/ranging metadata is ephemeral and is not replayed as fresh data after a partition. Relayed presence never invents local RSSI, range, or bearing.

**Evidence:** Actual authorized-link graph, origin/message IDs, delivery and replay results, hop observations, queue bounds, and recovery times. Record losses: send acceptance is not an end-to-end receipt, and best effort is not guaranteed delivery. Do not infer a relay route from BLE separation alone. If the graph cannot be verified, mark the multiple-hop/hop-bound subcases Blocked.

## V11 - Phone-To-Phone Wi-Fi RTT

**Needs:** Two phones with available Wi-Fi Aware and RTT, required permissions, location enabled, no venue access point dependency.

**Steps:** Establish an admitted Ring; disable UWB and GPS so the source is unambiguous. Confirm ranging-enabled Aware sessions and exchange fresh signed addresses. Focus A on B, measure at known positions, then change focus to another member if available. Toggle Wi-Fi/location or revoke the relevant permission on a test device. Restore availability, rotate the Aware address/session, and try again. Exercise Nearby coexistence and a pair without compatible RTT.

**Pass:** Successful results match the focused member's recent signed address, have successful status and a usable distance/deviation, and are rejected when more than five seconds old. The RTT label appears only for an actual measurement. No bearing is inferred. Unavailable hardware, permission, or coexistence gracefully falls back without a fictitious measurement. A stale address or result cannot be silently attributed to a different member.

**Evidence:** Hardware flags, service availability, session/result status, matched address binding, timestamps, measured/displayed distance, and coexistence outcomes. Signed address metadata does not authenticate FTM distance against a malicious admitted member; do not describe it as secure distance bounding.

## V12 - GNSS Sharing

**Needs:** H/A with GPS and precise location permission, outdoors for a positive fix; indoors/disabled provider for negative cases.

**Steps:** Sharing is unconditional while a Ring runs, so start by confirming there is no control to find: open PingRing Settings on H and on A and check no GPS switch exists. Confirm coordinates begin publishing on both phones without any action. At measured outdoor positions, wait for fixes and observe source/uncertainty. Confirm approximately 10 m quantization and ten-second publish cadence using a private instrumented observer. Try no fix, accuracy worse than 50 m before quantization, invalid coordinates, and fixes older than thirty seconds. Then background/return, revoke permission, partition/reconnect, and end the Ring.

**Pass:** Fixes are validated and quantized and uncertainty includes device error plus quantization allowance; 10 m is not an accuracy guarantee. Distance is labelled an estimate, draws no directional needle, and never triggers near-member haptics. Stale fixes do not become fresh through recomputation/replay. Revoking permission or ending the Ring stops publishing and clears GPS-derived estimates and retained metadata. Valid UWB/RTT remains higher priority. Note that a member can no longer decline to share: record the battery cost of publishing on every device rather than only on one.

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

**Steps:** Follow the [device audit checklist](building.md#accessibility-device-audit). Navigate host/join, SAS, tracking, chat, beacons, settings, recovery, and Leave/End without relying on sight. Check icon names, decorative graphics skipped, switch state/disabled labels, colour-selection state, 48 x 48 dp targets, focus restoration, and text contrast. Test Reduce Motion on/off with Android animation scales both enabled and disabled. Test independent Proximity Haptics, beacon vibration, and notification vibration. Test the focusable radar summary separately from Spoken Radar Updates.

**Pass:** No essential action relies only on colour, an unlabeled icon, or a too-small target. Large text remains usable. GPS speech includes estimate/uncertainty; RTT does not imply direction. Motion returns when both app reduction is off and system animations are enabled; static beacon indicators remain when reduced. Spoken changes are polite, stable for about 1.5 s, at least ten seconds apart, and stop on background/covered Radar/dialogs. Disabling proximity haptics does not silence unrelated alerts.

**Evidence:** Scanner report, TalkBack version, per-screen findings, animation scales before/after, recordings of focus/speech, and independent vibration results. Do not mark this Pass from test compilation alone. Actual speech delivery, contrast, and focus order require device observation.

## V17 - Security Hardening

**Needs:** Owned phones, synthetic test data, and an authorized replay/fault-injection harness for active protocol checks.

**Steps:** Compare SAS digits on real connections; reject/cancel/mismatch without confirming the unintended peer. Exercise the V08/V09 replay/key cases. Attempt a guest's sender/roster/host-control impersonation with deliberately signed test payloads from the wrong registered member. Test altered envelope body/target/origin and nonmember links. Exercise repeated SAS attempts and held requests. Review production logcat while sending synthetic chat/beacons, provisioning an invite, and exchanging UWB/GPS metadata.

**Pass:** No endpoint is admitted because the transport is merely encrypted. Wrong member/host claims and tampered origin signatures are rejected. Request queues remain bounded (eight concurrent SAS/approval prompts, twelve SAS prompts per minute); rejected attempts do not create unbounded work or orphan state. Logs contain no chat text, beacon titles, invite secrets, raw payload JSON, coordinates, private signing material, or STS keys. No private radio API is used to bypass platform constraints.

**Evidence:** Positive and negative verification outcomes, rejected-request counts/timestamps, sanitized logs, and memory/session observations under bounded load. No packet harness means the corresponding active subcases are Blocked, not Pass. Accepted boundaries remain: trusted admitted relays can read traffic, debug LAN is plaintext, live key control is not proximity proof, and signed GPS/address claims are not physical-location attestation.

## V18 - Release Key And Upgrade Readiness

**Needs:** Owner-controlled signing key, two encrypted offline backups, authorized CI configuration, and a disposable upgrade test device with synthetic data. See [Signing](building.md#signing).

**Steps:** Generate/provision the key privately, never in the repository. Restore an encrypted backup to a secure temporary location; compare its certificate fingerprint with the original. Configure all four signing secrets and inspect the signed CI artifact's `apksigner verify --verbose --print-certs` result. Separately verify no-secret CI behavior and partial-secret rejection. Install an earlier release-signed APK, populate synthetic preferences/chat state as appropriate, then install a higher-version APK signed by the same key without uninstalling. Reopen and check data. Only after these checks consider the versioned release workflow.

**Pass:** Backup restoration yields the same certificate. CI's signed artifact verifies and matches the intended fingerprint. All four secrets are required together; a tagged versioned release cannot silently fall back to debug signing. Same-key upgrades install without uninstall/signature errors and preserve applicable stored data. Ring-lifetime data is not expected to survive process death merely because installation succeeds. A debug-signed baseline cannot prove release-key upgradeability.

**Evidence:** Non-secret fingerprints, versionCodes, CI run/artifact references, verification output, and before/after preference/data checks. Do not publish keystore files, passwords, base64 contents, or temporary restored copies. Do not test destructive uninstall/different-key replacement on a phone containing real data. Restore/delete temporary test material using the owner's secure-handling process.

## Closing A Roadmap Item

1. Attach results for every applicable subcase, including negative and recovery cases, to the exact tested build/device matrix.
2. File failures with reproducible steps and sanitized evidence. A fix needs a new run; a code change alone does not convert Fail to Pass.
3. Keep unavailable hardware, missing harnesses, and unverified topology marked Blocked. State partial coverage explicitly.
4. Move an item out of Unverified only when its required scenarios have observed Pass results. Keep platform/trust constraints under Known limitations; never convert them into invented feature guarantees.
5. Retest affected cases after changes to transport, key registration, timing, sensor libraries, permissions, or Android/device software. Preserve the measurement baseline rather than replacing it with a generic green build status.
