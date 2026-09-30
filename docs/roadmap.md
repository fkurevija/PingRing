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

- **The BLE payload is 28 of the 31 legacy bytes.** Version 3 spent 8 bytes on the join timestamp, leaving **3**. That is enough for a flag or a small counter and not enough for another epoch-millis Long; going past it means BLE 5 extended advertising and losing devices that only do legacy.
- **No mesh extension.** Adding members adds density, not reach. Every link is host<->guest under `P2P_STAR`, and a guest-to-guest link forms only *after* both already reach the host.
- **The host's tracked-member choice is not broadcast.** A guest resolves the *rule* independently - it defaults to the host - but it cannot learn which member the host itself picked. It would cost another wire-format change, and with **3 bytes** left in the payload the field would have to be far smaller than the join timestamp that just took eight. It only matters with **Track Host** off, where a guest may pick freely anyway.
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

**Home — Host / Join**
- No items yet.

**Connectivity**
- ~~**Implement Wi-Fi Direct, or a host hotspot with clients joining it.**~~ Closed, and not for want of trying. Both halves are blocked by the platform, verified against the API 37 `android.jar` and the play-services-nearby 19.5.0 AAR rather than assumed:
  - **Transport choice.** `Connections.requestConnection` takes no transport parameter. Nearby picks BLE or Wi-Fi Direct itself, and the only transport constants that exist — `WIFI_DIRECT`, `WIFI_HOTSPOT` — live in obfuscated internal classes (`com.google.android.gms.internal.nearby.zzbz`, `com.google.android.gms.nearby.connection.zzam`), not the public API the app calls.
  - **Host hotspot.** `TetheringManager.startTethering` is `@RequiresPermission(TETHER_PRIVILEGED)`, a signature|privileged permission only the system holds. The one route a third-party app has is `WifiManager.startLocalOnlyHotspot`, but three pieces it would need are all hidden: `isLocalOnlyHotspotSupported`, `LocalOnlyHotspotReservation.getWifiInfo`, and `SoftApConfiguration.Builder`'s setters. So the app could start an access point and then be unable to read its SSID — it could never tell a member which network to join. That is not a degraded version of the feature, it is the feature with the one part that matters removed.

  Reaching past the BLE ceiling needs a different radio, not a different API call. `ConnectionMethodPolicy` records the finding so the question is not re-derived.
- ~~**Choose the connection method by capability.**~~ Done, for everything the platform actually allows. `ConnectionMethodPolicy` separates *capability* (hardware and OS permit) from *preference* (the user asked), and reports **why** something was refused rather than silently dropping it. The gap that mattered was the API level: UWB ranging needs Android 12, and a device reporting the feature on an older build could be offered a switch whose permission does not exist. The settings row now says which of the three reasons applies — no radio, permission not granted, or OS too old — and only offers the permission prompt when the permission is genuinely what is missing.
- ~~**Tune antenna use across BLE, UWB and Wi-Fi.**~~ Partly done. `RadioPowerPolicy` now decides the BLE scan mode, the scan pass count and the UWB retention window from the actual load — member count, whether UWB is ranging, whether the phone is on mains — and `DiscoveryRepository` applies it to both call sites. A second scan pass is only added once a ring is crowded enough to need it, the scan drops to the platform default when two other radios are already up off mains, and a retained UWB session is given up sooner under the same conditions. Wi-Fi Direct is still not a lever: Nearby owns that transport and exposes no setting, which is the same finding that removed the High Bandwidth switch. **Still unverified**: no device has measured what this actually saves. The thresholds are reasoned, not measured, and `RadioPowerPolicy.describe` writes the chosen plan to logcat so a device run can be checked after the fact.


- **Wi-Fi RTT ranging — foundation only, and the constraints are harder than they look.** `RangingSourcePolicy` exists and is fully tested: it orders the three sources (UWB → Wi-Fi RTT → BLE RSSI) and reports *why* RTT is unavailable rather than silently dropping it. The capability probe is written. **The ranging itself is not wired up**, and four things block it, none of which can be settled without hardware:
  1. **`startRanging` takes no peer list.** `WifiRttManager.startRanging(RangingRequest, Executor, RangingResultCallback)` has no way to say *which* peers to range — the platform picks RTT-capable peers from its own scan. Correlating a result to a specific member is the whole problem, and PingRing identifies members by ephemeral BLE peer ids with no MAC anywhere.
  2. **The only public correlation handle is `RangingResult.getUsdPeerId()`**, from Wi-Fi Direct Service Discovery. Members would have to *also* publish and subscribe over Wi-Fi P2P — a second discovery path alongside BLE.
  3. **Nearby Connections already owns Wi-Fi Direct.** Whether our own `WifiP2pManager` usage can coexist with it is untested, and may be mutually exclusive on some devices.
  4. **Location services must be on**, and RTT returns nothing when they are off device-wide even with the permission granted. That is a user-visible cost for a privacy-first app.

  **Verify before building further**: on two API 34+ devices with RTT hardware, do USD peer ids actually appear on `RangingResult`, and can a P2P session coexist with Nearby? If either answer is no, this closes like the transport item above.

**Radar — Beacon**
- **Move Emergency off the first preset slot.** Presets render in enum order and `SOS` is first, so Emergency is the default selection when the sheet opens — and it is a one-line move in `BeaconPreset`.

**Radar Settings · App Settings**
- No items yet.

---

[← Back to README](../README.md)


