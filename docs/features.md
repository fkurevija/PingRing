# ✨ Features

[← Back to README](../README.md) · [User Guide](user-guide.md) · [Architecture & Offline Operation](architecture.md) · [Building & Testing](building.md) · [Roadmap](roadmap.md)

For step-by-step instructions on using any of this, see the [User Guide](user-guide.md).

## 📡 Mesh & Connectivity
- **One-tap hosting**, no server registration. UWB and high-bandwidth Wi-Fi Direct toggleable on the fly.
- **Admission control**: the Host can stop accepting members at creation or mid-event; connection attempts are then rejected, BLE status flags update, and the Radar's QR button hides until re-enabled. Two more switches: **Discoverable** lists the session on nearby phones' join screens, and **Approve Each Join** holds each new member on the radar until the host allows or denies them. Both are off by default and neither affects joining by code. A request the host never answers is refused after two minutes, so putting the phone down mid-decision cannot pin a guest waiting forever.
- **Bounded capacity**: max **8 participants** (1 Host + 7 Guests), enforced by the Host (`8/8 - Full`). Prevents radio/socket starvation, guaranteeing 30 FPS UWB ranging and instant messaging.
- **Automatic fallback** from sub-metre UWB to BLE RSSI proximity when UWB is unsupported or unavailable. The RSSI path is smoothed over a rolling **2-second window** — the mean is taken in dBm and the distance curve applied once to the result, so one reflected or dropped advert cannot move the readout. A time window rather than a per-sample average, because the advertising rate varies by peer and the app only ever sees the reports that arrive.

## 👤 Identity & Join
- **Custom usernames**, saved locally via DataStore and pre-filled on future sessions. Names are enforced unique within a session, with an alert on collision.
- **Join via QR** (ML Kit, on the Host's on-screen code) or a **6-character PingRing Code** (`e.g. AB12CD`). Code fields force ALL CAPS, usernames use Word Capitalization. The Radar's QR button re-opens the code mid-event for latecomers.
- **Join validation**: a 2.5s timeout verifies the host; on failure the session is cleaned up and the user stays on the join screen to retry.

## 🚨 Beacons & Chat
- **Intent presets**: ✨ Found Something, 📍 Regroup Here, ⚠️ Caution, 🚨 Emergency, ⚡ Custom — with 5 colour palettes (Crimson, Amber, Emerald, Cyan, Purple) and 5 vibration rhythms (SOS/Urgent, Rapid Alert, Steady Pulse, Gentle Pulse, No Vibration). Beacons illuminate radar rings, peer pins and wave animations on receivers.
- **Quick Beacon** (`⚡`): set a configuration with **Set as Quick Beacon** in the beacon screen and the radar's Quick Beacon button sends that exact message, colour and rhythm in a single tap — no screen in between. With none set yet, the tap says so in a toast and opens the beacon screen.
- **Offline group chat** with sender-labelled bubbles. The chat window floats over the radar while typing, then returns to its docked position.
- **Beacons live in the chat transcript**: a beacon that is *running* is pinned to the newest end, where a current thing belongs and the eye already is. When it ends it moves back to where it was raised, ordered by timestamp among the messages around it. Nothing about how a beacon looks changes either way — same entry, same styling, just a different place in the conversation. A peer that disconnects mid-beacon has it ended rather than dropped, since it happened.
- **Host-preserved history**: the Host keeps the transcript for the life of the PingRing and replays it to peers who join or reconnect.
- **Configurable notifications**: toggle chat push app-wide in App Settings and per-PingRing in Event Settings, with independent **sound** and **vibration** controls. Notifications are suppressed while you are already watching the radar with the screen on and unlocked; the device can still vibrate or sound.

## 🧭 Radar, Theming & Hardware Adaptation
- **30 FPS sensor-fusion tracking**: rotation-vector, magnetometer and accelerometer orient peer vectors into world space while concurrent non-blocking UWB sessions stream sub-metre distance, azimuth and elevation.
- **UWB cross-checked against BLE, and marked when obstructed.** UWB's dominant error in a crowd is not noise but **obstruction**: a body between two phones blocks the direct first path, the round trip is measured off a reflection, and the distance reads *too long* — often by metres, always in the same direction. BLE signal strength degrades under that same body, and it is an independent measurement of the same geometry, which is what makes it a usable check. So the RSSI estimate is now kept alive while ranging (it used to be discarded the moment a UWB fix arrived, making the two mutually exclusive) and the radar reports **Cross-checked**, **Someone may be in the way**, or **Distance estimate only** when there is only one source. This costs nothing extra — the BLE adverts are already arriving for discovery — and it matters most at exactly the kind of event the app is for.
- **Proximity haptics** under 2 m; **stale peers expire** from the radar after 15 s. The RSSI distance is the mean of a rolling 2-second window in dBm, with one window per peer, so a single reflected or dropped advert cannot move the readout and eight members cannot average into each other. It is a window in *time* rather than a count of samples on purpose: a sample-weighted average would smooth differently depending on how often a peer happens to advertise, which the app cannot see.
- **Who is tracked, and who decides**: a **Tracking** dropdown names whoever the radar and the single UWB session are following, and changes it — the per-row buttons can also set it, and tapping the tracked row again returns to the automatic choice. With nothing chosen, a guest follows the **host** and the host follows **whoever joined first** — role-based rather than nearest, because "nearest" changes underneath the user as people walk. Every member advertises its arrival time, so "first" means first rather than first-heard: a member who wanders in late and stands nearest is not mistaken for the one who has been there since the start. Turning on **Track Host** locks every guest to the host and disables the picker for them, visibly rather than by hiding it, so the radar is never pointed at somebody with no way to find out why. The host is never locked: the policy asks *members* to follow it, it does not constrain the host. Everyone still *receives* every message regardless of who is tracked.
- **Directional radar**: a needle points at the tracked member and a lit arc on the ring widens as they approach — a sliver when far, the whole ring within arm's reach (1.5 m). Width is spaced logarithmically, so each doubling of distance gets an equal share of the arc. With no UWB bearing the ring stays plain - signal strength only tints it - rather than inventing a direction.
- **UWB sessions survive a transport drop**: if a member walks out of BLE range while still being tracked, the ranging session is held open for 45 s so walking back is instant instead of renegotiating. Ending or leaving the ring discards every retained address.
- **Theming**: a fixed violet brand palette applied throughout, honouring the system's light/dark setting, an adaptive violet-and-white radar icon, and a monochrome `ic_launcher_monochrome.xml` for Android 13+ launchers. **Material You dynamic colour is deliberately not used** — see [Roadmap](roadmap.md#planned-work).
- **Device-aware permissions**: unsupported hardware (`FEATURE_BLUETOOTH`, `FEATURE_WIFI`, `FEATURE_LOCATION`, camera, UWB, vibrator) skips its permission prompt, and its UI is disabled with an explanatory label instead of an ungrantable dialog.

---

[← Back to README](../README.md)


