# 🎯 PingRing

**Serverless, Real-Time P2P Crowd-Finding & Peer Coordination for Android**

[![Platform](https://img.shields.io/badge/Platform-Android_10%2B_%28API_29%2B%29-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack_Compose_&_Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Mesh](https://img.shields.io/badge/Mesh-Nearby_Connections-FF6F00?style=for-the-badge&logo=google&logoColor=white)](https://developers.google.com/nearby/connections/overview)
[![Ranging](https://img.shields.io/badge/Precision-Ultra--Wideband_%28UWB%29-00599C?style=for-the-badge&logo=radar&logoColor=white)](https://developer.android.com/guide/topics/connectivity/uwb)

<p align="center">
  Locate, nudge, chat, and coordinate with friends at crowded events, concerts, or festivals.<br/>
  <b>100% Serverless & Offline &bull; Zero Server Sign-in &bull; Ephemeral & Privacy-First</b>
</p>

---

## 📖 Overview

**PingRing** is a native Android app for short-lived, high-density events (festivals, conventions, sports venues, rallies) where cellular networks are congested or unavailable.

Using **Nearby Connections**, **BLE** and **UWB**, it forms an ad-hoc peer-to-peer mesh directly between nearby phones. Participants locate each other on a real-world radar, send haptic nudges, broadcast beacons, and chat in real time — with no cellular data and no external server.

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
3. **Data Sync**: The Host brokers and re-broadcasts chat, nudges, and beacons to all connected nodes.
4. **Spatial Ranging**: Concurrent UWB sessions run for sub-metre 3D positioning (distance, azimuth, elevation).

---

## ✨ Key Features

### 📡 Mesh & Connectivity
- **One-tap hosting**, no server registration. UWB and high-bandwidth Wi-Fi Direct toggleable on the fly.
- **Admission control**: the Host can stop accepting members at creation or mid-event; connection attempts are then rejected, BLE status flags update, and the Radar's QR button hides until re-enabled.
- **Bounded capacity**: max **8 participants** (1 Host + 7 Guests), enforced by the Host (`8/8 - Full`). Prevents radio/socket starvation, guaranteeing 30 FPS UWB ranging and instant messaging.
- **Automatic fallback** from sub-metre UWB to BLE RSSI proximity when UWB is unsupported or unavailable.

### 👤 Identity, Join & Nudges
- **Custom usernames**, saved locally via DataStore and pre-filled on future sessions. Names are enforced unique within a session, with an alert on collision.
- **Join via QR** (ML Kit, on the Host's on-screen code) or a **6-character PingRing Code** (`e.g. AB12CD`). Code fields force ALL CAPS, usernames use Word Capitalization. The Radar's QR button re-opens the code mid-event for latecomers.
- **Join validation**: a 2.5s timeout verifies the host; on failure the session is cleaned up and the user stays on the join screen to retry.
- **Nudges** (`👋`) target a specific peer or broadcast to all. Recipients get a distinct double-vibration pulse and banner. The button stays enabled regardless of connection or ranging state.

### 🚨 Beacons & Chat
- **Intent presets**: 🚨 Emergency, ✨ Found Something, 📍 Regroup Here, ⚠️ Caution, ⚡ Custom — with 5 colour palettes (Crimson, Amber, Emerald, Cyan, Purple) and 5 vibration rhythms (SOS/Urgent, Rapid Alert, Steady Pulse, Gentle Pulse, No Vibration). Beacons illuminate radar rings, peer pins and wave animations on receivers.
- **Offline group chat** with sender-labelled bubbles. The chat window floats over the radar while typing, then returns to its docked position.
- **Host-preserved history**: the Host keeps the transcript for the life of the PingRing and replays it to peers who join or reconnect.
- **Configurable notifications**: toggle chat push app-wide in App Settings and per-PingRing in Event Settings, with independent **sound** and **vibration** controls. Notifications are suppressed while you are already watching the radar with the screen on and unlocked; the device can still vibrate or sound.

### 🧭 Radar, Theming & Hardware Adaptation
- **30 FPS sensor-fusion tracking**: rotation-vector, magnetometer and accelerometer orient peer vectors into world space while concurrent non-blocking UWB sessions stream sub-metre distance, azimuth and elevation.
- **Proximity haptics** under 2 m; **stale peers expire** from the radar after 15 s.
- **Single tracked member**: pick one person to follow, so the radar and the one UWB session both stay on someone instead of all seven. Switching focus moves the session after a 750 ms settle. Everyone still *receives* every message regardless of who is tracked.
- **Directional radar**: a needle points at the tracked member and a lit arc on the ring widens as they approach — a sliver when far, the whole ring within arm's reach (1.5 m). Width is spaced logarithmically, so each doubling of distance gets an equal share of the arc. With no UWB bearing the ring falls back to plain signal strength rather than inventing a direction.
- **UWB sessions survive a transport drop**: if a member walks out of BLE range while still being tracked, the ranging session is held open for 45 s so walking back is instant instead of renegotiating. Ending or leaving the ring discards every retained address.
- **Theming**: Material You dynamic colour, system light/dark, an adaptive navy-and-white radar icon, and a monochrome `ic_launcher_monochrome.xml` for Android 13+ launchers.
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
./gradlew assembleDebug       # debug APK
```

On Windows (PowerShell): `.\gradlew.bat testDebugUnitTest assembleDebug`

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

**Releases.** Every push to `master` rolls a single `latest` release forward, overwriting the previous APK — it is a pointer to the newest build, not a history. Run artifacts keep each build for 90 days.

To keep a build permanently, tag it. A `v*` tag publishes an **immutable** release that is never replaced:

```bash
python tools/tag_release.py            # preview what has landed, changes nothing
python tools/tag_release.py --create   # create and push the tag
```

It suggests the next patch version, refuses to tag a dirty tree, and points the tag at an explicit commit rather than a branch name, so the release cannot move when `master` advances. Pushing the tag triggers the versioned release path in **Build APK**.

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

The radar reacts exactly as it would to real UWB: distance readout, "Direction locked" line, rings, arrow, haptics and the 15 s staleness sweep all follow the script. Run `python tools/test_mock_radar.py` to check the script and relay path with no device attached.

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

Two kinds of item: things that are **built but unverified**, and things **not built yet**. Nothing here is known to be broken.

### Unverified — needs real hardware

- **Guest→guest UWB ranging.** Relayed through the host, never tested between two UWB phones. Check: does the handshake complete across a relay hop, what happens when two guests focus each other at once, can one controller scope range two peers. Logcat: `Relaying a UWB ranging offer`, `Accepting a UWB ranging offer`.
- **UWB at 8 members.** The focus filter cut the host to one ranging session, which should have removed the old 7-session risk, but the full case is unmeasured — and the "30 FPS ranging" claim in Key Features is a design target, not a result.
- **BLE reach.** The second scan pass is a reliability gain, not a range multiplier. At what distance a peer actually disappears is unmeasured.
- **RSSI distance accuracy.** `RSSI_AT_ONE_METER = -59` and `PATH_LOSS_EXPONENT = 2.2` are defaults, not measurements; the model's accuracy rests on both. Needs calibrating at known distances.
- **LAN relay teardown.** `Ctrl+C` and host-drop handling read as complete, but have only been exercised by `test_lan_debug_relay.py` — never a real dropped connection or killed host.
- **The scripted radar is too clean.** `mock_radar.py` produces exact trajectories, so the radar never meets radio noise. Adding jitter would properly exercise the `RssiDistanceEstimator` smoothing.


### Known limitations

- **The range ceiling is the BLE scan radius.** Coded PHY (~4×) is the only BLE setting that genuinely multiplies range, and the public SDK does not expose it — `ScanSettings` has only `PHY_LE_ALL_SUPPORTED` and `AdvertiseSettings.Builder` has no `setPreferredPhy` (verified against the API 37 `android.jar`). Getting it needs hidden APIs, a deliberate non-goal here. UWB does not widen this: it never transports data, so a peer past the BLE radius is invisible to it too.
- **No mesh extension.** Adding members adds density, not reach. Every link is host↔guest under `P2P_STAR`, and a guest-to-guest link forms only *after* both already reach the host.
- **Relayed UWB offers are not end-to-end.** The STS key travels via the host. Same trust model as relayed chat — a host already brokers everything — but guest-to-guest ranging is not private against the host.
- **Wi-Fi Direct / hotspot switches are inert.** `useWifiDirect` and `useHotspot` are carried into `AdvertisingOptions.Builder`, which sets neither, so both UI controls toggle a field with no effect. Hotspot mode itself is never implemented. This is the one item here that can actively mislead a user rather than merely be unfinished.
- **Advert flags are one bit from trouble.** `acceptsNewMembers` sits at bit 6; current combinations stay under 128, but any flag above bit 6 would truncate the byte silently.
- **A retained member cannot be confirmed gone.** Holding a UWB session open across a transport drop buys an instant reconnect, but the far end stopping to range is indistinguishable from it having left the ring or from the host having ended it — all three produce the same silence. The app therefore reports a silent session as *unreachable* and says so, rather than claiming the member left. A real fix needs both sides to agree on a farewell message, which needs the transport that has just dropped.
- **The `latest` release rolls on every non-tag push**, feature branches included. Restrict to `master` if releases should track it. Only tag pushes create a permanent version, so a build worth keeping is lost after 90 days unless it is tagged.

### Out of scope

- **UWB ranging-initiated discovery** — waking a peer already out of BLE range, so UWB rather than BLE sets the reach ceiling. **Not possible on Android.** `androidx.core.uwb` exposes no discovery API at all: `UwbManager` has only the three session scopes plus availability, and `UwbControllerSessionScope` only accepts a `UwbAddress` you already hold. The spec's discovery exchange happens *inside* an established session and the platform does not expose it, so there is no way to learn a peer's 2-byte address before hearing from them. Reaching beyond BLE needs a different radio, not a different UWB API.
- **Retained members cannot be confirmed gone.** While a member is held on UWB after a transport drop, the app cannot tell "walked out of range" from "left the ring" or "the host ended the ring" — a stopped UWB session is the only signal, and all three look the same. The retention window and the unreachable report are the mitigation, not a fix; see the entry under *Known limitations*.
- **Release signing.** Only debug APKs are built and published.

### Planned work

Grouped by area. ✅ = already shipped, kept for reference.

**Global**
- ✅ Theme colour applied app-wide, including to chat bubbles.
- **Design the logo**, then match the theme colour to it. The colour currently has no logo to be derived from.

**Home — Host / Join**
- **Per-user chat bubble colour**: 4–6 presets plus a "custom" option opening a colour wheel. Beacon toasts in chat keep their own styling; the radar stays on the global colour.

**Connectivity**
- **Wire up the Wi-Fi Direct / hotspot settings** — see Known limitations.
- **Choose the connection method by capability.** UWB defaults from `isUwbSupported()` and Wi-Fi Direct is hard-coded `true`; nothing considers API level or what members support. No dropdown or checkbox list exists, only two switches.
- **Optionally make sessions discoverable** to nearby devices behind a setting, with the host approving individual joins rather than the current all-or-nothing switch.

**Radar**
- **Hide End / Leave PingRing while the keyboard is open.** The chat card already reacts to `isImeVisible`; the buttons do not.
- **Replace Nudge with a favourite beacon.** Needs a way to mark a favourite in the beacon screen first — no such concept exists.
- **Fix radar rendering.** The defect is not described anywhere, so this needs a description before it can be picked up.
- **Default the host's focus to the first member to join** rather than the nearest. No join order is recorded on peers yet, so this needs a join timestamp or an ordered friend list.
- ✅ Track one member at a time; the UWB session follows the selection.

**Radar — Chat**
- ✅ Push notifications for chat, per-PingRing and app-wide, with independent sound and vibration.

**Radar — Beacon**
- **Move Emergency off the first preset slot.** Presets render in enum order and `SOS` is first, so Emergency is the default selection when the sheet opens.
- **Embed an active beacon in the chat** so it stands out from ordinary messages. A beacon currently shows twice: a banner above the radar *and* a card in the remote-alerts list.

**Radar Settings · App Settings**
- No items yet.

---

<div align="center">
  <sub>Built with ❤️ for privacy-first, off-grid peer coordination.</sub>
</div>
