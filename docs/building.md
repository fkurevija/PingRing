# 🚀 Building & Testing

[← Back to README](../README.md) · [User Guide](user-guide.md) · [Features](features.md) · [Architecture & Offline Operation](architecture.md) · [Roadmap](roadmap.md)

**Prerequisites**: Android Studio (Ladybug+), JDK 17, Android SDK 37, and a physical device on **Android 10 (API 29)+**. UWB needs supported hardware (e.g. Pixel 6 Pro+, Galaxy S21 Ultra+).

The interface resources cover English, Croatian, Spanish, French, Canadian French, German, and Russian. The locale smoke tests are in `LocalizationResourcesInstrumentedTest`; they run with `uiTest` on a booted device.

The security-focused JVM coverage includes `BleIdentityCryptoTest` and `BleIdentityAdvertisementTest` for signed V5 BLE identity, `NearbyAdmissionPolicyTest` for endpoint/key and graceful-departure authorization, `TransportEventsTest` and `RelayCodecTest` for departure flow/serialization, and `BleAdvertisementPayloadTest` for the V4 codec fixture. `ConnectionRecoveryPromptPolicyTest` and `UwbRetentionPolicyTest` cover outage deadlines, delay bounds, snooze eligibility, and last-known direction timing/expiry. `RadarScreenInstrumentedTest` covers recovery leave/snooze/silence, the stale-direction label, the chat colour presets, and graceful-departure UI callback; `ConnectionAuthenticationDialogTest` checks SAS confirmation UI.

Every screen is covered by at least one device test, and the routes between them are asserted from both ends rather than one. `TextInputPolicyTest` covers what may be typed, and is grouped by the failure each case prevents rather than by function — a sanitizer is judged on what it destroys as much as on what it removes, so the emoji, combining-mark, ZWJ and non-ASCII cases are the point of the file and the ones a later "simplify this to a regex" edit would reintroduce. `PingRingCodeBlockInstrumentedTest` covers the invitation block on its own, so the long-press-to-copy behaviour is asserted once for both surfaces that render it. `PingRingJoinScreenInstrumentedTest` covers the join journey end to end - the back affordance, a valid name and code joining, a lower-case code being accepted because the host reads theirs aloud, and the four things the screen must refuse before calling back (blank name, name already in the ring, malformed code, full ring). `HomeScreenInstrumentedTest` asserts each of its three buttons calls back independently, `AppSettingsScreenInstrumentedTest` covers the Accessibility and Developer Options routes, asserts Developer Options is absent when `showDeveloperOptions` is false, and that About closes the list below it, and `HostSetupScreenInstrumentedTest` covers the PingRing Settings route. The point is reachability: a rewired callback on a screen with no test would otherwise strand a screen with no way in, and nothing in the suite would say so.

Run `uiTest` on a booted device to execute the Compose and device tests; real extended-advertising and sensor behavior still requires compatible physical phones.

## Coverage gate

`check` runs a JaCoCo line-coverage floor of **80% per class**, applied to `jacocoVerify`. This is a per-class rule and not a project total on purpose: an aggregate lets a well-tested policy class pay for an untested one, which is how the radar screen sat at 0% for a long time while the overall number looked respectable. The rule costs no extra test run — it reads the execution data `testDebugUnitTest` has already written.

The floor is **scoped, and the scope is the interesting part.** The gate reads unit-test data only, so anything a JVM cannot reach would fail at 0% while being thoroughly tested on a device. Two groups are excluded for that reason and are covered by the instrumented suite instead:

- the Compose screens, which need a real composition and a real `Context`;
- the framework-bound plumbing, which needs a `BluetoothAdapter`, a `Vibrator` or a `WifiRttManager` to exist at all.

What remains in scope is the code a JVM test genuinely can reach — the pure decision objects, which is where untested logic actually hides. The exclusions are listed explicitly in `coverageGateExclusions` and **rot visibly**: a new framework-bound class fails the gate until somebody names it there with a reason. That friction is deliberate, because guessing at reachability is what lets an untested policy class slip through disguised as plumbing.

Three policy classes are excluded for a narrower reason and are the real remaining debt: `RangingSourcePolicy`, `BleRangePolicy` and `RadarAccessibilityPolicy` each mix a platform probe into the same class as its rules. A per-class floor on unit data can never be met by that shape — the probe's lines are unreachable — so the fix is to move the probe out beside `DiagnosticsProbe`, as the other policies already do. See the [Roadmap](roadmap.md).

```bash
git clone https://github.com/fkurevija/PingRing.git
cd PingRing

./gradlew testDebugUnitTest   # unit tests
./gradlew uiTest              # Compose UI + device tests (needs a booted device)
./gradlew assembleDebug       # debug APK
```

On Windows (PowerShell): `.\gradlew.bat testDebugUnitTest assembleDebug`

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

For physical-radio, recovery, security, accessibility, and release-key checks, follow the [Roadmap Verification Guide](verification-guide.md). It separates runnable manual checks from scenarios requiring extra hardware or a protocol harness.

## Accessibility Device Audit

Compose semantics tests cover named Ring switches, on/off and disabled states, selected colour presets, and 48 x 48 dp target bounds. JVM tests check that spoken GPS summaries preserve estimate and uncertainty. Compiling these tests is not a substitute for executing them or testing TalkBack.

1. Install Google's Accessibility Scanner on a physical testing device and scan Home, join/host setup, the radar/chat, Ring settings, App Settings, and dialogs. Review missing labels, contrast, and touch-target findings. Interactive targets should be at least 48 x 48 dp, even when the visible icon or colour chip is smaller.
2. Enable TalkBack in Android Settings > Accessibility. Navigate by swipes and activate by double-tap without relying on the screen: host/join, compare SAS digits, choose a tracked member, send chat/a beacon, change settings, and leave/end with confirmation.
3. Check logical focus order and focus restoration after menus/dialogs close. Icon-only buttons must announce their action; decorative icons must be skipped. Switches must announce their name, state, and disabled status; colour presets must announce selection, not rely on the border alone.
4. Enable **App Settings > Accessibility > Radar screen-reader summary**. Verify distance/direction unavailable states, beacons, and last-known direction. GPS must announce an estimate and uncertainty, not precise proximity; RTT must not imply direction. Enable **Spoken Radar Updates** and verify changes are polite, debounced, at least ten seconds apart, and stopped when another destination/dialog opens or the app backgrounds. Disable **Proximity Haptics** and confirm beacon/notification vibration is unaffected. Enable **Reduce Motion**, then disable system animations separately: Home and beacon pulses should be static in both cases, with indicators still visible.
5. Repeat in light/dark themes, with increased font/display size, and on a narrow viewport. Check text contrast, clipping, overlapping targets, and keyboard/Switch Access focus. Record the device, Android/TalkBack versions, locale, findings, and retest results before closing the roadmap's accessibility audit.

## Releases

Every successful build on `master` rolls a single `latest` release forward, replacing the previous APK — it is a pointer to the newest build, not a history, and the rolling tag is moved to the commit it contains. Run artifacts keep each build for 90 days. Once a tagged release exists it takes GitHub's "Latest" badge; `latest` deliberately does not claim it.

## Signing

The rolling `latest` APK remains debug-signed. A `v*` versioned release is blocked unless the owner provisions the long-lived release key and all four CI secrets; when configured, CI builds a separate signed APK, verifies it with `apksigner`, and attaches it to the versioned release. Key creation and recovery are owner-controlled; losing the key prevents signed upgrades. Generate it outside the repository so it is never in the working tree:

```bash
keytool -genkeypair -v -keystore /secure/path/pingring-release.jks -alias pingring \
  -keyalg RSA -keysize 4096 -validity 10000

./gradlew assembleRelease \
  -PPINGRING_KEYSTORE=/secure/path/pingring-release.jks \
  -PPINGRING_KEYSTORE_PASSWORD=… -PPINGRING_KEY_ALIAS=pingring -PPINGRING_KEY_PASSWORD=…
```

Before relying on the key, make two encrypted backups on separate offline media. Restore one backup to a temporary secure path and run `keytool -list -v -keystore <restored-path> -alias pingring`; verify that the certificate fingerprint matches the original. Do not put the keystore, passwords, or a temporary restored copy in the repository.

In CI, set all four secrets: `PINGRING_KEYSTORE_BASE64` (the keystore, base64-encoded), `PINGRING_KEYSTORE_PASSWORD`, `PINGRING_KEY_ALIAS`, and `PINGRING_KEY_PASSWORD`. With none configured, signing is skipped; a partial set fails the workflow. CI runs `apksigner verify --verbose --print-certs` on the resulting release APK before uploading it as `pingring-release-apk`. Keystores are gitignored.

After the first signed APK exists and a later release has a higher `versionCode`, verify an upgrade on a test device before publishing: install the earlier signed APK, add non-sensitive test data, then install the new APK with `adb install -r <new-release.apk>`. Confirm that the update succeeds without an uninstall/signature error and that app data remains. Compare both APKs' certificate fingerprints with `apksigner verify --print-certs`; they must match. A debug-signed APK cannot serve as the baseline for this check because it has a different signing identity.

To keep a build permanently, tag it. A `v*` tag publishes an **immutable** release that is never replaced:

```bash
python tools/tag_release.py            # preview what has landed, changes nothing
python tools/tag_release.py --create   # create and push the tag
```

It suggests the next patch version, refuses to tag a dirty tree, and points the tag at an explicit commit rather than a branch name, so the release cannot move when `master` advances. Pushing the tag triggers the versioned release path in **Build APK**.

## CI behaviour

**UI tests run in CI on every push, and do not block.** The JVM tests in the fast job cannot see the UI at all — a `@Composable` body does not run until Compose composes it — so **Instrumented Tests** now runs the Compose and device suite on every push and PR rather than only on release tags. A failure there is advisory: the job is `continue-on-error`, so a red UI test never stops an APK from being built and published. The trade is that a green tick on that workflow means *the suite ran*, not *the suite passed* — every run states which in its summary, and the results are uploaded as artifacts either way.

To keep that affordable, runs for the same ref **queue rather than cancel** each other: five rapid pushes produce five sequential runs instead of five emulators competing for the same runner. Nothing is blocked on the result, so the waiting is free — and it is the intermediate runs that matter. Cancelling superseded runs is the usual optimisation, since nobody installs a build of an already-superseded commit, but a cancelled run leaves a gap exactly where the history is hardest to read: knowing that it was green on one commit and red on the next is the fact you want when something breaks.

Run it locally only if you have the machine for it — it needs a booted emulator and 10–20 minutes, mostly waiting. `./gradlew check` and `build` deliberately do not run it, so a slow machine can run `./gradlew testDebugUnitTest assembleDebug` and let CI cover the rest.

The script also judges whether a release is warranted, counting `feat`/`fix`/`perf` commits as user-facing and ignoring docs, CI and internal work. Every master build writes that assessment to its GitHub Actions run summary, so the suggestion appears where it will be seen — CI advises, it never tags on your behalf.

## 🧪 LAN Debug Transport (Emulator ↔ Physical Device)

Nearby Connections cannot bridge an emulator to a physical device, since the emulator has no real BLE/Wi-Fi Direct radio. For local development PingRing ships a **debug-only** TCP transport that virtualizes the connection over your PC's LAN/loopback, so multi-device flows can be tested without two phones.

1. **Start the relay.** Use the bundled run configuration **"app + LAN Debug Relay"**, which runs `adb reverse tcp:8942 tcp:8942` and launches `tools/lan_debug_relay.py` alongside the app. Or run it manually: `python tools\lan_debug_relay.py`.
2. **Enable LAN Debug Transport** in Home → Settings (gear icon) → Developer Options on *both* devices, before hosting or joining. The emulator reaches the relay via `10.0.2.2`; a physical device uses `127.0.0.1`, forwarded by `adb reverse`. `tools\enable_lan_debug.ps1` does this for a device, host included.
3. **Host on one device, join on the other** as usual — messages relay over TCP instead of Nearby Connections. Stop with `Ctrl+C`, which closes the socket and all clients cleanly so the port is immediately free.

Nothing connects to the relay until a device actually hosts or joins, so an idle relay showing no clients is expected rather than a fault.

**A join that sits on "connecting" is nearly always the tunnel, not the app.** The phone reaches `127.0.0.1:8942` only through `adb reverse`, so check in this order:

- `adb devices` lists the phone at all. A wireless-attached phone drops off adb on its own, and **the tunnel goes with it** — `adb reverse` is per-connection, so reconnecting (`adb connect <ip:port>`, the address `adb mdns services` reports) is not enough; re-run `adb reverse tcp:8942 tcp:8942` afterwards.
- `adb -s <serial> reverse --list` shows `tcp:8942 tcp:8942` for that device. The emulator correctly has none, because it reaches the relay directly.
- The relay host on the phone is `127.0.0.1` and on the emulator `10.0.2.2`. These differ deliberately and are not interchangeable.
- The relay's relay host field holds what you meant to type. That field validates rather than repairs, so a mistyped address is refused and the previous value kept — but a value left over from an earlier session is equally capable of connecting to nothing. `tools\enable_lan_debug.ps1` reads the field back and fails if it does not match.

The Developer Options card holding this toggle appears only in debug builds, and only while the device looks set up for development (ADB over USB/Wi-Fi, USB attached, or an emulator). It stays visible while the transport is on so it can always be switched back off.

**Debug only** — excluded from release builds, and not a substitute for testing on real hardware over Nearby Connections.

### 📡 Scripted Distance (Testing the Radar Remotely)

Over this transport nothing can measure a distance: neither the emulator (no UWB/BLE radio) nor the relay can, so peers arrive with no distance, bearing, arrow or hot/cold ring. To exercise ranging anyway, `tools/mock_radar.py` joins the event as a third, invisible participant and streams scripted range reports, rendered through the normal ranging path — only the measurement is fake, never the rendering:

```powershell
python tools\mock_radar.py <event-id> --pattern approach
python tools\mock_radar.py <event-id> --pattern orbit --distance 3
python tools\mock_radar.py <event-id> --peer a1b2c3d4 --name "Alice"

# A peer with no UWB, so the distance comes from the noisy signal instead
python tools\mock_radar.py <event-id> --rssi-only --noise-db 3 --outliers 0.05 --dropout 0.02
```

| Option | What it does |
| --- | --- |
| `--pattern approach` | Walks between `--start` and `--end` metres in place, crossing the sub-2 m proximity haptic twice per cycle (default) |
| `--pattern orbit` | Holds `--distance` while the bearing sweeps a full circle, to check the compass-corrected arrow |
| `--pattern walk` | Both at once: circling *and* closing in |
| `--pattern static` | A frozen reading, to check a steady state |
| `--peer` | Only drive these peer ids (repeatable). Default: every member of the event |
| `--name` | Display name for the driven peer(s), so two scripted pins are told apart |
| `--period`, `--rate`, `--azimuth`, `--elevation`, `--spread` | Cycle length, reports/second, starting bearing, elevation, and one bearing slice per peer |
| `--rssi-only` | Send a signal strength instead of a distance, so the reading goes through the real RSSI smoothing. **Required for the noise flags to mean anything** — see below |
| `--start`, `--end`, `--distance` | Distances in metres. Must be between **0.3 m and 40 m**, the same bounds `RssiDistanceEstimator` uses. Anything outside is refused, not clamped — see below |

**Mocked signal strength.** Every report carries an `rssi` derived by inverting the app's own estimator, so a scripted distance and a scripted dBm mean the same thing. On top of that sit `--noise-db` (symmetric jitter, `3.0` is a realistic indoor peer), `--outliers` (a multi-path reflection arriving far too strong) and `--dropout` (an advert that never arrives, dropped rather than replaced with a stale value). The signal strength is mocked either way; `--rssi-only` changes what the app *does* with it, which is the part that matters and is explained next.

**Why distances are bounded at 0.3–40 m.** Those are the app's own `RssiDistanceEstimator` limits, mirrored so a script cannot claim something the app could never report. Below 0.3 m is a bogus reading; above 40 m the estimate is noise-dominated and the app clamps it there anyway, so a scripted 200 m would render as exactly 40 m and quietly test nothing. Past the point where the curve falls under the sensitivity floor the dBm saturates too, which is the more insidious version: a peer at 60 m and one at 600 m would report identical signal strength, so the mock would be asserting they were indistinguishable. Out-of-range arguments are **refused with an error rather than silently clamped** — a silent clamp looks like a successful run while exercising the ceiling instead of the trajectory you asked for, which is the same reasoning that already rejects an out-of-range `--outliers`.

A test reads `RssiDistanceEstimator.kt` and fails if either bound moves there and not here, so the mirror cannot quietly drift.
| `--noise-db` | Standard deviation of symmetric jitter per reading, in dBm. `3.0` is a realistic indoor peer |
| `--outliers` | Probability a single reading arrives wildly *too strong*, as a multi-path reflection does |
| `--dropout` | Probability an advert never arrives. It is dropped, never replaced with a stale value |
| `--seed` | Seed the noise, so a failing run replays identically |

**Why `--rssi-only` matters.** A report carrying a distance is treated by the app as a ranging fix: precise by definition, so it wins outright and the smoothing filter is skipped. Adding noise to such a report only produces jitter on a number the app already trusts exactly — the filter is never involved. Sending `rssi` instead reproduces the case the filter actually exists for: a peer with no UWB, whose distance is inferred from noisy adverts. Under that mode the app runs the reading through the same `RssiSmoother` a BLE advert takes, so `--noise-db` and `--outliers` test the real filter.

Against a peer holding station at 3.16 m with one reading in twenty a 20 dB reflection — each claiming the peer is 0.39 m away — the readout stays within 0.57 m. That is the filter doing visible work, and it is exactly the case a smooth scripted trajectory could never produce.

The radar reacts as it would to real UWB: distance readout, "Direction locked" line, rings, arrow, haptics and the 15 s staleness sweep all follow the script. Run `python tools/test_mock_radar.py` to check the script and relay path with no device attached.

## 📱 Driving the UI by Text (device scripts)

Two PowerShell helpers sit in `tools/` for the case where a device test is not enough — checking something by hand, or setting up two devices before a scripted run. Both need a booted device and a serial from `adb devices`, which is normally two or three of them, since the emulator and a physical phone are usually both up.

- **`tools/enable_lan_debug.ps1`** walks Home → Settings → Developer Options on each device and switches on the LAN debug transport, scrolling until the row appears rather than assuming a coordinate. It optionally sets the relay host, which has to differ per device: `10.0.2.2` reaches the host machine from an emulator, while a phone needs `127.0.0.1` forwarded by `adb reverse`.
- **`tools/tap_text.ps1`** taps any single node by the text it shows:

```powershell
.\tools\tap_text.ps1 -Device emulator-5554 -List                    # what does this screen call things?
.\tools\tap_text.ps1 -Device emulator-5554 -Text 'Developer Options'
.\tools\tap_text.ps1 -Device emulator-5554 -Text 'Relay host' -Partial
```

```powershell
# Switch on the transport and set the relay host, per device
.\tools\enable_lan_debug.ps1 -Device emulator-5554 -RelayHost 10.0.2.2
.\tools\enable_lan_debug.ps1 -Device emulator-5554        # just the toggle
```

**Why text and not coordinates.** A coordinate is the one thing that cannot survive a change: a phone and an emulator differ in size, and a Compose screen moves its controls whenever the copy changes — so a coordinate script needs redrawing per device per screen, and fails by tapping whatever moved into its place. Resolving by visible text is also how a person reading the screen would name the control.

When nothing matches, `tap_text.ps1` prints the text currently on screen and exits non-zero. That is deliberate: the commonest cause of a miss is that the wording changed rather than the control disappearing, and the on-screen list is the answer to that. `-List` prints the same list without tapping, which is what to run first when a name is not what you expected. It exits non-zero on an empty screen too, because a blank screen and a locked one both dump successfully and printing nothing would leave you guessing which happened.

`adb` is resolved from `ANDROID_SDK_ROOT`, `ANDROID_HOME`, the project's `local.properties`, then `PATH` — in that order — rather than hardcoded, so a committed absolute path cannot make the script work on exactly one machine.

**Text entry is verified, not assumed.** `enable_lan_debug.ps1` reads the relay host back out of the field and fails if it does not match what was asked for. This is not ceremony: `input text` drops characters when the IME is still opening, and the result still parses as a host — `10.0.2.2` has been typed as `10.2.01` — so the mistake surfaces much later as peers that never arrive, far from the field that was mistyped. Two details make clearing the field work at all, and both were found the hard way: `adb keyevent` does not deliver `CTRL_A` reliably enough to select-all, and a `DEL` sent with the cursor at position 0 deletes forward into nothing, leaving the last character in place through any number of further presses. The script therefore clears in verified rounds, alternating `KEYCODE_DEL` and `KEYCODE_FORWARD_DEL`, and stops only when the field reads back empty.

These are conveniences for manual work, not a test suite, and they assert nothing: `uiautomator` serialises per device, so two of these against the *same* device at the same moment will collide — run them in sequence when they target one screen. For behaviour that should be checked automatically, add it to the instrumented suite.

**Verified on** an `x86_64` emulator (API 36 image) on Windows: `tap_text.ps1` across `-List`, exact and `-Partial` matching, a not-found miss, a bad serial, and an empty (locked) screen. `enable_lan_debug.ps1` end to end on the emulator — toggle on, relay host set to `10.0.2.2`, and the value read back — and reaching the relay over `10.0.2.2`. **Not** run against a physical phone: the handset in use was locked, so the phone-side half of the LAN debug flow (the `adb reverse` tunnel and `127.0.0.1`) is configured and confirmed present but not exercised end to end.

## 📥 Filing a development request

Requests used to live in a chat thread, where they are answered once and then lost: nothing lists what has been asked for, and nothing distinguishes *declined* from *forgotten*. Two pieces address that, and they are separate on purpose.

**The form** — [`.github/ISSUE_TEMPLATE/request.yml`](../.github/ISSUE_TEMPLATE/request.yml) is a GitHub Issue Form, so it renders natively on a phone with no code, no service and no secrets to leak. It asks for the situation rather than the solution ("where does this come up?"), because "it would be nice if" is a wish and "we tried X and it was ambiguous" is something that can be built. The final checkbox makes the submitter search the open issues themselves, which is the part a form can do that no bot can.

**The script** — `tools/request_issue.py` files a request from the command line and **refuses near-duplicates**, which is what a form cannot do:

```bash
python tools\request_issue.py "Add a wristband vibrate option"   # preview
python tools\request_issue.py --search-only "battery drain"       # never files
python tools\request_issue.py --create "Add group chat mute"      # file it
```

Titles are matched against open issues by stemmed-token Jaccard similarity, refusing at 0.5 and above. It **refuses rather than warns**, because a warning that scrolls past is the same as no warning — and someone running a de-duplication script has usually not searched, which is why they are there. Two deliberate choices:

- **A failed issue query raises instead of returning empty.** An empty list reads downstream as "no duplicates found", which turns a network error into a false all-clear and files the duplicate anyway — the one outcome the script exists to prevent.
- **Closed issues are reported but do not block.** A closed request may record a decision not to build something; re-filing over it silently would undo that.

Preview-only unless `--create`, matching `tag_release.py`. Needs the GitHub CLI (`gh`) authenticated. Tests: `python tools/test_request_issue.py` — pure matching, no network and no account. They assert both directions, since the threshold is only meaningful against the pairs it has to separate.

---

[← Back to README](../README.md)


