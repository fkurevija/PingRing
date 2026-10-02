# 🏗 Architecture & Offline Operation

[← Back to README](../README.md) · [User Guide](user-guide.md) · [Features](features.md) · [Building & Testing](building.md) · [Roadmap](roadmap.md)

## System Architecture

Star/mesh hybrid over Nearby Connections (`P2P_STAR`) with BLE background discovery.

```mermaid
graph TD
    subgraph "Local Ad-Hoc PingRing Mesh"
        Host["Host Node (Nearby Connections)"]
        PeerA["Guest Peer A"]
        PeerB["Guest Peer B"]
        PeerC["Guest Peer C"]

        Host <-->|"Nearby Connections (transport selected by Nearby)"| PeerA
        Host <-->|"Nearby Connections"| PeerB
        Host <-->|"Nearby Connections"| PeerC
        Host -.-|"Focused UWB ranging when supported"| PeerA
    end
```

### Protocol Flow
1. **Discovery**: Host advertises over BLE Service UUID `0000fa1b-0000-1000-8000-00805f9b34fb`.
2. **P2P Handshake**: Guests discover the advertiser and establish an encrypted Nearby Connections session.
3. **Data Sync**: The Host brokers and re-broadcasts chat and beacons to all connected nodes.
4. **Spatial Ranging**: a focused UWB session provides distance and bearing when supported; otherwise the app estimates distance from BLE RSSI. Wi-Fi RTT and GNSS are not connected to live ranging in this build.

---

## Localization

User-facing interface, accessibility, and notification-channel text lives in Android string resources. English in `values/strings.xml` is the fallback; language-qualified resource sets provide Croatian, Spanish, French, Canadian French, German, and Russian. `locales_config.xml` exposes those choices to Android's per-app language settings on Android 13 and newer. Older supported versions use the device locale. Protocol values and participant-authored chat/beacon text are not translated.

---

## Transport security

Nearby encrypts established payload transport, but PingRing currently accepts connections without authenticating the token Nearby supplies during connection initiation. BLE service data is an unauthenticated public broadcast and currently includes the event code and host control flags. Do not treat the code, event hash, peer ID, or endpoint name as proof of identity. The reviewed attack paths and hardening plan are in the [Security Review](security-review.md).

---

## Offline operation

**Offline is the product, not a limitation.** The reason this app exists is the moment a venue's data network stops working — a sold-out stage where the bars go quiet, a rally field with no bars at all. Everything below is in service of that moment.

1. **No external infrastructure to fail.** There is no server, no account, no remote logging, and no dependency on anyone's uptime. Nothing to be down, because nothing is there. Discovery, transport, chat, ranging and beacons are all local.
2. **Nothing to configure.** No network to join, no SSID, no password, no login. Phones find each other directly over BLE and Nearby Connections.
3. **It works before it works.** A phone with no SIM, in aeroplane mode with Wi-Fi and Bluetooth on, runs a full PingRing.
4. **It degrades rather than dies.** UWB provides distance and bearing when supported; BLE signal strength is the fallback estimate. Wi-Fi RTT has policy, capability and correlation helpers but no ranging session, while GNSS is policy-only and has no peer-fix sharing path. Neither is connected to live ranging. See [Known limitations](roadmap.md#known-limitations).

### Ephemeral identity

A random peer ID per session, rotated on leave — so there is nothing that carries across events, and no account to correlate anything with. Your own ID is filtered from scan results, so you never appear on your own radar. BLE advertising is opt-in and stops on leave/end; ending an event broadcasts a closure packet that returns every member to the home screen.

### Location permission

Location permission is required because the radios that make this work are proximity sensors that Android gates behind it — not because anything is uploaded anywhere.

- **Turning location off can break ranging.** UWB depends on location access. Wi-Fi RTT also requires location services, but is not an active ranging source in this build.
- **Location is used for ranging between nearby devices, and nothing else.** No coordinates are transmitted, because there is nowhere to transmit them to.

## Background operation

While a PingRing is active, discovery, advertising, nearby transport, and supported UWB work run from an Android foreground service. The Activity may be backgrounded or its task removed without ending the PingRing, and the persistent notification reopens the radar.

Android still stops work after a force-stop, process kill, reboot, or revoked runtime permission; a new session must be started after those.

---

[← Back to README](../README.md)
