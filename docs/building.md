# 🚀 Building & Testing

[← Back to README](../README.md) · [User Guide](user-guide.md) · [Features](features.md) · [Architecture & Offline Operation](architecture.md) · [Roadmap](roadmap.md)

**Prerequisites**: Android Studio (Ladybug+), JDK 17, Android SDK 37, and a physical device on **Android 10 (API 29)+**. UWB needs supported hardware (e.g. Pixel 6 Pro+, Galaxy S21 Ultra+).

The interface resources cover English, Croatian, Spanish, French, Canadian French, German, and Russian. The locale smoke tests are in `LocalizationResourcesInstrumentedTest`; they run with `uiTest` on a booted device.

The security-focused JVM coverage includes `BleIdentityCryptoTest` and `BleIdentityAdvertisementTest` for signed V5 BLE identity, `NearbyAdmissionPolicyTest` for endpoint/key and graceful-departure authorization, `TransportEventsTest` and `RelayCodecTest` for departure flow/serialization, and `BleAdvertisementPayloadTest` for the V4 codec fixture. `ConnectionRecoveryPromptPolicyTest` and `UwbRetentionPolicyTest` cover outage deadlines, delay bounds, snooze eligibility, and last-known direction timing/expiry. `RadarScreenInstrumentedTest` covers recovery leave/snooze/silence, the stale-direction label, and graceful-departure UI callback; `ConnectionAuthenticationDialogTest` checks SAS confirmation UI. Run `uiTest` on a booted device to execute the Compose and device tests; real extended-advertising and sensor behavior still requires compatible physical phones.

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
2. **Enable LAN Debug Transport** in Home → Settings (gear icon, before hosting/joining) on *both* devices. The emulator reaches the relay via `10.0.2.2`; a physical device uses `127.0.0.1`, forwarded by `adb reverse`.
3. **Host on one device, join on the other** as usual — messages relay over TCP instead of Nearby Connections. Stop with `Ctrl+C`, which closes the socket and all clients cleanly so the port is immediately free.

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
| `--noise-db` | Standard deviation of symmetric jitter per reading, in dBm. `3.0` is a realistic indoor peer |
| `--outliers` | Probability a single reading arrives wildly *too strong*, as a multi-path reflection does |
| `--dropout` | Probability an advert never arrives. It is dropped, never replaced with a stale value |
| `--seed` | Seed the noise, so a failing run replays identically |

**Why `--rssi-only` matters.** A report carrying a distance is treated by the app as a ranging fix: precise by definition, so it wins outright and the smoothing filter is skipped. Adding noise to such a report only produces jitter on a number the app already trusts exactly — the filter is never involved. Sending `rssi` instead reproduces the case the filter actually exists for: a peer with no UWB, whose distance is inferred from noisy adverts. Under that mode the app runs the reading through the same `RssiSmoother` a BLE advert takes, so `--noise-db` and `--outliers` test the real filter.

Against a peer holding station at 3.16 m with one reading in twenty a 20 dB reflection — each claiming the peer is 0.39 m away — the readout stays within 0.57 m. That is the filter doing visible work, and it is exactly the case a smooth scripted trajectory could never produce.

The radar reacts as it would to real UWB: distance readout, "Direction locked" line, rings, arrow, haptics and the 15 s staleness sweep all follow the script. Run `python tools/test_mock_radar.py` to check the script and relay path with no device attached.

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


