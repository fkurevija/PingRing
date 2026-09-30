# 📋 Roadmap & Known Limitations

[← Back to README](../README.md) · [User Guide](user-guide.md) · [Features](features.md) · [Architecture & Privacy](architecture.md) · [Building & Testing](building.md)

Three kinds of item: things that are **built but unverified**, things that are **built and deliberately bounded**, and things that are **not being built**. Nothing here is known to be broken.

## Unverified — needs real hardware

- **Guest→guest UWB ranging.** Relayed through the host, never tested between two UWB phones. Check: does the handshake complete across a relay hop, what happens when two guests focus each other at once, can one controller scope range two peers. Logcat: `Relaying a UWB ranging offer`, `Accepting a UWB ranging offer`.
- **UWB at 8 members.** The focus filter cut the host to one ranging session, which should have removed the old 7-session risk, but the full case is unmeasured — and the "30 FPS ranging" claim in [Features](features.md) is a design target, not a result.
- **BLE reach.** The second scan pass is a reliability gain, not a range multiplier. The ceiling is the radio's own sensitivity, not an app setting: TI's LE 1M reference is -93 dBm, which working back from `RSSI_AT_ONE_METER` is roughly 50 m in free space, 35 m at the `PATH_LOSS_EXPONENT = 2.2` used here, and 14 m at the n=3.0 that crowded indoor venues produce. Treat ~14 m indoors as the expected figure and the exact drop-out as unmeasured. **Verify** by walking two phones apart — clear line-of-sight and through a doorway — until the peer stops appearing.
- **RSSI distance accuracy.** `RSSI_AT_ONE_METER = -59` and `PATH_LOSS_EXPONENT = 2.2` are literature defaults, not measurements on this hardware. -59 dBm at 1 m sits mid-band for a handset (-55 to -65 dBm is the commonly reported range), so it is a reasonable default rather than an arbitrary one — but the accuracy of the readout rests on both constants. One limit no amount of calibration removes: a received advert carries no transmit-power field, so a single fleet-wide constant is necessarily an average across peer models and antennas, and per-peer bias cannot be corrected from the packet. **Verify** by fitting `RSSI = A - 10n·log10(d)` against the UWB distances the app already computes for ranged peers, which needs no extra hardware.
- **LAN relay teardown.** `Ctrl+C` and host-drop handling read as complete, but have only been exercised by `test_lan_debug_relay.py` — never a real dropped connection or killed host.
- **Discovery and join approval.** Both are built and unit tested, and the approval rules are tested as a pure function, but the part that cannot be tested that way is the one that matters: holding a live Nearby Connections request open across a human decision, and whether it survives a host who takes a minute to answer. The host-side timeout now refuses anything unanswered for two minutes (`sweepExpiredJoinRequests`), so a host who walks away mid-decision cannot leave a request pinned forever — but that the refusal reaches a real device in time is still unmeasured. **Verify** by hosting on one phone with **Discoverable** and **Approve Each Join** on, and joining from the other: denial, a disconnect while the request is held, a full session, a closed session, and code joining with discovery off.

## Known limitations

- **The BLE payload is 20 of the 31 legacy bytes.** The 11 spare bytes are named and asserted; going past them means BLE 5 extended advertising and losing devices that only do legacy.
- **No mesh extension.** Adding members adds density, not reach. Every link is host<->guest under `P2P_STAR`, and a guest-to-guest link forms only *after* both already reach the host.
- **The host's tracked-member choice is not broadcast.** A guest resolves the *rule* independently - it defaults to the host - but it cannot learn which member the host itself picked. Adding a field is a wire-format change rather than a UI one, and it would consume most of the payload's 11 spare bytes. It only matters with **Track Host** off, where a guest may pick freely anyway.
- **Relayed UWB offers are not end-to-end.** The STS key travels via the host. Same trust model as relayed chat - a host already brokers everything - but guest-to-guest ranging is not private against the host.
- **A retained member cannot be confirmed gone.** Holding a UWB session open across a transport drop buys an instant reconnect, but the far end stopping to range is indistinguishable from it having left the ring or from the host having ended it - all three produce the same silence. The app therefore reports a silent session as *unreachable* and says so, rather than claiming the member left. A real fix needs both sides to agree on a farewell message, which needs the transport that has just dropped.
- **Release signing is wired but dormant.** `assembleRelease` signs properly when given the four `PINGRING_*` properties, and CI picks it up from a base64 keystore secret — but no key exists yet, so the published APK is still debug-signed. Generating one is a decision to keep the same key forever: Android will not let a properly signed build be replaced by a differently signed one, so losing the key means the app can never be updated again under that identity. See [Signing](building.md#signing).

## Out of scope

- **UWB ranging-initiated discovery** — waking a peer already out of BLE range, so UWB rather than BLE sets the reach ceiling. **Not possible on Android.** `androidx.core.uwb` exposes no discovery API at all: `UwbManager` has only the three session scopes plus availability, and `UwbControllerSessionScope` only accepts a `UwbAddress` you already hold. The spec's discovery exchange happens *inside* an established session and the platform does not expose it, so there is no way to learn a peer's 2-byte address before hearing from them. Reaching beyond BLE needs a different radio, not a different UWB API.
- **Extending range with a coded PHY.** Coded PHY is the only BLE setting that genuinely multiplies range, and the public SDK does not expose it — `ScanSettings` has only `PHY_LE_ALL_SUPPORTED` and `AdvertiseSettings.Builder` has no `setPreferredPhy` (verified against the API 37 `android.jar`). Getting it needs hidden APIs, a deliberate non-goal here. The commonly quoted "~4×" is a free-space figure: 4× range is 12 dB of extra link budget, and at the `PATH_LOSS_EXPONENT = 2.2` used here the same 12 dB is 3.5×. Silicon Labs quotes 4–6 dB of real-world sensitivity gain instead, which is 1.5–1.8× at that exponent. Either way it would not help PingRing: the gain applies to established connections via `HCI_LE_SetPhy`, and PingRing advertises non-connectable and never forms a link. UWB does not widen the reach either — it never transports data, so a peer past the BLE radius is invisible to it too.

## Planned work

Grouped by area. Nothing here is known to be broken: these are things not built, or built and unverified. Work that has shipped is described under [Features](features.md) rather than kept here.

**Global**
- **Define the brand colour and match it to the logo.** *(NEEDS CONFIRMATION)* A violet (`#5C2BD9`) is now the app's fixed primary in both light and dark, and the launcher icon has been brought into the same family — but the colour was chosen by eye from a swatch, not derived from a logo, because no logo exists yet. **Confirm** it is the colour the brand should have before anything is built on it: the tone, the exact hex, whether dark mode should lighten it as it currently does, and whether amber is right for tertiary. If a logo is designed later this should be revisited rather than assumed to match.
- **Per-user chat bubble colour**: 4 presets plus a "custom" option opening a colour wheel. Today every bubble uses the global theme colour. Beacon entries keep their own styling and the radar stays global — neither is affected by the choice.
- **Accessibility audit.** Decorative icons correctly pass `contentDescription = null`, but nothing has checked the app against an accessibility standard, and the radar is drawn on a `Canvas` that no screen reader can describe at all.
- **Multi-language support.** Every string is a hard-coded literal.
- **Help and documentation.** An open question rather than a decision: an offline in-app knowledge base versus spreading help through the app. Needs Iloha's input before it is worth building. The [User Guide](user-guide.md) is the first step towards this and should be extended alongside each new feature.
- **Security review.** Untested and unaudited: whether connections can be eavesdropped, and whether the BLE advert or the Nearby handshake can be leveraged against a device. The app's privacy claims rest on this being answered.
- **Development request portal.** Consider a form or bot that opens — and de-duplicates against existing — GitHub issues, so requests are tracked rather than living in chat.

**Home — Host / Join**
- **The two settings screens should not both own the event switches.** The host setup screen duplicates five of them — Accept New Members, Track Host Only, UWB, Wi-Fi, Chat Notifications — and reaches Discoverable and Approve Each Join only through an **All Settings** link. One screen should own them all.
- **Give peers a join timestamp.** There is none on `RadarPeerUpdate`, so "first to join" has to stand in for "first to be discovered": a member who joins late and is nearest can still be picked as the host's default. It costs eight bytes on the advertisement, so it is a wire-format change rather than a UI one, but until it exists the default is not quite what it claims.

**Connectivity**
- ~~**Implement Wi-Fi Direct, or a host hotspot with clients joining it.**~~ Partly resolved, and the misleading half is gone: Nearby Connections carries its payload channel over Wi-Fi Direct whether or not the app asks, so the **High Bandwidth (Wi-Fi)** switch was removed from both settings screens rather than left toggling a field with no effect. What remains is the part the app cannot supply — an explicit transport choice, or a host-run hotspot for guests that are out of BLE range but still on the same network.
- **Choose the connection method by capability.** UWB defaults from `isUwbSupported()` and Wi-Fi Direct is hard-coded `true`; nothing considers API level or what members support. No dropdown or checkbox list exists, only two switches.
- **Tune antenna use across BLE, UWB and Wi-Fi.** Three radios competing on one battery, with BLE scanning continuously and UWB ranging held open for 45 s after a drop. Nothing has measured what that costs.

**Radar — Beacon**
- **Move Emergency off the first preset slot.** Presets render in enum order and `SOS` is first, so Emergency is the default selection when the sheet opens — and it is a one-line move in `BeaconPreset`.

**Radar Settings · App Settings**
- No items yet.

---

[← Back to README](../README.md)


