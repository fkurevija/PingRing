# 🎯 PingRing

**Serverless, Real-Time P2P Crowd-Finding & Peer Coordination for Android**

[![Build APK](https://github.com/fkurevija/PingRing/actions/workflows/build-apk.yml/badge.svg)](https://github.com/fkurevija/PingRing/actions/workflows/build-apk.yml)
[![Instrumented Tests](https://github.com/fkurevija/PingRing/actions/workflows/instrumented-tests.yml/badge.svg)](https://github.com/fkurevija/PingRing/actions/workflows/instrumented-tests.yml)
[![Platform](https://img.shields.io/badge/Platform-Android_10%2B_%28API_29%2B%29-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack_Compose_&_Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Mesh](https://img.shields.io/badge/Mesh-Nearby_Connections-FF6F00?style=for-the-badge&logo=google&logoColor=white)](https://developers.google.com/nearby/connections/overview)
[![Ranging](https://img.shields.io/badge/Precision-Ultra--Wideband_%28UWB%29-00599C?style=for-the-badge&logo=radar&logoColor=white)](https://developer.android.com/guide/topics/connectivity/uwb)

<p align="center">
  Locate, rally, chat, and coordinate with friends at crowded events, concerts, or festivals.<br/>
  <b>100% Serverless & Offline &bull; No Network Needed &bull; No Sign-in, No Accounts</b>
</p>

---

## 📖 Overview

**Offline is the product** — this app exists for the moment a venue's data network stops working. There is no server, no account, and nothing to configure; phones find each other directly over BLE and Nearby Connections, and ranging falls back rather than dying.

Using **Nearby Connections** with BLE discovery, it forms an ad-hoc host-and-guests group directly between nearby phones. Where supported, UWB ranges the focused member; BLE signal strength is the fallback. Participants locate each other on a real-world radar, raise haptic beacons, and chat in real time — with no cellular data and no external server.

Location permission is required because the radios doing the ranging are proximity sensors Android gates behind it, not because anything is uploaded. There is nowhere to upload it to.

---

## 📑 Documentation

| Document | What's in it |
| :--- | :--- |
| **[📖 User Guide](docs/user-guide.md)** | **Start here to use the app** — hosting, joining, the radar, beacons, chat, every setting, and what the messages on screen mean. |
| [✨ Features](docs/features.md) | What PingRing does and why each piece works the way it does. |
| [🏗 Architecture & Offline Operation](docs/architecture.md) | System architecture, protocol flow, what offline means here, and background operation. |
| [🚀 Building & Testing](docs/building.md) | Prerequisites, gradle commands, releases and signing, the LAN debug transport, and scripted-distance testing. |
| [📋 Roadmap & Known Limitations](docs/roadmap.md) | Unverified items, known limitations, deliberate non-goals, and planned work. |

Every document links back here at the top and bottom.

---

## ✨ Key Features

### 📡 Mesh & Connectivity
- **One-tap hosting**, no server registration, with **admission control** — close the ring mid-event, list it on nearby phones (**Discoverable**), or approve each join by hand.
- **Bounded capacity**: max **8 participants** (1 Host + 7 Guests), enforced by the Host.
- **UWB ranging with BLE RSSI fallback**: UWB supplies distance and bearing for the focused member when supported; otherwise BLE signal strength estimates proximity. Wi-Fi RTT and GNSS policy foundations are not connected to live ranging yet; see [Known limitations](docs/roadmap.md#known-limitations).

### 👤 Identity & Join
- **Custom usernames**, saved locally and unique within a session.
- **Join via QR** or a **6-character PingRing Code** (`e.g. AB12CD`), with a validated handshake.
- **Localized interface** in English, Croatian, Spanish, French, Canadian French, German, and Russian. Android 13+ offers an app-specific language setting; older versions follow the device language.

### 🚨 Beacons & Chat
- **Intent presets** with 5 colour palettes and 5 vibration rhythms, plus a one-tap **Quick Beacon**.
- **Offline group chat** with sender-labelled bubbles, beacons in the transcript, and host-replayed history for latecomers.

### 🧭 Radar
- **Sensor-fusion tracking** with a directional needle, proximity haptics under 2 m, and a user-controlled tracking choice.
- **UWB readings cross-checked against BLE.** In a crowd, UWB's dominant error is not noise but *obstruction* — a body between two phones makes the distance read long, often by metres. BLE degrades under that same body, so it is an independent measurement of the same geometry, and the radar reports **Cross-checked**, **Someone may be in the way**, or **Distance estimate only** when there is only one source.
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
| **Serialization** | Moshi (relay payloads) + kotlinx.serialization |
| **Persistence / Asynchrony** | Jetpack DataStore Preferences, Kotlin Coroutines & StateFlow |
| **Testing** | JUnit 4, `kotlinx-coroutines-test`, Compose UI Test, JaCoCo |

---

## 🚀 Build it

**Prerequisites**: Android Studio (Ladybug+), JDK 17, Android SDK 37, and a physical device on **Android 10 (API 29)+**.

```bash
git clone https://github.com/fkurevija/PingRing.git
cd PingRing

./gradlew testDebugUnitTest   # unit tests
./gradlew uiTest              # Compose UI + device tests (needs a booted device)
./gradlew assembleDebug       # debug APK
```

`check` and `build` deliberately skip the instrumented tests so a slow machine can stay fast — CI runs them on every push instead.

Releases, signing, CI behaviour, the emulator↔device LAN debug transport and scripted-distance testing are all in **[Building & Testing](docs/building.md)**.

---

<div align="center">
  <sub>Built with ❤️ for staying connected when the network isn't.</sub>
</div>
