<div align="center">

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

</div>

## 📌 Table of Contents
- [Overview](#-overview)
- [System Architecture](#-system-architecture)
- [Key Features](#-key-features)
  - [📡 Mesh Networking & Connectivity](#-mesh-networking--connectivity)
  - [👤 User Profiles & Uniqueness](#-user-profiles--uniqueness)
  - [📱 QR & Ping-Ring Code Join](#-qr--ping-ring-code-join)
  - [👋 Peer Nudges](#-peer-nudges)
  - [🚨 Customizable Peer Beacons](#-customizable-peer-beacons)
  - [💬 Decentralized Mesh Chat](#-decentralized-mesh-chat)
  - [🧭 Radar & Sensor Fusion Ranging](#-radar--sensor-fusion-ranging)
  - [🎨 Dynamic Theming & App Icon](#-dynamic-theming--app-icon)
  - [🔒 Privacy & Ephemeral Security](#-privacy--ephemeral-security)
- [Tech Stack](#-tech-stack)
- [Building & Testing](#-building--testing)
- [Privacy Model](#-privacy-model)

---

## 📖 Overview

**PingRing** is a native Android crowd-finding application designed for short-lived, high-density events (music festivals, conventions, sports venues, or rallies) where cellular networks are often congested or unavailable.

By leveraging **Google Nearby Connections**, **Bluetooth Low Energy (BLE)**, and **Ultra-wideband (UWB)**, PingRing forms an ad-hoc local peer-to-peer mesh network directly between nearby smartphones. Participants can locate each other on a real-world radar, send haptic nudges, broadcast emergency/intent beacons, and chat in real time without cellular data or an external server.

---

## 🏗 System Architecture

PingRing uses a star/mesh hybrid architecture over Nearby Connections (`P2P_STAR` strategy) with BLE background discovery.

```mermaid
graph TD
    subgraph "Local Ad-Hoc Ping-Ring Mesh"
        Host["👑 Host Node<br/>(Nearby Connections Advertiser)"]
        PeerA["👤 Guest Peer A<br/>(Nearby Connections Discoverer)"]
        PeerB["👤 Guest Peer B<br/>(Nearby Connections Discoverer)"]
        PeerC["👤 Guest Peer C<br/>(Nearby Connections Discoverer)"]

        Host <-->|"Wi-Fi Direct / BLE Mesh"| PeerA
        Host <-->|"Wi-Fi Direct / BLE Mesh"| PeerB
        Host <-->|"Wi-Fi Direct / BLE Mesh"| PeerC

        PeerA -.->"Direct Jetpack UWB Ranging (30 FPS)"<.- PeerB
        Host -.->"Direct Jetpack UWB Ranging (30 FPS)"<.- PeerA
    end
```

### Protocol Flow
1. **Discovery**: Host advertises the Ping-Ring over BLE Service UUID (`0000fa1b-0000-1000-8000-00805f9b34fb`).
2. **P2P Handshake**: Guests discover the advertiser and establish an encrypted Nearby Connections session.
3. **Data Sync**: Chat messages, nudges, and beacon payloads are brokered and re-broadcasted by the Host to all connected nodes.
4. **Spatial Ranging**: High-precision UWB sessions run concurrently between nodes for sub-metre 3D positioning (distance, azimuth, elevation).

---

## ✨ Key Features

### 📡 Mesh Networking & Connectivity
- **One-Tap Hosting**: Instantly start a local Ping-Ring mesh without server registration.
- **Hardware Toggles**: Enable or disable **Ultra-Wideband (UWB)** and **High-Bandwidth Wi-Fi Direct** on the fly.
- **Automatic Fallback**: Gracefully falls back from sub-metre UWB ranging to BLE RSSI proximity if UWB is unsupported or unavailable.

### 👤 User Profiles & Uniqueness
- **Custom Usernames**: Easily set your display name when hosting or joining.
- **Persistent Local Profile**: Saves your last entered username locally via DataStore preferences for automatic pre-filling on future sessions.
- **Conflict Prevention**: Enforces name uniqueness within the active Ping-Ring session and alerts you if a chosen username is already taken.

### 📱 QR & Ping-Ring Code Join
- **Camera QR Scanner**: Join instantly by scanning the Host's on-screen QR code using ML Kit vision decoding.
- **6-Character Ping-Ring Code**: Manual fallback entry for quick code typing (`e.g. AB12CD`).
- **Context-Aware Keyboards**: Automatically opens the soft keyboard in **ALL CAPS** for code fields and **Word Capitalization** for usernames.

### 👋 Peer Nudges
- **Instant Haptic Alerts**: Send quick targeted nudges (`👋`) to specific peers or broadcast a **Nudge All** to the entire group.
- **Double-Haptic Pulse**: Recipients receive a distinct double-vibration pulse and banner notification without typing text.

### 🚨 Customizable Peer Beacons
- **Intent Presets**: Broadcast active beacons with custom intents (*🚨 SOS*, *✨ Found Something*, *📍 Regroup Here*, *⚠️ Caution*, *⚡ Custom*).
- **Visual & Haptic Personalization**: Choose from 5 color palettes (Crimson, Amber, Emerald, Cyan, Purple) and custom hardware vibration rhythms (*SOS Morse*, *Rapid Alert*, *Steady Rhythm*, *Gentle Pulse*).
- **Radar Highlighting**: Active beacons illuminate radar rings, peer pins, and wave animations on receiving devices.

### 💬 Decentralized Mesh Chat
- **Offline Group Messaging**: Text chat across the Nearby Connections mesh without internet.
- **Floating IME Input**: The chat window utilizes `.imePadding()` to smoothly float above the software keyboard when typing and return to position when dismissed.

### 🧭 Radar & Sensor Fusion Ranging
- **Real-World Orientation**: Sensor fusion integrates rotation-vector, magnetometer, and accelerometer hardware to orient peer vectors into world space.
- **30 FPS Spatial Tracking**: Concurrent non-blocking Jetpack UWB sessions stream sub-metre distance, azimuth, and elevation.
- **Proximity Alerts**: Haptic feedback triggers when a peer enters close range (< 2 meters).
- **Auto-Cleanup**: Stale or disconnected peers expire from the radar canvas after 15 seconds of inactivity.

### 🎨 Dynamic Theming & App Icon
- **Adaptive Icon**: Modern dark navy background layers with a crisp white target radar vector.
- **Material You Dynamic Icons**: Native monochrome app icon support (`ic_launcher_monochrome.xml`) on Android 13+ (API 33+) launchers.
- **System Dark/Light Theme**: Fully respects system light/dark mode and Material 3 dynamic accent palettes.

### 🔒 Privacy & Ephemeral Security
- **Ephemeral Identifiers**: Every session generates a random peer ID. Leaving or closing a Ping-Ring rotates the ID and wipes local peer state.
- **Self-Filtering**: Local peer IDs are filtered from scan results so you never appear on your own radar.
- **Host Closure**: Host can end the event at any time, broadcasting a BLE closure packet to return all members safely to the home screen.

---

## 🛠 Tech Stack

| Component | Library / Framework |
| :--- | :--- |
| **Language** | Kotlin 2.4+ |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Navigation** | Navigation 3 (`androidx.navigation3`) |
| **P2P Transport** | Google Nearby Connections API (`play-services-nearby`) |
| **Spatial Ranging** | Jetpack UWB (`androidx.core.uwb`) |
| **QR Scanning** | CameraX + Google ML Kit Barcode Scanning |
| **Local Persistence** | Jetpack DataStore Preferences |
| **Asynchrony** | Kotlin Coroutines & StateFlow |

---

## 🚀 Building & Testing

### Prerequisites
- **Android Studio** (Ladybug or newer)
- **JDK 17**
- **Android SDK 37**
- Physical Android device running **Android 10 (API 29)** or newer (UWB features require supported hardware such as Pixel 6 Pro+, Galaxy S21 Ultra+, etc.).

### Build Commands

```bash
# Clone the repository
git clone https://github.com/fkurevija/PingRing.git
cd PingRing

# Run unit tests
./gradlew testDebugUnitTest

# Assemble Debug APK
./gradlew assembleDebug
```

On **Windows (PowerShell)**:
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The compiled APK will be available at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🛡 Privacy Model

PingRing is built from the ground up to respect user privacy:

1. **No External Servers**: No account registration, email collection, or remote logging. All data stays local to the ad-hoc mesh.
2. **Ephemeral Identity**: Peer identifiers rotate every session. No persistent tracking cross-event.
3. **Session-Scoped Advertising**: BLE advertising is strictly opt-in and stops immediately when the app is closed or when leaving a Ping-Ring.

---

<div align="center">
  <sub>Built with ❤️ for privacy-first, off-grid peer coordination.</sub>
</div>
