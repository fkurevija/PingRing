# PingRing Security Review

[← Back to README](../README.md) · [Roadmap](roadmap.md) · [Architecture & Offline Operation](architecture.md)

**Reviewed:** 2026-10-02
**Scope:** BLE advertisement contents and handling; Nearby Connections admission, peer identity, and payload routing; sensitive diagnostic logging; join-request abuse.

## Assessment

This was a static source review of the production Android paths. No packet capture, active radio spoofing, or two-device man-in-the-middle exercise was possible in this environment, so exploitability has not been demonstrated on hardware. The threat model is a person within BLE/Nearby radio range who can observe and transmit advertisements or advertise a Nearby service. It does not assume control of either phone's OS.

Nearby Connections encrypts established payload transport, but encryption alone does not authenticate which app peer is on the other end. Google's [connection-management guidance](https://developers.google.com/nearby/connections/android/manage-connections#authenticate_the_connection) recommends comparing the shared authentication digits. PingRing retains that comparison dialog behind the **Verify Nearby connections** App Settings switch, which is off by default. With the switch off, the control channel is accepted automatically; QR-secret verification or host Allow/Deny still gates ordinary traffic. Turning off comparison leaves initial peer impersonation/interception as a residual risk, not a solved authentication problem.

## Implemented Mitigations

### BLE member identity uses per-device signatures

V5 presence additionally requires live control of the registered key. The receiver reuses the mesh's 32-byte random nonce exchange over that member's Nearby link. The ECDSA response binds the Ring, both peer IDs, and the fresh challenge; responses expire after fifteen seconds and cannot be reused. Successful verification permits BLE updates for ten seconds, measured with Android's monotonic clock. Advertisements do not extend this window; disconnect, a different registered key, or Ring teardown invalidates it. Without proof, the scan path does not advance the accepted advertisement sequence or refresh RSSI/radar presence. See [live-proof policy](../app/src/main/java/com/fkurevija/pingring/data/LiveKeyControlTracker.kt).

BLE v5 uses a P-256 ECDSA signature over the member ID, event code, join time, flags, and monotonic sequence. Each event creates an ephemeral signing keypair; the private key stays on that device. Public keys are registered over the admitted Nearby connection. The host accepts a guest key only when its peer ID matches the authorized endpoint, then distributes the registered key to the other guests. In-session radar updates require that registered key and a valid signature; the V4 group-HMAC packet is not accepted for member presence. Pre-join discovery reads only the unsigned locator hint. This preserves registered-key continuity but does not authenticate the intended person during initial admission. See [advertisement encoding and verification](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt) and [key registration authorization](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Nearby digit comparison is optional

With the setting enabled on both devices, both users can explicitly confirm matching Nearby digits before the control channel is accepted. With it disabled, the authentication prompt path is bypassed, but subsequent payload admission remains gated: the host verifies QR-secret proof or holds a code-only join for the username-labelled Allow/Deny overlay used by LAN debug. Missing proof and unanswered approvals expire after 45 seconds for each stage; pending control channels and approvals are bounded, and initial connection attempts are limited to twelve per minute. The preference applies to new Nearby connections; LAN debug does not use this authentication flow. See [join request handling](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Mesh links and forwarded origins are authenticated

New members still require direct host admission. Links between already-admitted members prove current control of the registered per-event key with a fresh, event- and peer-bound nonce; this establishes continuity with the admitted key, not initial human identity. Forwardable envelopes bind event, type, origin, message ID, target, and payload under the origin's P-256 signature. The receiver checks the registered origin key and the authenticated immediate relay, suppresses duplicate IDs, and enforces a seven-hop bound. The bounded queue holds at most 512 envelopes / 1 MiB in memory and clears when the Ring ends. It is best effort and is lost if the process dies.

Signed five-second mesh heartbeats relay member availability and the member's current username. Names are bound to the registered origin and sanitized on receipt, but are not verified human identities or globally reserved names. Heartbeats contain no radio measurement; each device's RSSI and UWB range/bearing remain local. UWB offers are readable by every admitted relay on their route, including the host if it lies on that route. See [mesh routing and payload authorization](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Graceful departures require an authenticated sender

A guest's leave notice is sent over its accepted host endpoint. The host checks the claimed member ID against that endpoint before relaying the notice; guests accept the relay only from their authenticated host. A notice confirms departure only when delivered. A disconnect, crash, or host outage without the notice remains an unreachable/unknown state.

### Logs and join queues are bounded

Production logs no longer include chat text, beacon titles, raw payload JSON, or UWB STS keys. Pending control channels and pending join approvals are capped at eight per respective queue; connection attempts are limited to twelve per minute, and capacity is rechecked when a peer is authorized. See [Nearby request limits and logging](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Additional ranging metadata is signed and ephemeral

Wi-Fi Aware addresses and opt-in GPS fixes use origin-signed mesh envelopes with member ID and sequence checks. Their payloads are never queued across partitions. Phone-to-phone RTT requests target the focused member's signed, recent Aware address, match the returned MAC address, and reject failed or stale readings. This authenticates the claimed address binding, not the underlying FTM radio exchange; it is not secure distance bounding or proof against a malicious admitted member claiming another address.

GPS sharing is unconditional while a Ring is running; it is no longer a host-controlled policy. Coordinates are quantized to approximately 10 m and broadcast at ten-second intervals. Device accuracy, quantization error, fix age, and uncertainty are checked; stale fixes are discarded. Ring teardown closes radio sessions and clears retained metadata. GPS positions can still be false or inaccurate; signed claims are not attested physical locations. Admitted members and relays can read shared coordinates and current Aware addresses.

## Residual Risks

### Event code remains a public locator

The six-hex code is a locator, not a credential. A typed-code join has no secret proof and always requires host approval; a QR scan carries the 256-bit secret and is admitted directly when host policy permits. The code is still advertised over BLE so a guest who was told it can find the host. Unauthenticated BLE/Nearby discovery hints can be forged. If digit comparison is off, a user can connect to an impersonating endpoint; subsequent key signatures do not repair a compromised initial admission. Keep the switch on when this additional identity check is required. Protect the QR like an invitation and approve only intended guests; neither usernames nor role hints prove human identity.

### The host's control over a ring is a single yes/no

There is no per-join approval switch and no way for a host to hold a guest it personally invited. Anyone holding the six-character code can request to join, and the host's standing control is closing the ring to new arrivals. A code-only join still needs explicit Allow and capacity still refuses the ninth member. Disabling the optional Nearby digit comparison weakens initial peer authentication.

### BLE replay and extended-radio limits

Receivers reject duplicate or regressing signed sequence numbers and require a live-key proof within the preceding ten seconds. Captured advertisements alone cannot create or renew that proof. A previously unseen higher-sequence packet can still be replayed during a valid proof window, and a relay-assisted nonce response can prove key control without proving proximity. RSSI remains an estimate of the received signal, not secure measured range; a fresh UWB fix is required for measured range. Existing radar measurements expire through the normal peer-staleness policy rather than disappearing immediately when the proof window closes.

V5 requires extended advertising. Devices whose Bluetooth controller does not support it do not advertise or consume RSSI-based member proximity; the radar reports that limitation instead of falling back to group-authenticated V4. Nearby messaging and other available ranging remain independent. This capability is hardware-dependent even though the app's minimum Android API supports the public extended-advertising API.

### LAN debug transport is not production-secure

The debug-only emulator/device TCP relay does not provide Nearby's encrypted transport; it is intended for a trusted development LAN and must not be used for real events or with untrusted networks. It is excluded from release builds.

### UWB offers remain host-visible

Guest-to-guest UWB offers route over the member mesh. Admitted relays can read the STS key; this trust boundary is intentional and documented under [Known limitations](roadmap.md#known-limitations).

## Verification Limits

The original review used static code review plus unit/compile validation. The digit-comparison flow has been retained and made optional, but both enabled and bypassed flows require hardware verification. Signed-advertisement capture/replay, joined-member impersonation, the extended-advertising hardware matrix, join floods and sanitized-log checks still require dedicated hardware verification.

---

[← Back to README](../README.md)