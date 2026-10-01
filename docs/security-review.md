# PingRing Security Review

[← Back to README](../README.md) · [Roadmap](roadmap.md) · [Architecture & Offline Operation](architecture.md)

**Reviewed:** 2026-10-01  
**Scope:** BLE advertisement contents and handling; Nearby Connections admission, peer identity, and payload routing; sensitive diagnostic logging; join-request abuse.

## Assessment

This was a static source review of the production Android paths. No packet capture, active radio spoofing, or two-device man-in-the-middle exercise was possible in this environment, so exploitability has not been demonstrated on hardware. The threat model is a person within BLE/Nearby radio range who can observe and transmit advertisements or advertise a Nearby service. It does not assume control of either phone's OS.

Nearby Connections encrypts established payload transport, but encryption alone does not authenticate which app peer is on the other end. Google's [connection-management guidance](https://developers.google.com/nearby/connections/android/manage-connections#authenticate_the_connection) recommends comparing the shared authentication digits. PingRing now requires an explicit user confirmation before accepting the transport connection.

## Implemented Mitigations

### BLE control spoofing no longer changes session state

BLE v4 signs the 28-byte advertisement prefix with HMAC-SHA256 truncated to 88 bits, keyed by the 256-bit invite secret. In-session peers verify the tag before using a peer ID or reading proximity. The invite secret is not present in BLE. BLE host, closure, admission, and ranging flags are no longer applied; only the public discoverability hint is read before joining. Session closure and host controls travel over the authenticated Nearby endpoint. See [advertisement encoding and verification](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt).

### Nearby transport requires SAS confirmation

Both endpoints display Nearby's shared authentication digits and must explicitly confirm before the encrypted control channel is accepted. Users are instructed to compare digits with the intended person. The host then verifies QR-secret proof or holds a code-only join for approval. SAS prompts expire after two minutes, are capped at eight concurrent requests, and are limited to twelve per minute. See [SAS request handling](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt) and [the confirmation dialog](../app/src/main/java/com/fkurevija/pingring/ui/screens/ConnectionAuthenticationDialog.kt).

### Sender identities are bound to accepted endpoints

The host accepts chat, beacon, and UWB sender claims only when they match the peer ID bound to that confirmed endpoint. Guests accept relayed sender-attributed traffic and host controls only from the authenticated host endpoint. UWB offers are checked against their source endpoint before the host relays them. See [payload authorization](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Logs and join queues are bounded

Production logs no longer include chat text, beacon titles, raw payload JSON, or UWB STS keys. SAS prompts and pending join approvals are capped at eight; SAS attempts are limited to twelve per minute, and capacity is rechecked when a peer is authorized. See [Nearby request limits and logging](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

## Residual Risks

### Event code remains a public locator

The six-hex code is still broadcast so a discoverable session can be found; it is a locator, not a credential. A typed-code join has no secret proof and always requires host approval. QR scans carry the 256-bit secret. Unauthenticated pre-join discovery hints can still be forged to create false nearby-list rows, but they cannot create an authenticated member or change event state. The browse list is capped at 16 entries. Protect the QR like an invitation, and compare Nearby's digits before confirming.

### Invited members share the BLE group key

Every admitted member receives the same invite secret so it can verify other members' BLE advertisements. A malicious admitted member can therefore forge another peer's BLE proximity identity; the shared key proves ring membership, not which member sent an advert. It cannot use BLE to close the event or change host policy, and it cannot forge that peer's authenticated Nearby sender identity. Individual BLE identities would need a different key distribution or signature scheme and likely a larger radio format.

### LAN debug transport is not production-secure

The debug-only emulator/device TCP relay does not provide Nearby's encrypted transport; it is intended for a trusted development LAN and must not be used for real events or with untrusted networks. It is excluded from release builds.

### UWB offers remain host-visible

Guest-to-guest UWB offers are relayed through the host. The host can read the STS key; this trust boundary is intentional and remains in [Known limitations](roadmap.md#known-limitations).

## Verification Limits

This was a static code review plus unit/compile validation. A live two-phone SAS comparison, BLE spoof attempt, packet capture, join flood, and logcat collection test have not been performed on hardware. Verify those scenarios before treating the mitigations as device-tested.

---

[← Back to README](../README.md)