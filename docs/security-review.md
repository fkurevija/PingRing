# PingRing Security Review

[← Back to README](../README.md) · [Roadmap](roadmap.md) · [Architecture & Offline Operation](architecture.md)

**Reviewed:** 2026-10-01  
**Scope:** BLE advertisement contents and handling; Nearby Connections admission, peer identity, and payload routing; sensitive diagnostic logging; join-request abuse.

## Assessment

This was a static source review of the production Android paths. No packet capture, active radio spoofing, or two-device man-in-the-middle exercise was possible in this environment, so exploitability has not been demonstrated on hardware. The threat model is a person within BLE/Nearby radio range who can observe and transmit advertisements or advertise a Nearby service. It does not assume control of either phone's OS.

Nearby Connections encrypts established payload transport, but that does not authenticate which app peer is on the other end. Google's [connection-management guidance](https://developers.google.com/nearby/connections/android/manage-connections#authenticate_the_connection) says the token provided during connection initiation should be authenticated and warns that connections accepted without authentication are insecure. PingRing accepts connections without checking that token.

## Findings

### High: Forged BLE advertisements can close a guest's event

BLE service data is a public, unsigned broadcast. A guest accepts an advertisement when its 32-bit `eventHash` matches the current event, then treats any packet with the host and closure flags as authoritative and leaves the event. The packet is not checked against an authenticated host identity, and the full event code is present in advertisements, so a nearby sender can construct the matching hash and closure flags. The same trust issue applies to host policy flags and fabricated member IDs. See [advertisement encoding and decoding](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt) and [scan-result handling](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt).

**Impact:** a nearby attacker can remove guests from an active PingRing or inject false members and host state. The 15-second cleanup limits the lifetime of phantom members but does not protect event closure.

### High: The join code is disclosed in every active member advertisement

`buildServicePayload` writes `currentEvent.id` into the BLE packet for every member, whether or not the host enabled **Discoverable**. The code is generated from the first six hexadecimal characters of a UUID, giving 24 bits, and is also used as the Nearby service ID. A passive observer can read it directly from BLE rather than guessing it. Because accepting new members and automatic admission are defaults, an observer can use the code to request a connection and, if admitted, receive relayed chat, beacons, and history. **Discoverable** currently controls the browse-list flag, not whether the code is broadcast. See [event-code generation](../app/src/main/java/com/fkurevija/pingring/MainActivity.kt) and [BLE payload creation](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt).

### High: Nearby connections are encrypted but not peer-authenticated

`onConnectionInitiated` ignores the authentication token supplied on `ConnectionInfo` and proceeds to automatic admission or host approval based on the incoming flag and local settings. Nearby's encrypted transport therefore protects against passive payload sniffing, but PingRing does not authenticate the endpoint as the intended host/member; an active nearby attacker can impersonate an endpoint or attempt a man-in-the-middle attack. The endpoint name is attacker-supplied metadata, not proof of identity. See [Nearby connection handling](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

### Medium: Accepted members can forge another member's payload identity

The transport callback passes the endpoint ID to envelope dispatch, but chat and beacon routing discard it and trust `senderPeerId` and `senderName` from the payload body. There is no binding between an accepted endpoint and the identity claimed in those fields. A connected member can therefore send chat or beacons under another member's name, and the host may preserve or relay that forged identity. See [payload dispatch](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt), [wire routing](../app/src/main/java/com/fkurevija/pingring/data/RelayCodec.kt), and [message handling](../app/src/main/java/com/fkurevija/pingring/data/DiscoveryRepository.kt).

### Medium: Logs contain chat text and UWB session secrets

Production code logs outgoing chat text, received chat text, beacon titles, and the complete incoming payload JSON. A UWB offer contains the static STS key, so logging its raw JSON exposes that key to anyone who can collect device logs or bug reports. These are local diagnostic disclosures, not passive over-the-air disclosure; Nearby encrypts the transport. See [Nearby logging](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt) and [transport event logging](../app/src/main/java/com/fkurevija/pingring/data/TransportEvents.kt).

### Medium: Join approval has no app-level queue or rate limit

When approval is enabled, each incoming request is appended to the pending list and remains there for up to two minutes. The list has no app-level size or per-source rate limit; pending requests are not reserved against capacity, and approval does not recheck capacity. A nearby sender can flood the host's approval UI, and a burst of approvals can exceed the intended capacity bound. The timeout prevents permanent retention, not request flooding. See [approval queue and admission handling](../app/src/main/java/com/fkurevija/pingring/data/NearbyConnectionsManager.kt).

## Existing Controls and Residual Trust

- Nearby encrypts established payload transport and requires both endpoints to accept, but PingRing does not authenticate the connection token.
- The host can disable new joins or require manual approval. Approval is off by default, and the event code is still broadcast over BLE.
- Connected-member capacity is enforced at connection initiation. BLE-discovered peer records are not authenticated or capped before entering the radar model.
- Guest-to-guest UWB offers are relayed through the host. Their STS key is intentionally visible to the host; this trust boundary is documented separately in [Known limitations](roadmap.md#known-limitations).

## Recommended Hardening

1. Introduce a high-entropy event secret that is not broadcast in BLE. Bind it to the join flow and authenticate both the Nearby endpoint and the BLE advertisement before accepting identity or control flags.
2. Do not apply host closure or policy flags from an unauthenticated advertisement. Keep an unauthenticated BLE sighting as a proximity hint only.
3. Bind every accepted Nearby endpoint to a verified peer ID, reject payloads whose sender identity does not match that binding, and recheck capacity when an approval is granted.
4. Bound pending join requests and rate-limit repeated attempts.
5. Remove message text, full payload JSON, and STS key material from production logs; retain type, byte count, and opaque request IDs for diagnostics.

These changes affect event-code sharing, peer identity, and wire compatibility; they should be designed and tested as a security-hardening change rather than patched independently at individual call sites.

---

[← Back to README](../README.md)