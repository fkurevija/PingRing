# 📖 User Guide

[← Back to README](../README.md) · [Features](features.md) · [Architecture & Offline Operation](architecture.md) · [Building & Testing](building.md) · [Roadmap](roadmap.md)

How to use PingRing as it exists today — what each screen does and which switch to reach for. For *why* it works the way it does, see [Features](features.md); for the design rationale, [Architecture & Offline Operation](architecture.md).

**Contents**
- [Before you start](#before-you-start)
- [Hosting a PingRing](#hosting-a-pingring)
- [Joining a PingRing](#joining-a-pingring)
- [The Radar](#the-radar)
- [Beacons](#beacons)
- [Chat](#chat)
- [PingRing Settings](#pingring-settings)
- [App Settings](#app-settings)
- [Leaving and ending](#leaving-and-ending)
- [Background operation and notifications](#background-operation-and-notifications)
- [What you might see](#what-you-might-see)

---

## Before you start

- **Android 10 (API 29) or newer**, and Bluetooth on. PingRing works without a network — no SIM, no Wi-Fi, no account.
- **Permissions.** The app asks only for what your phone actually has. Unsupported hardware (no Bluetooth, no Wi-Fi, no camera, no UWB, no vibrator) never gets a permission dialog; the switch is disabled with a label explaining why instead.
- **UWB is optional.** On a phone without UWB, distances use signed BLE RSSI if the Bluetooth controller supports extended advertising. If it does not, BLE proximity is shown as unavailable rather than using spoofable legacy RSSI.
- **Capacity is 8 people** (1 host + 7 guests). The host enforces this; a 9th attempt is refused.

---

## Hosting a PingRing

1. Tap **Create a PingRing** on the home screen.
2. Enter a **Username**. It is saved on the device and pre-filled next time. Names must be unique within a session — a collision is refused with an alert.
3. Share the **QR code** when possible; it contains the 256-bit invitation secret. The 6-character **PingRing Code** (`e.g. AB12CD`) is a public event locator, not a secret.
4. If you want to change who may join, chat colour, tracking, UWB, or notifications, tap **PingRing Settings** in the settings section. The host screen keeps these controls in one place.
5. Tap **Launch Radar**.

Everything in step 4 has a working default, so you can skip it and launch straight away.

### Letting people find you

- **Join by QR** proves possession of the invitation secret. A typed code only locates the event; the host must approve code-only joins.
- **Discoverable** (PingRing Settings, off by default) lists your session on nearby phones' **Nearby PingRings** list on their join screen.
- **Approve Each Join** (PingRing Settings, off by default) holds QR-secret joins for your decision. A card appears at the top of the radar — *"N devices want to join"* — with **Allow** / **Deny** per device. Code-only joins always require approval. Unanswered requests are refused after **two minutes**.
- For every Nearby connection, compare the six digits shown on both phones and confirm only if they match. Reject the request if they differ or you cannot compare them with the intended person.

> Disabling **Accept New Members** mid-event rejects further requests through the authenticated Nearby connection and hides the radar's QR button until you re-enable it.

---

## Joining a PingRing

1. Tap **Join a PingRing**.
2. Enter your **Username** and one of:
  - the **6-character PingRing Code** — the field forces capitals; the host must approve this code-only request;
  - the host's **QR code** — tap **Scan Host QR Code** (disabled with a reason if there is no camera); this includes the invitation secret;
  - a session under **Nearby PingRings**, if the host turned on **Discoverable**. Tapping starts a Nearby connection; compare and confirm the digits shown on both phones.
3. Tap **Connect**.

Before the connection is accepted, compare the six Nearby digits with the person holding the other phone. Reject if they differ. A typed-code join then waits for the host's approval; unanswered requests expire after **two minutes**. If joining fails or is denied, the session is cleaned up and you can retry.

Once the digits match and the host has approved you when required, you land on the radar. The host keeps the chat transcript for the life of the PingRing and replays it to you, so you see what was said before you arrived.

---

## The Radar

The radar draws **one person at a time** — the member you are *tracking* — which is what keeps a full 8-member ring readable on a phone screen.

**Reading the display**
- The **needle** points at the tracked member, corrected for compass heading.
- The **lit arc** on the ring widens as they approach — a sliver when far, the whole ring within arm's reach (about 1.5 m). Each doubling of distance gets an equal share of the arc.
- The **distance readout** under the ring is sub-metre on UWB hardware. Without a fresh UWB bearing, the ring stays plain and only the signed BLE signal-strength tint may change — PingRing does not infer direction from RSSI.
- A member seen only through a mesh relay is labeled **Present via mesh · range unavailable**. The app does not reuse another phone's RSSI or UWB measurement as your own.
- Compatible phones can show **Wi-Fi RTT · distance only** without an access point. Both phones need available Wi-Fi Aware/RTT support; no directional needle is inferred.
- When the host enables **Share GPS location**, members share coordinates rounded to about 10 m every ten seconds. The radar labels GPS distance as an estimate with uncertainty; it does not trigger close-range haptics. Stale or inaccurate fixes are discarded. Coordinate sharing defaults off and exposes coordinates to admitted members and relays.
- A peer that has not been heard from for **15 seconds** is swept off the radar unless its focused UWB session is being retained during a transport gap.
- If the tracked member walks out of transport range, UWB ranging is held open for up to **45 seconds** (or **20 seconds** when radio load is high), so walking back can reconnect instantly.
- After **2 seconds** without a UWB fix, the radar can show a faded **last known direction** for up to **2 minutes**. It includes the age of the measurement; the person may have moved. BLE RSSI alone never supplies a direction.
- A **Weak BLE signal** notice appears if the smoothed tracked-peer signal remains at or below **-85 dBm** for 1.5 seconds; a group summary appears when other members meet the same threshold. It clears above **-80 dBm**. This warns about BLE proximity, not Nearby connection health.

### Tracking

Use the **Tracking** dropdown, or the button on a member's row. Both set the same thing.

- With nothing chosen, the choice is automatic: a **guest follows the host**; the **host follows whoever joined first**. It is role-based rather than nearest, because "nearest" changes underneath you as people move. Members announce when they arrived, so "first" is genuine arrival — not whoever the host happened to hear first, which is often the person standing closest.
- Tap a member's row button to track them; the row is marked **TRACKING** and the card is highlighted.
- Tap the tracked row again to go back to automatic.
- If the host turned on **Track Host Only**, your Tracking picker is visible but disabled — the host's decision, not a bug.
- Tracking changes what the radar draws and what UWB ranges. **You still receive every message**, no matter who you are tracking.

### Connection recovery

If you are a guest and lose the authenticated connection to the host, PingRing waits for the interval configured in **App Settings → Connection Recovery** before asking whether you want to leave. It is enabled by default and set to **2 minutes**; the delay can be set from **1 to 10 minutes**.

- **Leave PingRing** stops your session and returns you Home.
- **Remind me in 2 min** snoozes the prompt; a reconnect cancels it.
- **Silence for this PingRing** suppresses further prompts until you leave or the Ring ends.

The prompt appears only while the radar is in the foreground. PingRing cannot tell whether a disconnected host is out of range or ended the Ring while you were disconnected, so the message reports that the connection is unavailable rather than claiming the Ring ended.

### Member list

Each row shows a status dot (beacon colour if they have a beacon running, otherwise green when close and blue when not), the name, proximity and signal strength in dBm, and the distance. The host's radar shows pending join requests above the radar.

### Haptics

Your phone vibrates when the **tracked** member comes within **2 m**.

### Screen readers

In **App Settings → Accessibility**, turn on **Radar screen-reader summary** to let TalkBack describe the tracked member's distance or signal state, direction when available, and active beacon. It updates with the radar, so TalkBack can describe the same live state shown by the ring. Accessibility features are off by default.

### Showing the code again

Tap the **QR icon** in the top bar to reopen the "Scan to Join" dialog — useful when somebody arrives late. The button is hidden while the ring is closed to new members.

---

## Beacons

A beacon is a loud, coloured, vibrating "come here" signal that every member's phone sees on their radar, in their member list, and in the chat.

### Raising one

Tap **New Beacon** on the radar (the button reads **My Beacon** while one of yours is running). In the sheet:

1. **Select Preset** — ✨ Found Something, 📍 Regroup Here, ⚠️ Caution, 🚨 Emergency, ⚡ Custom. Picking one fills in the message, colour and rhythm; edit any of them afterwards.
2. **Colour** — Red, Amber, Green, Cyan or Purple.
3. **Vibration** — SOS / Urgent, Rapid Alert, Steady Pulse, Gentle Pulse, or No Vibration. **Test Vibration** plays the rhythm on your own phone first.
4. **Beacon Message / Intent** — the text other people will read.
5. **Activate Beacon**.

The button is painted in your chosen colour, with the label drawn for contrast against it.

### While it runs

- A banner appears at the top of your radar — *"Your Beacon is Active: …"* — with **Stop**.
- On receivers' phones it lights up the radar rings and that member's pin, pulses, and appears as a banner.
- In the chat it is pinned to the newest end while running, then moves back to where it was raised, in timestamp order among the surrounding messages.
- Tapping **New Beacon** again lets you edit and **Update Beacon** without stopping it. **Stop Beacon** ends it.

### Quick Beacon

The **⚡ Quick Beacon** button sends one saved configuration in a single tap — no screen in between. To set it: open **New Beacon**, configure it, and tap **Set as Quick Beacon** (the star fills). Tap again — *Remove Quick Beacon* — to clear it. There is one slot, and it survives the app closing.

If none is set, tapping Quick Beacon says so and opens the beacon sheet, rather than doing nothing.

---

## Chat

- Type in the field at the bottom and tap the send button. The chat window floats over the radar while the keyboard is up and docks again when it closes.
- Bubbles are labelled with the sender. Everyone in the ring receives every message.
- If you joined late or reconnected, the host replays the transcript so far.
- **Notifications** for incoming messages: on/off app-wide in [App Settings](#app-settings), and on/off per event in [PingRing Settings](#pingring-settings), with independent **sound** and **vibration**. While you are already watching the radar with the screen on and unlocked, the notification banner is suppressed — but the device can still sound or vibrate.

---

## PingRing Settings

Reached from the host setup screen's **PingRing Settings** card or from **Settings** in the radar's top bar. This is the only screen that owns the event switches — the host setup screen deliberately carries none, so there is only one copy of each. Event switches apply when you press **Save Changes**; chat colour saves as soon as you select it.

**Connectivity & Participation**
- **Accept New Members** — allow new members to join. Turning it off rejects further authenticated Nearby requests and hides the QR button until it is back on.
- **Discoverable** — list this session on nearby phones' **Nearby PingRings** list. Off by default. Does not affect joining by code.
- **Approve Each Join** — hold QR-secret joins on the radar until you allow or deny them. Off by default. Code-only joins always need approval. Unanswered requests are refused after two minutes.
- **Track Host Only** — ask every guest's radar to follow you. Guests can see the picker but cannot change it. You are never locked out of tracking whoever you like.
- **Ultra-wideband (UWB)** — sub-metre ranging. When it is off, the row says *why*: this device has no UWB radio; ranging needs permission (tap the row to grant it); or ranging needs Android 12 or newer.
- **High Bandwidth (Wi-Fi)** — always on, nothing to change.
- **Chat Notifications** — notifications for this event.
- **Chat Bubble Colour** — choose one of four randomized presets or a custom hue for your messages. Hosting or joining a new PingRing gives each member an independent preset palette; reopening settings in the same Ring keeps it unchanged. Each selection saves immediately. **Undo** restores your previous colour; **Use theme colour** clears the event-specific choice.
- **Max Capacity** — 8 members (1 host + 7 guests), enforced by the host.

**Danger Zone**
- **End PingRing** (host) — removes every member and returns them all to the home page. Confirmed with a dialog.
- **Leave PingRing** (guest) — stops you sharing your position. The host and other members are unaffected. Confirmed with a dialog.

---

## App Settings

The gear icon on the home screen, before hosting or joining. These are device-wide preferences, grouped so one control has one clear owner.

- **Language** — choose System Default or one of the available languages: English, Croatian, Spanish, French, Canadian French, German, or Russian. The choice applies to PingRing alone.
- **Appearance** — *Theme Mode* is *System Default*, *Light Theme* or *Dark Theme*. PingRing uses a fixed violet brand palette in both modes; it deliberately does not follow Material You wallpaper colours.
- **Notifications** — **Chat Push Notifications**, **Notification Sound**, and **Notification Vibration** govern device-wide incoming-chat notifications. The PingRing-specific **Chat Notifications** switch remains in [PingRing Settings](#pingring-settings), where it controls only that event.
- **Accessibility** — optional accessibility features. **Radar screen-reader summary** is off by default; enable it to have TalkBack announce the tracked member, distance or signal state, direction, and active beacon.
- **Connection Recovery** — enable or disable the guest leave prompt and set how long host contact must be lost before it appears (1–10 minutes; default 2 minutes).
- **Developer Options** — shown only in debug builds. It contains the LAN Debug Transport and relay host. See [Building & Testing](building.md) for how to use it.

---

## Leaving and ending

- **Guest** — the radar's back arrow (top left) opens *Leave this PingRing?* → **Leave**. You stop sharing your position; the event carries on without you.
- **Host** — the back arrow opens *End this PingRing?* → **End PingRing**. Every connected member is notified over the authenticated Nearby channel and returned to the home page.
- Both routes are also in PingRing Settings → Danger Zone.

---

## Background operation and notifications

While a PingRing is active, discovery, advertising, the mesh transport and UWB run from an Android **foreground service**. You can background the app or swipe its task away — the PingRing keeps running, and the persistent notification reopens the radar.

Android still stops the work after a **force-stop**, a **process kill**, a **reboot**, or if you revoke a runtime permission. Start a new session after any of those.

---

## What you might see

| What you see | What it means |
| --- | --- |
| "Bluetooth is off. Enable it to discover nearby peers." | Nothing can be discovered until Bluetooth is on. Tap the eye icon to recheck. |
| A nearby PingRing appears in the list | BLE discovery is only a hint. Confirm the Nearby digits; the host may still reject the request if the ring is closed or full. |
| "Direction locked · 42°" | A UWB fix, with a bearing. The needle points that way. |
| "BLE proximity mode" | No bearing for this peer — distance is inferred from signal strength, so only the ring tint changes. PingRing does not invent a direction. |
| "Verified BLE proximity is unavailable" | This controller cannot send the extended V5 signed advertisement, so RSSI-based member presence is disabled on this device. |
| "Weak BLE signal" notice | The smoothed signal is near the expected receive limit. Proximity may disappear; move closer if possible. |
| "N devices want to join" card | **Approve Each Join** is on. Allow or Deny each one; unanswered requests time out after two minutes. |
| Distance shown as "—" in the member list | No fix for that peer yet, or it has gone stale. Peers normally expire after 15 s without an advert; the focused peer can remain during its retained UWB window. |
| A member's dot turning green | They are close (hot). Blue means not close. |
| Distance holds while somebody has walked off | A tracked UWB session is retained for up to 45 s, or 20 s under high radio load. The app cannot tell "out of range" from "left", so it treats the member as unreachable rather than claiming they left. |
| A last-known direction appears | It is a frozen UWB bearing from the last fix, shown with its age for at most 2 minutes. The person may have moved; RSSI alone cannot provide direction. |
| "PingRing connection unavailable" appears | As a guest, you have been disconnected from the host longer than your Connection Recovery delay. Leave, snooze for 2 minutes, or silence prompts for this Ring. It does not prove the host ended the Ring. |

---

<div align="center">
  <sub>Found something the app doesn't do yet? It is probably already on the <a href="roadmap.md">Roadmap</a>.</sub>
</div>

[← Back to README](../README.md)



