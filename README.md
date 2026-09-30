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

## 📑 Documentation

| Document | What's in it |
| :--- | :--- |
| **[📖 User Guide](docs/user-guide.md)** | **Start here to use the app** — hosting, joining, the radar, beacons, chat, every setting, and what the messages on screen mean. |
| [✨ Features](docs/features.md) | What PingRing does and why each piece works the way it does. |
| [🏗 Architecture & Privacy](docs/architecture.md) | System architecture, protocol flow, the privacy model, and background operation. |
| [🚀 Building & Testing](docs/building.md) | Prerequisites, gradle commands, releases and signing, the LAN debug transport, and scripted-distance testing. |
| [📋 Roadmap & Known Limitations](docs/roadmap.md) | Unverified items, known limitations, deliberate non-goals, and planned work. |

Every document links back here at the top and bottom.

---

## ✨ Key Features

### 📡 Mesh & Connectivity
- **One-tap hosting**, no server registration, with **admission control** — close the ring mid-event, list it on nearby phones (**Discoverable**), or approve each join by hand.
- **Bounded capacity**: max **8 participants** (1 Host + 7 Guests), enforced by the Host.
- **Automatic fallback** from sub-metre UWB to smoothed BLE RSSI proximity when UWB is unavailable.

### 👤 Identity & Join
- **Custom usernames**, saved locally and unique within a session.
- **Join via QR** or a **6-character PingRing Code** (`e.g. AB12CD`), with a validated handshake.

### 🚨 Beacons & Chat
- **Intent presets** with 5 colour palettes and 5 vibration rhythms, plus a one-tap **Quick Beacon**.
- **Offline group chat** with sender-labelled bubbles, beacons in the transcript, and host-replayed history for latecomers.

### 🧭 Radar
- **30 FPS sensor-fusion tracking** with a directional needle, proximity haptics under 2 m, and a user-controlled tracking choice.
- **Device-aware permissions** — unsupported hardware is explained, never asked for.

Full detail, and the reasoning behind each of these: **[Features](docs/features.md)**.

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

## 🚀 Build it

**Prerequisites**: Android Studio (Ladybug+), JDK 17, Android SDK 37, and a physical device on **Android 10 (API 29)+**.

```bash
git clone https://github.com/fkurevija/PingRing.git
cd PingRing

./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # debug APK
```

Releases, signing, CI behaviour, the emulator↔device LAN debug transport and scripted-distance testing are all in **[Building & Testing](docs/building.md)**.

---

<div align="center">
  <sub>Built with ❤️ for privacy-first, off-grid peer coordination.</sub>
</div>
