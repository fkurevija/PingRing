# PingRing Security Review

[← Back to README](../README.md) · [Roadmap](roadmap.md) · [Architecture & Offline Operation](architecture.md)

**Reviewed:** 2026-10-02
**Scope:** BLE advertisement contents and handling; Nearby Connections admission, peer identity, and payload routing; sensitive diagnostic logging; join-request abuse.

## Assessment

This was a static source review of the production Android paths. No packet capture, active radio spoofing, or two-device man-in-the-middle exercise was possible in this environment, so exploitability has not been demonstrated on hardware. The threat model is a person within BLE/Nearby radio range who can observe and transmit advertisements or advertise a Nearby service. It does not assume control of either phone's OS.

Nearby Connections encrypts established payload transport, but encryption alone does not authenticate which app peer is on the other end. Google's [connection-management guidance](https://developers.google.com/nearby/connections/android/manage-connections#authenticate_the_connection) recommends comparing the shared authentication digits. PingRing now requires an explicit user confirmation before accepting the transport connection.

## Implemented Mitigations

### BLE member identity uses per-device signatures

BLE v5 uses a P-256 ECDSA signature over the member ID, event code, join time, flags, and monotonic sequence. Each event creates an ephemeral signing keypair; the private key stays on that device. Public keys are registered over the SAS-confirmed Nearby connection. The host accepts a guest key only when its peer ID matches the authenticated endpoint, then distributes the registered key to the other guests. In-session radar updates require that registered key and a valid signature; the V4 group-HMAC packet is not accepted for member presence. Pre-join discovery reads only the unsigned locator hint. Host controls and closure remain on the authenticated Nearby endpoint. See [advertisement encoding and verification](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt) and [key registration authorization](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Nearby transport requires SAS confirmation

Both endpoints display Nearby's shared authentication digits and must explicitly confirm before the encrypted control channel is accepted. Users are instructed to compare digits with the intended person. The host then verifies QR-secret proof or holds a code-only join for approval. SAS prompts expire after two minutes, are capped at eight concurrent requests, and are limited to twelve per minute. See [SAS request handling](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt) and [the confirmation dialog](../app/src/main/java/com/fkurevija/pingring/ui/screens/ConnectionAuthenticationDialog.kt).

### Sender identities are bound to accepted endpoints

The host accepts chat, beacon, and UWB sender claims only when they match the peer ID bound to that confirmed endpoint. Guests accept relayed sender-attributed traffic and host controls only from the authenticated host endpoint. UWB offers are checked against their source endpoint before the host relays them. See [payload authorization](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Graceful departures require an authenticated sender

A guest's leave notice is sent over its accepted host endpoint. The host checks the claimed member ID against that endpoint before relaying the notice; guests accept the relay only from their authenticated host. A notice confirms departure only when delivered. A disconnect, crash, or host outage without the notice remains an unreachable/unknown state.

### Logs and join queues are bounded

Production logs no longer include chat text, beacon titles, raw payload JSON, or UWB STS keys. SAS prompts and pending join approvals are capped at eight; SAS attempts are limited to twelve per minute, and capacity is rechecked when a peer is authorized. See [Nearby request limits and logging](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

## Residual Risks

### Event code remains a public locator

The six-hex code is still broadcast so a discoverable session can be found; it is a locator, not a credential. A typed-code join has no secret proof and always requires host approval. QR scans carry the 256-bit secret. Unauthenticated pre-join discovery hints can still be forged to create false nearby-list rows, but they cannot create an authenticated member or change event state. The browse list is capped at 16 entries. Protect the QR like an invitation, and compare Nearby's digits before confirming.

### BLE replay and extended-radio limits

Receivers reject duplicate or regressing signed sequence numbers for a member. A previously captured, higher-sequence packet that a receiver has never seen can still be replayed once; there is no synchronized trusted clock in the offline protocol to give advertisements a reliable absolute expiry. The normal peer staleness timeout limits how long a single replay remains visible without further packets.

V5 requires extended advertising. Devices whose Bluetooth controller does not support it do not advertise or consume RSSI-based member proximity; the radar reports that limitation instead of falling back to group-authenticated V4. Nearby messaging and other available ranging remain independent. This capability is hardware-dependent even though the app's minimum Android API supports the public extended-advertising API.

### LAN debug transport is not production-secure

The debug-only emulator/device TCP relay does not provide Nearby's encrypted transport; it is intended for a trusted development LAN and must not be used for real events or with untrusted networks. It is excluded from release builds.

### UWB offers remain host-visible

Guest-to-guest UWB offers are relayed through the host. The host can read the STS key; this trust boundary is intentional and remains in [Known limitations](roadmap.md#known-limitations).

## Verification Limits

This was a static code review plus unit/compile validation. A live two-phone SAS comparison, signed-advertisement capture/replay, joined-member impersonation attempt, extended-advertising hardware matrix, join flood, and logcat collection test have not been performed on hardware. Verify those scenarios before treating the mitigations as device-tested.

---

[← Back to README](../README.md)