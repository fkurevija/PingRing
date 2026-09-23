# PingRing

PingRing is an Android crowd-finding prototype that helps people locate one
another during a shared, short-lived event. It is a fully peer-to-peer (P2P) 
application that uses Bluetooth Low Energy (BLE), Ultra-wideband (UWB), and 
Google Nearby Connections to discover and track friends without an external server.

## Feature overview

### Networking & connectivity

- Tap **Create a Ping-Ring** to host a local mesh; nearby devices discover it automatically over BLE without any manual pairing or server sign-in.
- The host advertises over Nearby Connections using the P2P_STAR strategy; guests discover and connect automatically on joining.
- Toggle **Ultra-wideband (UWB)** and **High Bandwidth (Wi-Fi Direct / Hotspot)** from both the Host Setup and Event Settings screens to match the hardware available at your venue.
- When UWB is unavailable the app falls back gracefully to BLE RSSI proximity — no configuration required.

### Usernames & Persistent Profiles

- Host and joining users enter a custom username when setting up or joining a Ping-Ring.
- Last entered usernames are saved locally via DataStore preferences and automatically pre-filled on future sessions to save typing.
- Enforces username uniqueness within the active Ping-Ring session — notifying users with on-screen error alerts if a chosen username is already taken.

### QR & Ping-Ring Code Join Flow

- Tap **Scan Host QR Code** from the join screen to open the camera scanner.
- Scanned QR payloads and manual text inputs accept a 6-character **Ping-Ring Code**.
- Text input fields feature smart keyboard capitalization options: Ping-Ring code fields trigger ALL CAPS input mode, while username and chat fields trigger word/sentence capitalization.
- Fallback to manual code entry is always available if camera permissions are denied or unavailable.

### Real-time P2P Chat & Floating IME Input

- Open the chat panel from the Radar screen to send text messages to everyone in the ring.
- Messages travel over the existing Nearby Connections mesh — no internet connection needed.
- The host automatically re-broadcasts every incoming message to all other peers, keeping the full group in sync.
- The floating Mesh Chat window uses IME insets (`.imePadding()`) to float smoothly above the soft keyboard when typing and return seamlessly to position when the keyboard closes.

### Peer Nudges

- Send quick, targeted alerts to specific peers (via the 👋 button on their card) or broadcast to everyone at once ("Nudge All").
- Nudges trigger a distinctive double-haptic vibration pulse and an on-screen banner on recipient devices without requiring text input.
- Routed peer-to-peer over the Nearby Connections mesh with automatic host re-broadcasting.

### Customizable Peer Beacons

- Broadcast active beacons calling other participants to your location with customizable intent presets (*🚨 SOS / Emergency*, *✨ Found Something*, *📍 Regroup Here*, *⚠️ Caution*, *⚡ Custom*).
- Personalize beacon visual styling with selectable color palettes (Crimson, Amber, Emerald, Cyan, Purple) that dynamically illuminate radar rings, peer pins, and pulsating wave animations.
- Transmit custom hardware vibration rhythms and intensities (*SOS Morse*, *Rapid Alert*, *Steady Rhythm*, *Gentle Pulse*, *Visual Only*) to receiving devices over the P2P mesh.
- Active beacons render real-time alert banners with one-tap status and dismissal controls.

### Radar & Ranging

- The radar canvas shows all peers simultaneously, each rendered with a directional arrow, distance label, and RSSI indicator.
- On UWB-capable hardware, sub-metre ranging data (distance, azimuth, elevation) is streamed at up to 30 FPS via concurrent Jetpack UWB sessions — one per peer.
- Sensor fusion combines the rotation-vector, magnetometer, and accelerometer to rotate each peer arrow into world space so it always points in the correct real-world direction regardless of how you hold the phone.
- Peers that stop advertising are automatically removed from the radar after 15 seconds of inactivity.
- The layout is portrait-locked and adapts between compact and wide screen configurations; haptic feedback fires when a peer enters close proximity.

### Adaptive & Themed App Icon

- Custom vector adaptive app icon (`ic_launcher_background.xml` & `ic_launcher_foreground.xml`) featuring dark navy circular background layers and a crisp white radar target core.
- Full Material You dynamic themed app icon support (`ic_launcher_monochrome.xml`) on Android 13+ (API 33+) system launchers.

### Session & Privacy

- Each session uses an ephemeral peer ID generated at join time; leaving or closing the ring rotates the ID and wipes all local peer data.
- You never appear on your own radar — the app self-filters its own peer ID from all scan results.
- The host can end the ring at any time; a BLE closure packet is broadcast to all nearby members, returning them to the Home screen automatically.
- Visibility can be toggled mid-session from the radar toolbar without leaving the ring.

### Permissions

- A step-by-step rationale flow covers every runtime permission required on Android 12+: Bluetooth, Nearby Devices, Location, Camera, and UWB.
- Fallback paths are available for every permission — for example, manual Ping-Ring code entry is always accessible if camera permission is denied.

## Technology

- Kotlin (2.4.20+)
- Jetpack Compose and Material 3
- Navigation 3
- Kotlin Coroutines and Flow
- **Google Nearby Connections API** (P2P coordination & Wi-Fi mesh)
- `androidx.core.uwb`
- Android Bluetooth LE APIs
- Android Sensor Framework
- Compose Material Adaptive dependencies

## Requirements

- Android Studio with Android SDK 37
- JDK 17
- Android 10 (API 29) or newer
- Bluetooth and Wi-Fi enabled
- Ultra-wideband (UWB) hardware is optional; the app falls back to BLE proximity/RSSI

## Build and test

From the repository root:

```bash
./gradlew testDebugUnitTest assembleDebug
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Privacy model

Discovery is opt-in and strictly tied to the active event. The app advertises 
ephemeral peer identifiers. Leaving an event rotates the identifier and clears 
all local peer data. Because the app is P2P, no session data is ever uploaded 
to a central server.
