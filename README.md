# PingRing

PingRing is an Android crowd-finding prototype that helps people locate one
another during a shared, short-lived event. It is a fully peer-to-peer (P2P) 
application that uses Bluetooth Low Energy (BLE), Ultra-wideband (UWB), and 
Google Nearby Connections to discover and track friends without an external server.

## Implemented

- **Fully Peer-to-Peer (P2P):** All coordination, chat messaging, and UWB parameter exchange occurs 
  directly between devices using Google Nearby Connections. No external server required.
- **Real-Time P2P Chat:** Decentralized group text messaging across hosted and joined 
  Ping-Ring events. The host automatically brokers packets outward to all remote mesh nodes.
- **Intelligent Connectivity:** Automatically selects the best connection method 
  (UWB, Wi-Fi Direct, or Hotspot) based on device support and host preferences.
- **Connectivity Controls:** Toggles for Ultra-wideband (UWB) and High Bandwidth (Wi-Fi) 
  coordination available in both Host Setup and Event Settings for maximum compatibility.
- **Multi-Peer Tracking:** Simultaneous tracking of multiple friends on a single radar 
  using concurrent, non-blocking UWB sessions.
- **Auto-Cleanup:** Automatic removal of stale peers from the radar after 15 seconds of 
  inactivity to ensure data accuracy.
- **Event-code join flow:** Built with Jetpack Compose and Navigation 3.
- **QR Scanning:** Camera-based join flow using CameraX and on-device ML Kit QR decoding.
- **Session-scoped Privacy:** Ephemeral peer IDs and visibility controls. Self-filtering 
  ensures you don't appear on your own radar.
- **Host-only Event Closure:** Clear the event locally and broadcast closure to 
  nearby members via BLE, returning them to the Home screen.
- **High-Precision Ranging:** Real Jetpack UWB session setup on supported devices with 
  throttled 30 FPS world-space vector updates for performance stability.
- **Sensor Fusion:** Integration of rotation-vector, magnetometer, and accelerometer 
  sensors for world-space direction calculation.
- **Adaptive Radar UI:** Portrait-locked dynamic layout that adapts to compact and 
  wide screens, featuring directional arrows, distance, RSSI, and haptic feedback.
- **Android 12+ Permissions:** Comprehensive rationale flow for Bluetooth, 
  Location, Nearby Devices, and UWB permissions.

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

## QR join flow

- Tap **Scan QR Code** from the join screen to open the scanner.
- Scanned payloads must contain a valid event code.
- Fallback to manual entry is always available if the camera is denied or unavailable.
