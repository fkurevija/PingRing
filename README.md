# 🎯 PingRing

**Serverless, Real-Time P2P Crowd-Finding & Peer Coordination for Android**

[![Platform](https://img.shields.io/badge/Platform-Android_10%2B_%28API_29%2B%29-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack_Compose_&_Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Mesh](https://img.shields.io/badge/Mesh-Nearby_Connections-FF6F00?style=for-the-badge&logo=google&logoColor=white)](https://developers.google.com/nearby/connections/overview)
[![Ranging](https://img.shields.io/badge/Precision-Ultra--Wideband_%28UWB%29-00599C?style=for-the-badge&logo=radar&logoColor=white)](https://developer.android.com/guide/topics/connectivity/uwb)

<p align="center">
  Locate, rally, chat, and coordinate with friends at crowded events, concerts, or festivals.<br/>
  <b>100% Serverless & Offline &bull; Zero Server Sign-in &bull; Ephemeral & Privacy-First</b>
</p>

---

## 📖 Overview

**PingRing** is a native Android app for short-lived, high-density events (festivals, conventions, sports venues, rallies) where cellular networks are congested or unavailable.

Using **Nearby Connections**, **BLE** and **UWB**, it forms an ad-hoc peer-to-peer mesh directly between nearby phones. Participants locate each other on a real-world radar, raise haptic beacons, and chat in real time — with no cellular data and no external server.

---

## 🏗 System Architecture

Star/mesh hybrid over Nearby Connections (`P2P_STAR`) with BLE background discovery.

```mermaid
graph TD
    subgraph "Local Ad-Hoc PingRing Mesh"
        Host["Host Node (Nearby Connections Advertiser)"]
        PeerA["Guest Peer A (Nearby Connections Discoverer)"]
        PeerB["Guest Peer B (Nearby Connections Discoverer)"]
        PeerC["Guest Peer C (Nearby Connections Discoverer)"]

        Host <-->|"Wi-Fi Direct / BLE Mesh"| PeerA
        Host <-->|"Wi-Fi Direct / BLE Mesh"| PeerB
        Host <-->|"Wi-Fi Direct / BLE Mesh"| PeerC

        PeerA -.-|"Direct Jetpack UWB Ranging (30 FPS)"| PeerB
        Host -.-|"Direct Jetpack UWB Ranging (30 FPS)"| PeerA
    end
```

### Protocol Flow
1. **Discovery**: Host advertises over BLE Service UUID `0000fa1b-0000-1000-8000-00805f9b34fb`.
2. **P2P Handshake**: Guests discover the advertiser and establish an encrypted Nearby Connections session.
3. **Data Sync**: The Host brokers and re-broadcasts chat and beacons to all connected nodes.
4. **Spatial Ranging**: Concurrent UWB sessions run for sub-metre 3D positioning (distance, azimuth, elevation).

---

## ✨ Key Features

### 📡 Mesh & Connectivity
- **One-tap hosting**, no server registration. UWB and high-bandwidth Wi-Fi Direct toggleable on the fly.
- **Admission control**: the Host can stop accepting members at creation or mid-event; connection attempts are then rejected, BLE status flags update, and the Radar's QR button hides until re-enabled. Two more switches: **Discoverable** lists the session on nearby phones' join screens, and **Approve Each Join** holds each new member on the radar until the host allows or denies them. Both are off by default and neither affects joining by code. A request the host never answers is refused after two minutes, so putting the phone down mid-decision cannot pin a guest waiting forever.
- **Bounded capacity**: max **8 participants** (1 Host + 7 Guests), enforced by the Host (`8/8 - Full`). Prevents radio/socket starvation, guaranteeing 30 FPS UWB ranging and instant messaging.
- **Automatic fallback** from sub-metre UWB to BLE RSSI proximity when UWB is unsupported or unavailable. The RSSI path is smoothed over a rolling **2-second window** — the mean is taken in dBm and the distance curve applied once to the result, so one reflected or dropped advert cannot move the readout. A time window rather than a per-sample average, because the advertising rate varies by peer and the app only ever sees the reports that arrive.

### 👤 Identity & Join
- **Custom usernames**, saved locally via DataStore and pre-filled on future sessions. Names are enforced unique within a session, with an alert on collision.
- **Join via QR** (ML Kit, on the Host's on-screen code) or a **6-character PingRing Code** (`e.g. AB12CD`). Code fields force ALL CAPS, usernames use Word Capitalization. The Radar's QR button re-opens the code mid-event for latecomers.
- **Join validation**: a 2.5s timeout verifies the host; on failure the session is cleaned up and the user stays on the join screen to retry.

### 🚨 Beacons & Chat
- **Intent presets**: 🚨 Emergency, ✨ Found Something, 📍 Regroup Here, ⚠️ Caution, ⚡ Custom — with 5 colour palettes (Crimson, Amber, Emerald, Cyan, Purple) and 5 vibration rhythms (SOS/Urgent, Rapid Alert, Steady Pulse, Gentle Pulse, No Vibration). Beacons illuminate radar rings, peer pins and wave animations on receivers.
- **Quick Beacon** (`⚡`): set a configuration with **Set as Quick Beacon** in the beacon screen and the radar's Quick Beacon button sends that exact message, colour and rhythm in a single tap — no screen in between. With none set yet, the tap says so in a toast and opens the beacon screen.
- **Offline group chat** with sender-labelled bubbles. The chat window floats over the radar while typing, then returns to its docked position.
- **Beacons live in the chat transcript**: a beacon that is *running* is pinned to the newest end, where a current thing belongs and the eye already is. When it ends it moves back to where it was raised, ordered by timestamp among the messages around it. Nothing about how a beacon looks changes either way — same entry, same styling, just a different place in the conversation. A peer that disconnects mid-beacon has it ended rather than dropped, since it happened.
- **Host-preserved history**: the Host keeps the transcript for the life of the PingRing and replays it to peers who join or reconnect.
- **Configurable notifications**: toggle chat push app-wide in App Settings and per-PingRing in Event Settings, with independent **sound** and **vibration** controls. Notifications are suppressed while you are already watching the radar with the screen on and unlocked; the device can still vibrate or sound.

### 🧭 Radar, Theming & Hardware Adaptation
- **30 FPS sensor-fusion tracking**: rotation-vector, magnetometer and accelerometer orient peer vectors into world space while concurrent non-blocking UWB sessions stream sub-metre distance, azimuth and elevation.
- **Proximity haptics** under 2 m; **stale peers expire** from the radar after 15 s. The RSSI distance is the mean of a rolling 2-second window in dBm, with one window per peer, so a single reflected or dropped advert cannot move the readout and eight members cannot average into each other. It is a window in *time* rather than a count of samples on purpose: a sample-weighted average would smooth differently depending on how often a peer happens to advertise, which the app cannot see.
- **Who is tracked, and who decides**: a **Tracking** dropdown names whoever the radar and the single UWB session are following, and changes it — the per-row buttons can also set it, and tapping the tracked row again returns to the automatic choice. With nothing chosen, a guest follows the **host** and the host follows the **first member it saw** — role-based rather than nearest, because "nearest" changes underneath the user as people walk. Turning on **Track Host** locks every guest to the host and disables the picker for them, visibly rather than by hiding it, so the radar is never pointed at somebody with no way to find out why. The host is never locked: the policy asks *members* to follow it, it does not constrain the host. Everyone still *receives* every message regardless of who is tracked.
- **Directional radar**: a needle points at the tracked member and a lit arc on the ring widens as they approach — a sliver when far, the whole ring within arm's reach (1.5 m). Width is spaced logarithmically, so each doubling of distance gets an equal share of the arc. With no UWB bearing the ring stays plain - signal strength only tints it - rather than inventing a direction.
- **UWB sessions survive a transport drop**: if a member walks out of BLE range while still being tracked, the ranging session is held open for 45 s so walking back is instant instead of renegotiating. Ending or leaving the ring discards every retained address.
- **Theming**: a fixed violet brand palette applied throughout, honouring the system's light/dark setting, an adaptive violet-and-white radar icon, and a monochrome `ic_launcher_monochrome.xml` for Android 13+ launchers. **Material You dynamic colour is deliberately not used** — see *Planned work*.
- **Device-aware permissions**: unsupported hardware (`FEATURE_BLUETOOTH`, `FEATURE_WIFI`, `FEATURE_LOCATION`, camera, UWB, vibrator) skips its permission prompt, and its UI is disabled with an explanatory label instead of an ungrantable dialog.

---

## 🛠 Tech Stack

| Component | Library / Framework |
| :--- | :--- |
| **Language** | Kotlin 2.4+ |
| **UI / Navigation** | Jetpack Compose + Material 3, Navigation 3 (`androidx.navigation3`) |
| **P2P Transport** | Google Nearby Connections API (`play-services-nearby`) |
| **Spatial Ranging** | Jetpack UWB (`androidx.core.uwb`) |
| **QR Scanning** | CameraX + Google ML Kit Barcode Scanning |
| **Persistence / Asynchrony** | Jetpack DataStore Preferences, Kotlin Coroutines & StateFlow |

---

## 🚀 Building & Testing

**Prerequisites**: Android Studio (Ladybug+), JDK 17, Android SDK 37, and a physical device on **Android 10 (API 29)+**. UWB needs supported hardware (e.g. Pixel 6 Pro+, Galaxy S21 Ultra+).

```bash
git clone https://github.com/fkurevija/PingRing.git
cd PingRing

./gradlew testDebugUnitTest   # unit tests
./gradlew uiTest              # Compose UI + device tests (needs a booted device)
./gradlew assembleDebug       # debug APK
```

On Windows (PowerShell): `.\gradlew.bat testDebugUnitTest assembleDebug`

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

**Releases.** Every successful build on `master` rolls a single `latest` release forward, replacing the previous APK — it is a pointer to the newest build, not a history, and the rolling tag is moved to the commit it contains. Run artifacts keep each build for 90 days. Once a tagged release exists it takes GitHub's "Latest" badge; `latest` deliberately does not claim it.

**Signing.** The published APK is a debug build. A signed release build is wired up but inactive until a key exists, because a key is a secret nobody can generate on your behalf:

```bash
keytool -genkeypair -v -keystore pingring-release.jks -alias pingring \
  -keyalg RSA -keysize 4096 -validity 10000

./gradlew assembleRelease \
  -PPINGRING_KEYSTORE=pingring-release.jks \
  -PPINGRING_KEYSTORE_PASSWORD=… -PPINGRING_KEY_ALIAS=pingring -PPINGRING_KEY_PASSWORD=…
```

In CI, set `PINGRING_KEYSTORE_BASE64` (the keystore, base64-encoded) plus the three password secrets and the signed build appears as an extra artifact. All four properties are required together — a partial set is ignored rather than half-applied, and the build stays unsigned rather than silently using the debug key, which matters because a debug-signed APK cannot be upgraded by a properly signed one. Keystores are gitignored.

To keep a build permanently, tag it. A `v*` tag publishes an **immutable** release that is never replaced:

```bash
python tools/tag_release.py            # preview what has landed, changes nothing
python tools/tag_release.py --create   # create and push the tag
```

It suggests the next patch version, refuses to tag a dirty tree, and points the tag at an explicit commit rather than a branch name, so the release cannot move when `master` advances. Pushing the tag triggers the versioned release path in **Build APK**.

**UI tests run in CI on every push, and do not block.** The JVM tests in the fast job cannot see the UI at all — a `@Composable` body does not run until Compose composes it — so **Instrumented Tests** now runs the Compose and device suite on every push and PR rather than only on release tags. A failure there is advisory: the job is `continue-on-error`, so a red UI test never stops an APK from being built and published. The trade is that a green tick on that workflow means *the suite ran*, not *the suite passed* — every run states which in its summary, and the results are uploaded as artifacts either way.

To keep that affordable, a new push **cancels** the run in flight for the same ref, so the emulator is only ever working on the newest commit. Release tags are excluded from cancellation: a tag is never re-triggered, so a tag build cancelled between creating its release and uploading its APK would leave an empty release nobody could fix.

Run it locally only if you have the machine for it — it needs a booted emulator and 10–20 minutes, mostly waiting. `./gradlew check` and `build` deliberately do not run it, so a slow machine can run `./gradlew testDebugUnitTest assembleDebug` and let CI cover the rest.

The script also judges whether a release is warranted, counting `feat`/`fix`/`perf` commits as user-facing and ignoring docs, CI and internal work. Every master build writes that assessment to its GitHub Actions run summary, so the suggestion appears where it will be seen — CI advises, it never tags on your behalf.

### 🧪 LAN Debug Transport (Emulator ↔ Physical Device)

Nearby Connections cannot bridge an emulator to a physical device, since the emulator has no real BLE/Wi-Fi Direct radio. For local development PingRing ships a **debug-only** TCP transport that virtualizes the connection over your PC's LAN/loopback, so multi-device flows can be tested without two phones.

1. **Start the relay.** Use the bundled run configuration **"app + LAN Debug Relay"**, which runs `adb reverse tcp:8942 tcp:8942` and launches `tools/lan_debug_relay.py` alongside the app. Or run it manually: `python tools\lan_debug_relay.py`.
2. **Enable LAN Debug Transport** in Home → Settings (gear icon, before hosting/joining) on *both* devices. The emulator reaches the relay via `10.0.2.2`; a physical device uses `127.0.0.1`, forwarded by `adb reverse`.
3. **Host on one device, join on the other** as usual — messages relay over TCP instead of Nearby Connections. Stop with `Ctrl+C`, which closes the socket and all clients cleanly so the port is immediately free.

The Developer Options card holding this toggle appears only in debug builds, and only while the device looks set up for development (ADB over USB/Wi-Fi, USB attached, or an emulator). It stays visible while the transport is on so it can always be switched back off.

**Debug only** — excluded from release builds, and not a substitute for testing on real hardware over Nearby Connections.

#### 📡 Scripted Distance (Testing the Radar Remotely)

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

---

## 🛡 Privacy Model

1. **No external servers** — no accounts, emails, or remote logging. All data stays on the local mesh.
2. **Ephemeral identity** — a random peer ID per session; leaving or closing rotates it and wipes local peer state, so there is no cross-event tracking. Your own ID is filtered from scan results, so you never appear on your own radar.
3. **Session-scoped advertising** — BLE advertising is opt-in and stops on leave/end. The Host can end an event at any time, broadcasting a BLE closure packet that returns all members to the home screen.

## Background operation

While a PingRing is active, discovery, advertising, nearby transport, and supported UWB work run from an Android foreground service. The Activity may be backgrounded or its task removed without ending the PingRing, and the persistent notification reopens the radar.

Android still stops work after a force-stop, process kill, reboot, or revoked runtime permission; a new session must be started after those.

---

## 📋 TODO

Three kinds of item: things that are **built but unverified**, things that are **built and deliberately bounded**, and things that are **not being built**. Nothing here is known to be broken.

### Unverified — needs real hardware

- **Guest→guest UWB ranging.** Relayed through the host, never tested between two UWB phones. Check: does the handshake complete across a relay hop, what happens when two guests focus each other at once, can one controller scope range two peers. Logcat: `Relaying a UWB ranging offer`, `Accepting a UWB ranging offer`.
- **UWB at 8 members.** The focus filter cut the host to one ranging session, which should have removed the old 7-session risk, but the full case is unmeasured — and the "30 FPS ranging" claim in Key Features is a design target, not a result.
- **BLE reach.** The second scan pass is a reliability gain, not a range multiplier. The ceiling is the radio's own sensitivity, not an app setting: TI's LE 1M reference is -93 dBm, which working back from `RSSI_AT_ONE_METER` is roughly 50 m in free space, 35 m at the `PATH_LOSS_EXPONENT = 2.2` used here, and 14 m at the n=3.0 that crowded indoor venues produce. Treat ~14 m indoors as the expected figure and the exact drop-out as unmeasured. **Verify** by walking two phones apart — clear line-of-sight and through a doorway — until the peer stops appearing.
- **RSSI distance accuracy.** `RSSI_AT_ONE_METER = -59` and `PATH_LOSS_EXPONENT = 2.2` are literature defaults, not measurements on this hardware. -59 dBm at 1 m sits mid-band for a handset (-55 to -65 dBm is the commonly reported range), so it is a reasonable default rather than an arbitrary one — but the accuracy of the readout rests on both constants. One limit no amount of calibration removes: a received advert carries no transmit-power field, so a single fleet-wide constant is necessarily an average across peer models and antennas, and per-peer bias cannot be corrected from the packet. **Verify** by fitting `RSSI = A - 10n·log10(d)` against the UWB distances the app already computes for ranged peers, which needs no extra hardware.
- **LAN relay teardown.** `Ctrl+C` and host-drop handling read as complete, but have only been exercised by `test_lan_debug_relay.py` — never a real dropped connection or killed host.
- **Discovery and join approval.** Both are built and unit tested, and the approval rules are tested as a pure function, but the part that cannot be tested that way is the one that matters: holding a live Nearby Connections request open across a human decision, and whether it survives a host who takes a minute to answer. The host-side timeout now refuses anything unanswered for two minutes (`sweepExpiredJoinRequests`), so a host who walks away mid-decision cannot leave a request pinned forever — but that the refusal reaches a real device in time is still unmeasured. **Verify** by hosting on one phone with **Discoverable** and **Approve Each Join** on, and joining from the other: denial, a disconnect while the request is held, a full session, a closed session, and code joining with discovery off.

### Known limitations

- **The BLE payload is now 20 of the 31 legacy bytes.** A v1 build ignores a v2 advert instead of misreading it, so mixed-version rings half-work: a v1 guest joins by code and appears as a member, but cannot browse. The 11 spare bytes are named and asserted; going past them means BLE 5 extended advertising and losing devices that only do legacy.
- **No mesh extension.** Adding members adds density, not reach. Every link is host<->guest under `P2P_STAR`, and a guest-to-guest link forms only *after* both already reach the host.
- **The host's tracked-member choice is not broadcast.** A guest resolves the *rule* independently - it defaults to the host - but it cannot learn which member the host itself picked. Adding a field is a wire-format change rather than a UI one, and it would consume most of the payload's 11 spare bytes. It only matters with **Track Host** off, where a guest may pick freely anyway.
- **Relayed UWB offers are not end-to-end.** The STS key travels via the host. Same trust model as relayed chat - a host already brokers everything - but guest-to-guest ranging is not private against the host.
- **A retained member cannot be confirmed gone.** Holding a UWB session open across a transport drop buys an instant reconnect, but the far end stopping to range is indistinguishable from it having left the ring or from the host having ended it - all three produce the same silence. The app therefore reports a silent session as *unreachable* and says so, rather than claiming the member left. A real fix needs both sides to agree on a farewell message, which needs the transport that has just dropped.
- **Release signing is wired but dormant.** `assembleRelease` signs properly when given the four `PINGRING_*` properties, and CI picks it up from a base64 keystore secret — but no key exists yet, so the published APK is still debug-signed. Generating one is a decision to keep the same key forever: Android will not let a properly signed build be replaced by a differently signed one, so losing the key means the app can never be updated again under that identity. See *Signing* above.

### Out of scope

- **UWB ranging-initiated discovery** — waking a peer already out of BLE range, so UWB rather than BLE sets the reach ceiling. **Not possible on Android.** `androidx.core.uwb` exposes no discovery API at all: `UwbManager` has only the three session scopes plus availability, and `UwbControllerSessionScope` only accepts a `UwbAddress` you already hold. The spec's discovery exchange happens *inside* an established session and the platform does not expose it, so there is no way to learn a peer's 2-byte address before hearing from them. Reaching beyond BLE needs a different radio, not a different UWB API.
- **Extending range with a coded PHY.** Coded PHY is the only BLE setting that genuinely multiplies range, and the public SDK does not expose it — `ScanSettings` has only `PHY_LE_ALL_SUPPORTED` and `AdvertiseSettings.Builder` has no `setPreferredPhy` (verified against the API 37 `android.jar`). Getting it needs hidden APIs, a deliberate non-goal here. The commonly quoted "~4×" is a free-space figure: 4× range is 12 dB of extra link budget, and at the `PATH_LOSS_EXPONENT = 2.2` used here the same 12 dB is 3.5×. Silicon Labs quotes 4–6 dB of real-world sensitivity gain instead, which is 1.5–1.8× at that exponent. Either way it would not help PingRing: the gain applies to established connections via `HCI_LE_SetPhy`, and PingRing advertises non-connectable and never forms a link. UWB does not widen the reach either — it never transports data, so a peer past the BLE radius is invisible to it too.

### Planned work

Grouped by area. Nothing here is known to be broken: these are things not built, or built and unverified. Work that has shipped is described under *Key Features* rather than kept here.

**Global**
- **Define the brand colour and match it to the logo.** *(NEEDS CONFIRMATION)* A violet (`#5C2BD9`) is now the app's fixed primary in both light and dark, and the launcher icon has been brought into the same family — but the colour was chosen by eye from a swatch, not derived from a logo, because no logo exists yet. **Confirm** it is the colour the brand should have before anything is built on it: the tone, the exact hex, whether dark mode should lighten it as it currently does, and whether amber is right for tertiary. If a logo is designed later this should be revisited rather than assumed to match.
- **Per-user chat bubble colour**: 4 presets plus a "custom" option opening a colour wheel. Today every bubble uses the global theme colour. Beacon entries keep their own styling and the radar stays global — neither is affected by the choice.
- **Replace the v1 advertisement compatibility path with v2 only.** v1 has never shipped outside test devices, so reading it is code that exists to serve nobody. Drop it unless v1 and v2 are deliberately kept for different hardware ages or API levels — in which case say so, because the current shape implies a compatibility promise the project has not made.
- **Accessibility audit.** Decorative icons correctly pass `contentDescription = null`, but nothing has checked the app against an accessibility standard, and the radar is drawn on a `Canvas` that no screen reader can describe at all.
- **Multi-language support.** Every string is a hard-coded literal.
- **Help and documentation.** An open question rather than a decision: an offline in-app knowledge base versus spreading help through the app. Needs Iloha's input before it is worth building.
- **Security review.** Untested and unaudited: whether connections can be eavesdropped, and whether the BLE advert or the Nearby handshake can be leveraged against a device. The app's privacy claims rest on this being answered.
- **Development request portal.** Consider a form or bot that opens — and de-duplicates against existing — GitHub issues, so requests are tracked rather than living in chat.

**Home — Host / Join**
- **The two settings screens should not both own the event switches.** The host setup screen duplicates five of them — Accept New Members, Track Host Only, UWB, Wi-Fi, Chat Notifications — and reaches Discoverable and Approve Each Join only through an **All Settings** link. One screen should own them all.
- **Give peers a join timestamp.** There is none on `RadarPeerUpdate`, so "first to join" has to stand in for "first to be discovered": a member who joins late and is nearest can still be picked as the host's default. It costs eight bytes on the advertisement, so it is a wire-format change rather than a UI one, but until it exists the default is not quite what it claims.

**Connectivity**
- ~~**Implement Wi-Fi Direct, or a host hotspot with clients joining it.**~~ Partly resolved, and the misleading half is gone: Nearby Connections carries its payload channel over Wi-Fi Direct whether or not the app asks, so the **High Bandwidth (Wi-Fi)** switch was removed from both settings screens rather than left toggling a field with no effect. What remains is the part the app cannot supply — an explicit transport choice, or a host-run hotspot for guests that are out of BLE range but still on the same network.
- **Choose the connection method by capability.** UWB defaults from `isUwbSupported()` and Wi-Fi Direct is hard-coded `true`; nothing considers API level or what members support. No dropdown or checkbox list exists, only two switches.
- **Tune antenna use across BLE, UWB and Wi-Fi.** Three radios competing on one battery, with BLE scanning continuously and UWB ranging held open for 45 s after a drop. Nothing has measured what that costs.

**Radar — Beacon**
- **Move Emergency off the first preset slot.** Presets render in enum order and `SOS` is first, so Emergency is the default selection when the sheet opens — and it is a one-line move in `BeaconPreset`.

**Radar Settings · App Settings**
- No items yet.

---

<div align="center">
  <sub>Built with ❤️ for privacy-first, off-grid peer coordination.</sub>
</div>
