# 📋 Roadmap & Known Limitations

[← Back to README](../README.md) · [User Guide](user-guide.md) · [Features](features.md) · [Architecture & Offline Operation](architecture.md) · [Security Review](security-review.md) · [Building & Testing](building.md)

This page separates behavior awaiting **verification**, constraints imposed by **platform or trust boundaries**, and work that can be addressed through **engineering or product decisions**. Items here are not known to be broken.

## Unverified

### Hardware and radio validation

- **Guest→guest UWB ranging.** Relayed through the host, never tested between two UWB phones. Check: does the handshake complete across a relay hop, what happens when two guests focus each other at once, can one controller scope range two peers. Logcat: `Relaying a UWB ranging offer`, `Accepting a UWB ranging offer`.
- **UWB at 8 members.** The focus filter limits each device to one ranging session, but the full 8-member case has not been measured on hardware.
- **BLE reach.** The second scan pass is a reliability gain, not a range multiplier. The ceiling is the radio's own sensitivity, not an app setting: TI's LE 1M reference is -93 dBm, which working back from `RSSI_AT_ONE_METER` is roughly 50 m in free space, 35 m at the `PATH_LOSS_EXPONENT = 2.2` used here, and 14 m at the n=3.0 that crowded indoor venues produce. Treat ~14 m indoors as the expected figure and the exact drop-out as unmeasured. **Verify** by walking two phones apart — clear line-of-sight and through a doorway — until the peer stops appearing.
- **RSSI distance accuracy.** `RSSI_AT_ONE_METER = -59` and `PATH_LOSS_EXPONENT = 2.2` are literature defaults, not measurements on this hardware. -59 dBm at 1 m sits mid-band for a handset (-55 to -65 dBm is the commonly reported range), so it is a reasonable default rather than an arbitrary one — but the accuracy of the readout rests on both constants. One limit no amount of calibration removes: a received advert carries no transmit-power field, so a single fleet-wide constant is necessarily an average across peer models and antennas, and per-peer bias cannot be corrected from the packet. **Verify** by fitting `RSSI = A - 10n·log10(d)` against the UWB distances the app already computes for ranged peers, which needs no extra hardware.
- **LAN relay teardown.** `Ctrl+C` and host-drop handling read as complete, but have only been exercised by `test_lan_debug_relay.py` — never a real dropped connection or killed host.
- **Discovery and join approval.** Both are built and unit tested, and the approval rules are tested as a pure function, but the part that cannot be tested that way is the one that matters: holding a live Nearby Connections request open across a human decision, and whether it survives a host who takes a minute to answer. The host-side timeout now refuses anything unanswered for two minutes (`sweepExpiredJoinRequests`), so a host who walks away mid-decision cannot leave a request pinned forever — but that the refusal reaches a real device in time is still unmeasured. **Verify** by hosting on one phone with **Discoverable** and **Approve Each Join** on, and joining from the other: denial, a disconnect while the request is held, a full session, a closed session, and code joining with discovery off.
- **Radio-power policy.** `RadioPowerPolicy` is covered by unit tests and is applied by discovery, but its scan-pass, scan-mode and UWB-retention thresholds have not been measured for battery or discovery reliability on real devices.
- **V5 extended-advertising hardware matrix.** Verify service-data capacity and signed member-key registration/roster relay on representative controllers, including unsupported devices. Confirm unsupported devices never fall back to V4 RSSI presence.
- **Connection recovery on hardware.** Verify host disconnect detection, the configured delay, reconnect cancellation, foreground-only prompting, snooze, silence-for-this-Ring, and host-ended-while-disconnected wording on devices.
- **Last-known UWB direction on hardware.** Walk and turn with a UWB-ranged peer, then disconnect it. Verify the frozen world bearing does not rotate with the phone, the age updates, a fresh fix clears it, and the marker expires after two minutes. BLE-only peers must not acquire an inferred bearing.
- **Accessibility audit.** The radar has an opt-in spoken summary, but TalkBack behavior, focus order, contrast, and the recovery dialog have not had a device-assisted audit.
- **Security hardening on hardware.** A live SAS comparison, signed-advertisement capture/replay, joined-member impersonation attempt, join flood, and logcat review have not been performed. See the [Security Review](security-review.md#verification-limits).

### Release signing readiness

- **Provision and verify the owner-controlled release key before publishing an upgradeable release.** Restore an encrypted offline backup and compare its certificate fingerprint, configure all four CI secrets, verify CI's signed artifact, and install a higher-version APK over an earlier APK signed with the same key while checking app data survives. Never commit the keystore or passwords. See [Signing](building.md#signing).

## Known limitations

- **BLE member identity requires extended advertising.** V5 carries a 98-byte P-256 signed member advertisement. Devices without extended-advertising hardware do not send or accept RSSI-based member presence; they can still use Nearby and any other supported ranging. Extended advertising is supported by the public Android API at the current minimum SDK, but controller support varies by device.
- **Nearby controls its transport.** The public Connections API has no transport selector, and a third-party app cannot create a joinable host hotspot using the public tethering/local-only-hotspot APIs. Nearby chooses the transport; selecting or replacing it would require a separate transport architecture.
- **The LAN debug relay is plaintext.** It is debug-only and excluded from release builds, but traffic, including invite provisioning, is not encrypted by Nearby. Use it only on a trusted development LAN; see the planned hardening item below if that boundary changes.

## Out of scope

These are verified limits of the public Android APIs or deliberate non-goals, not unverified hardware checks. Do not use hidden APIs to work around them. Revisit only if Android exposes the needed public capability or PingRing adopts a different radio/transport architecture.

- **UWB ranging-initiated discovery** — waking a peer already out of BLE range, so UWB rather than BLE sets the reach ceiling. **Not possible on Android.** `androidx.core.uwb` exposes no discovery API at all: `UwbManager` has only the three session scopes plus availability, and `UwbControllerSessionScope` only accepts a `UwbAddress` you already hold. The spec's discovery exchange happens *inside* an established session and the platform does not expose it, so there is no way to learn a peer's 2-byte address before hearing from them. Reaching beyond BLE needs a different radio, not a different UWB API.
- **Extending range with a coded PHY.** Coded PHY is the only BLE setting that genuinely multiplies range, and the public SDK does not expose it — `ScanSettings` has only `PHY_LE_ALL_SUPPORTED` and `AdvertiseSettings.Builder` has no `setPreferredPhy` (verified against the API 37 `android.jar`). Getting it needs hidden APIs, a deliberate non-goal here. The commonly quoted "~4×" is a free-space figure: 4× range is 12 dB of extra link budget, and at the `PATH_LOSS_EXPONENT = 2.2` used here the same 12 dB is 3.5×. Silicon Labs quotes 4–6 dB of real-world sensitivity gain instead, which is 1.5–1.8× at that exponent. Either way it would not help PingRing: the gain applies to established connections via `HCI_LE_SetPhy`, and PingRing advertises non-connectable and never forms a link. UWB does not widen the reach either — it never transports data, so a peer past the BLE radius is invisible to it too.
- **Replacing Nearby with a selectable/custom transport.** Transport selection is not planned for the current architecture. Revisit only if physical-device validation shows Nearby's transport choice is materially insufficient; a replacement would require a separate radio, protocol, security, and device-compatibility design.

## Planned engineering and product work

- **Add mesh extension only with a concrete range requirement.** The current star topology does not relay presence between guests; a relay architecture would change discovery, routing, liveness, and battery costs.
- **Broadcast the host's selected radar target when needed.** Today each guest chooses independently unless **Track Host Only** is enabled. Add a host-focus control message only if guests need to mirror the host's local choice.
- **Protect guest-to-guest UWB offers end-to-end.** Offers currently pass through the host, which can read the STS key. Define pairwise key establishment and rotation before changing this trust boundary.
- **Improve liveness and replay freshness.** Consider a signed leave/closure protocol and freshness mechanism that works without a shared trusted clock; document replay/partition tradeoffs before implementing a new wire revision.
- **Implement additional ranging sources.** Wi-Fi RTT needs live ranging/discovery, USD attribution and Nearby coexistence validation; GNSS needs a privacy-reviewed peer-fix sharing protocol. Neither is currently active.
- **Decide product direction for the brand and in-app help.** The palette is provisional, and help placement/content has no offline product decision yet.
- **Encrypt the LAN debug relay only if its trust boundary expands.** It remains plaintext and debug-only; do not use it outside a trusted development LAN.

---

[← Back to README](../README.md)


