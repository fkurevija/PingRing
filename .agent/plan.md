# Project Plan

PingRing - A crowd-finding Android app using BLE and UWB.

CORE MECHANIC
- Two or more users open the app, join a shared "event" (via code/QR), and opt in to discovery.
- Real-time proximity indicator:
  - Directional arrow + distance for UWB-supported devices (androidx.core.uwb).
  - Color-coded "hot/cold" ring (RSSI-based) for non-UWB devices.
  - Arrow orientation corrected by magnetometer/rotation vector sensors.

ARCHITECTURE
- Layered: BLE (discovery), Nearby Connections/Wi-Fi Aware (data exchange), Jetpack UWB (ranging).
- Manual radio management (handling late 2026 Google API changes).
- Android 12+ runtime permissions handling.

PRIVACY & UX
- Opt-in, session-scoped visibility, ephemeral IDs.
- Auto-cleanup after events, "Go Invisible" toggle.
- Battery efficient: adaptive BLE intervals, UWB foreground-only.
- Haptic fallback (pulses based on proximity).

ADDITIONAL IDEAS
- Mesh relay for extended range.
- "Meet me here" relative pins (non-GPS).
- Notify-on-approach background mode.
- OEM-specific BLE behavior handling.

## Project Brief

# PingRing Project Brief

## Features
- **Event Joining via QR/Code**: Securely join a shared discovery session with other users using a unique event code or QR scan.
- **UWB Directional Ranging**: High-precision distance and directional guidance for UWB-supported devices, providing an "arrow-to-target" experience.
- **BLE Proximity ("Hot/Cold") Indicator**: A fallback mechanism for non-UWB devices that uses BLE RSSI values to provide a visual color-coded proximity ring.
- **Sensor-Corrected Guidance**: Integration with device magnetometer and rotation vector sensors to ensure the directional arrow remains accurate regardless of how the device is held.
- **Privacy & Visibility Controls**: Session-scoped discovery with ephemeral IDs, including a "Go Invisible" toggle for instant privacy.

## High-Level Technical Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Navigation**: Jetpack Navigation 3 (State-driven navigation)
- **Adaptive Layouts**: Compose Material Adaptive Library (for handling various screen sizes and foldables)
- **Concurrency**: Kotlin Coroutines & Flow
- **Ranging & Discovery**: Jetpack UWB (`androidx.core.uwb`), Bluetooth LE (BLE), and Nearby Connections API
- **Sensors**: Android Sensor Framework (Magnetometer and Rotation Vector)
- **Permissions**: Android 12+ Runtime Permissions handling (Precise Location, Bluetooth, etc.)

## Implementation Steps
**Total Duration:** 20m 56s

### Task_1_CoreFoundationSessionManagement: Set up runtime permissions (Bluetooth, Precise Location, UWB), State-driven Navigation 3, and session/event management layer with privacy controls.
- **Status:** COMPLETED
- **Updates:** Implemented PermissionWrapper for Android 12+ permissions. Established Navigation 3 structure with Event Join and Radar screens. Implemented SessionManager for ephemeral session data and 'Go Invisible' logic.
- **Acceptance Criteria:**
  - Runtime permissions are correctly requested and handled
  - Navigation 3 structure is established for Event Join and Main Radar screens
  - Session management data layer with Ephemeral IDs and 'Go Invisible' toggle is implemented
- **Duration:** 14m 13s

### Task_2_RangingAndSensorFusion: Implement BLE RSSI proximity tracking, Jetpack UWB ranging, and Sensor Framework integration for directional guidance.
- **Status:** COMPLETED
- **Updates:** Implemented BLE advertising/scanning for RSSI proximity. Integrated Jetpack UWB for high-precision ranging. Implemented sensor fusion using Rotation Vector and UWB azimuth for world-space directional vectors. Added connectivity management to handle manual radio state. Created DiscoveryRepository to expose these as Flows.
- **Acceptance Criteria:**
  - BLE advertising and scanning tracks peer RSSI values for proximity mapping
  - Jetpack UWB ranging computes distance and angle to target when available
  - Magnetometer and rotation vector data are fused to provide sensor-corrected directional vectors
- **Duration:** 6m 43s

### Task_3_ComposeUiAndRadarView: Create adaptive Jetpack Compose UI including QR/Code Join flow, Proximity Hot/Cold Ring, and UWB Arrow Guidance.
- **Status:** COMPLETED
- **Updates:** Replaced the peer-list-only radar with a responsive Compose radar surface, RSSI hot/cold ring, azimuth-driven directional arrow, distance/status display, haptic close-range feedback, adaptive phone/tablet layout, peer list, and visibility/connectivity states.
- **Acceptance Criteria:**
  - UI adapts to different screen sizes using Compose Material Adaptive Library
  - Hot/Cold proximity ring changes color based on BLE RSSI tracking data
  - Directional arrow indicates the target direction based on UWB and sensor fusion
- **StartTime:** 2026-09-19 13:23:53 EDT

### Task_4_RunAndVerify: Integrate data layers with UI, perform end-to-end flow execution, and execute final verification checks.
- **Status:** COMPLETED
- **Updates:** Ran `testDebugUnitTest` and `assembleDebug`; all unit tests passed and the debug APK assembled successfully.
- **Acceptance Criteria:**
  - Application builds successfully without compilation errors
  - All components are fully integrated and app does not crash during execution
  - Make sure all existing tests pass
  - Critic_agent verifies application stability, absence of crashes, and alignment with all user requirements
