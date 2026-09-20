# PingRing

PingRing is an Android crowd-finding prototype that helps people locate one
another during a shared, short-lived event. Participants join with an event
code, opt in to discovery, and receive proximity feedback from nearby peers.

## Implemented

- Event-code join flow built with Jetpack Compose and Navigation 3
- Camera-based QR scanning join flow (CameraX + on-device ML Kit QR decoding)
- Session-scoped visibility with ephemeral peer IDs
- One-active-Ping-Ring membership guard per device
- Host-only **End Ping-Ring** flow that clears the host locally and broadcasts
  event closure to nearby members, returning recipients to Home
- "Go invisible" control and event leave/cleanup handling
- Persisted in-app appearance setting: system default, light, or dark
- Android 12+ Bluetooth, location, and UWB permission rationale flow
- Camera permission prompt for QR join flow
- Bluetooth Low Energy advertising and scanning using event peer IDs
- RSSI-based hot/cold proximity classification
- Optional Jetpack UWB session initialization on supported devices
- Rotation-vector and magnetometer sensor integration
- World-space direction-vector calculation from device orientation and target azimuth
- Adaptive radar UI for compact and wide layouts
- Directional arrow, distance, RSSI, peer list, connectivity warning, and haptic feedback

## Technology

- Kotlin
- Jetpack Compose and Material 3
- Navigation 3
- Kotlin Coroutines and Flow
- Android Bluetooth LE APIs
- `androidx.core.uwb`
- Android Sensor Framework
- Compose Material Adaptive dependencies

## Requirements

- Android Studio with Android SDK 37
- JDK 11
- Android 10 (API 29) or newer
- Bluetooth LE hardware
- UWB hardware is optional; the app falls back to BLE proximity

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

Discovery is opt-in and tied to the active event. The app advertises a
short-lived peer identifier rather than a permanent account identifier.
Leaving an event rotates the local identifier and clears the in-memory peer
list. Turning visibility off stops discovery while keeping the event open.

## Current prototype limitations

- Event membership is coordinated locally and event closure is delivered over
  BLE only to nearby, actively scanning members. A backend is still required
  to synchronize membership or closure across distance, offline periods, and
  process restarts.
- UWB peer-session parameter exchange is not yet implemented. UWB-capable
  devices initialize the client session, while BLE remains the usable
  proximity fallback until peer parameters are exchanged through a transport.

## QR join flow

- From the join screen, tap **Scan QR Code** to open the scanner UI.
- The app requests camera permission only when scanner mode is opened.
- Valid scanned payloads include a plain event code (e.g. `ABC_123`) or a join
  link/query that contains an event code.
- Invalid/empty scans, permission denial, cancellation, and unavailable camera
  states are handled in-app with fallback to manual event-code entry.
