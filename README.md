# PingRing

PingRing is an Android crowd-finding prototype that helps people locate one
another during a shared, short-lived event. Participants join with an event
code, opt in to discovery, and receive proximity feedback from nearby peers.

## Implemented

- Event-code join flow built with Jetpack Compose and Navigation 3
- Session-scoped visibility with ephemeral peer IDs
- "Go invisible" control and event leave/cleanup handling
- Android 12+ Bluetooth, location, and UWB permission rationale flow
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

- QR scanning is represented by the join-screen placeholder and is not yet
  connected to a camera scanner.
- UWB peer-session parameter exchange is not yet wired to a backend or
  Nearby Connections transport; the repository retains a structured fallback
  path for BLE-only discovery.
- Event coordination is currently local/in-memory and does not persist or
  synchronize event membership between devices.
