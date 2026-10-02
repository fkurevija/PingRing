# PingRing Security Review

[← Back to README](../README.md) · [Roadmap](roadmap.md) · [Architecture & Offline Operation](architecture.md)

**Reviewed:** 2026-10-02
**Scope:** BLE advertisement contents and handling; Nearby Connections admission, peer identity, and payload routing; sensitive diagnostic logging; join-request abuse.

## Assessment

This was a static source review of the production Android paths. No packet capture, active radio spoofing, or two-device man-in-the-middle exercise was possible in this environment, so exploitability has not been demonstrated on hardware. The threat model is a person within BLE/Nearby radio range who can observe and transmit advertisements or advertise a Nearby service. It does not assume control of either phone's OS.

Nearby Connections encrypts established payload transport, but encryption alone does not authenticate which app peer is on the other end. Google's [connection-management guidance](https://developers.google.com/nearby/connections/android/manage-connections#authenticate_the_connection) recommends comparing the shared authentication digits. PingRing now requires an explicit user confirmation before accepting the transport connection.

## Implemented Mitigations

### BLE member identity uses per-device signatures

V5 presence additionally requires live control of the registered key. The receiver reuses the mesh's 32-byte random nonce exchange over that member's Nearby link. The ECDSA response binds the Ring, both peer IDs, and the fresh challenge; responses expire after fifteen seconds and cannot be reused. Successful verification permits BLE updates for ten seconds, measured with Android's monotonic clock. Advertisements do not extend this window; disconnect, a different registered key, or Ring teardown invalidates it. Without proof, the scan path does not advance the accepted advertisement sequence or refresh RSSI/radar presence. See [live-proof policy](../app/src/main/java/com/fkurevija/pingring/data/LiveKeyControlTracker.kt).

BLE v5 uses a P-256 ECDSA signature over the member ID, event code, join time, flags, and monotonic sequence. Each event creates an ephemeral signing keypair; the private key stays on that device. Public keys are registered over the SAS-confirmed Nearby connection. The host accepts a guest key only when its peer ID matches the authenticated endpoint, then distributes the registered key to the other guests. In-session radar updates require that registered key and a valid signature; the V4 group-HMAC packet is not accepted for member presence. Pre-join discovery reads only the unsigned locator hint. Host controls and closure remain on the authenticated Nearby endpoint. See [advertisement encoding and verification](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt) and [key registration authorization](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Nearby transport requires SAS confirmation

Both endpoints display Nearby's shared authentication digits and must explicitly confirm before the encrypted control channel is accepted. Users are instructed to compare digits with the intended person. The host then verifies QR-secret proof or holds a code-only join for approval. SAS prompts expire after two minutes, are capped at eight concurrent requests, and are limited to twelve per minute. See [SAS request handling](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt) and [the confirmation dialog](../app/src/main/java/com/fkurevija/pingring/ui/screens/ConnectionAuthenticationDialog.kt).

### Mesh links and forwarded origins are authenticated

New members still require direct host admission. Links between already-admitted members prove current control of the registered per-event key with a fresh, event- and peer-bound nonce; this does not replace the join SAS flow. Forwardable envelopes bind event, type, origin, message ID, target, and payload under the origin's P-256 signature. The receiver checks the registered origin key and the authenticated immediate relay, suppresses duplicate IDs, and enforces a seven-hop bound. The bounded queue holds at most 512 envelopes / 1 MiB in memory and clears when the Ring ends. It is best effort and is lost if the process dies.

Signed five-second mesh heartbeats relay member availability only. They contain no radio measurement; each device's RSSI and UWB range/bearing remain local. UWB offers are readable by every admitted relay on their route, including the host if it lies on that route. See [mesh routing and payload authorization](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Graceful departures require an authenticated sender

A guest's leave notice is sent over its accepted host endpoint. The host checks the claimed member ID against that endpoint before relaying the notice; guests accept the relay only from their authenticated host. A notice confirms departure only when delivered. A disconnect, crash, or host outage without the notice remains an unreachable/unknown state.

### Logs and join queues are bounded

Production logs no longer include chat text, beacon titles, raw payload JSON, or UWB STS keys. SAS prompts and pending join approvals are capped at eight; SAS attempts are limited to twelve per minute, and capacity is rechecked when a peer is authorized. See [Nearby request limits and logging](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Additional ranging metadata is signed and ephemeral

Wi-Fi Aware addresses and opt-in GPS fixes use origin-signed mesh envelopes with member ID and sequence checks. Their payloads are never queued across partitions. Phone-to-phone RTT requests target the focused member's signed, recent Aware address, match the returned MAC address, and reject failed or stale readings. This authenticates the claimed address binding, not the underlying FTM radio exchange; it is not secure distance bounding or proof against a malicious admitted member claiming another address.

GPS sharing defaults off and is enabled by host-authorized Ring controls. Coordinates are quantized to approximately 10 m and broadcast at ten-second intervals. Device accuracy, quantization error, fix age, and uncertainty are checked; stale fixes are discarded. Disabling sharing clears GPS-derived estimates and stops local GPS updates. Ring teardown closes radio sessions and clears retained metadata. GPS positions can still be false or inaccurate; signed claims are not attested physical locations. Admitted members and relays can read shared coordinates and current Aware addresses.

## Residual Risks

### Event code remains a public locator

The six-hex code is still broadcast so a discoverable session can be found; it is a locator, not a credential. A typed-code join has no secret proof and always requires host approval. QR scans carry the 256-bit secret. Unauthenticated pre-join discovery hints can still be forged to create false nearby-list rows, but they cannot create an authenticated member or change event state. The browse list is capped at 16 entries. Protect the QR like an invitation, and compare Nearby's digits before confirming.

### BLE replay and extended-radio limits

Receivers reject duplicate or regressing signed sequence numbers and require a live-key proof within the preceding ten seconds. Captured advertisements alone cannot create or renew that proof. A previously unseen higher-sequence packet can still be replayed during a valid proof window, and a relay-assisted nonce response can prove key control without proving proximity. RSSI remains an estimate of the received signal, not secure measured range; a fresh UWB fix is required for measured range. Existing radar measurements expire through the normal peer-staleness policy rather than disappearing immediately when the proof window closes.

V5 requires extended advertising. Devices whose Bluetooth controller does not support it do not advertise or consume RSSI-based member proximity; the radar reports that limitation instead of falling back to group-authenticated V4. Nearby messaging and other available ranging remain independent. This capability is hardware-dependent even though the app's minimum Android API supports the public extended-advertising API.

### LAN debug transport is not production-secure

The debug-only emulator/device TCP relay does not provide Nearby's encrypted transport; it is intended for a trusted development LAN and must not be used for real events or with untrusted networks. It is excluded from release builds.

### UWB offers remain host-visible

Guest-to-guest UWB offers route over the member mesh. Admitted relays can read the STS key; this trust boundary is intentional and documented under [Known limitations](roadmap.md#known-limitations).

## Verification Limits

This was a static code review plus unit/compile validation. A live two-phone SAS comparison, signed-advertisement capture/replay, joined-member impersonation attempt, extended-advertising hardware matrix, join flood, and logcat collection test have not been performed on hardware. Verify those scenarios before treating the mitigations as device-tested.

---

[← Back to README](../README.md)