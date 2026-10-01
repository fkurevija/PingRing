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
- **UWB is optional.** On a phone without UWB, distances are inferred from BLE signal strength — coarser and noisier, but the radar still works.
- **Capacity is 8 people** (1 host + 7 guests). The host enforces this; a 9th attempt is refused.

---

## Hosting a PingRing

1. Tap **Create a PingRing** on the home screen.
2. Enter a **Username**. It is saved on the device and pre-filled next time. Names must be unique within a session — a collision is refused with an alert.
3. Read the **PingRing Code**: a 6-character code (`e.g. AB12CD`) shown under a QR code. This is what guests use to join.
4. If you want to change anything about the ring — who may join, tracking, UWB, notifications — tap **Event Settings**, or **All Settings** in the top bar. That screen owns every one of them; this one deliberately carries none, so there is only ever one copy of each switch.
5. Tap **Launch Radar**.

Everything in step 4 has a working default, so you can skip it and launch straight away.

### Letting people find you

- **Join by code / QR** always works — the code is the credential, and nothing gates it.
- **Discoverable** (PingRing Settings, off by default) lists your session on nearby phones' **Nearby PingRings** list on their join screen.
- **Approve Each Join** (PingRing Settings, off by default) holds each new member on your radar as a request. A card appears at the top of the radar — *"N devices want to join"* — with **Allow** / **Deny** per device. A request you never answer is refused automatically after **two minutes**.

> Disabling **Accept New Members** mid-event rejects further connection attempts, updates the BLE status flags, and hides the radar's QR button until you re-enable it.

---

## Joining a PingRing

1. Tap **Join a PingRing**.
2. Enter your **Username** and one of:
   - the **6-character PingRing Code** — the field forces capitals;
   - the host's **QR code** — tap **Scan Host QR Code** (disabled with a reason if there is no camera);
   - a session under **Nearby PingRings**, if the host turned on **Discoverable**. Tapping it joins directly — no confirmation step.
3. Tap **Connect**.

Joining verifies the host within **2.5 seconds**. If it fails, the session is cleaned up and you stay on this screen to retry — nothing is left half-joined.

Once the host has approved you (if approval is on), you land on the radar. The host keeps the chat transcript for the life of the PingRing and replays it to you, so you see what was said before you arrived.

---

## The Radar

The radar draws **one person at a time** — the member you are *tracking* — which is what keeps a full 8-member ring readable on a phone screen.

**Reading the display**
- The **needle** points at the tracked member, corrected for compass heading.
- The **lit arc** on the ring widens as they approach — a sliver when far, the whole ring within arm's reach (about 1.5 m). Each doubling of distance gets an equal share of the arc.
- The **distance readout** under the ring is sub-metre on UWB hardware. Without a bearing (no UWB), the ring stays plain and only the signal-strength tint changes — PingRing does not invent a direction.
- A peer that has not been heard from for **15 seconds** is swept off the radar.
- If the tracked member walks out of range while still tracked, ranging is held open for **45 seconds**, so walking back is instant.

### Tracking

Use the **Tracking** dropdown, or the button on a member's row. Both set the same thing.

- With nothing chosen, the choice is automatic: a **guest follows the host**; the **host follows whoever joined first**. It is role-based rather than nearest, because "nearest" changes underneath you as people move. Members announce when they arrived, so "first" is genuine arrival — not whoever the host happened to hear first, which is often the person standing closest.
- Tap a member's row button to track them; the row is marked **TRACKING** and the card is highlighted.
- Tap the tracked row again to go back to automatic.
- If the host turned on **Track Host Only**, your Tracking picker is visible but disabled — the host's decision, not a bug.
- Tracking changes what the radar draws and what UWB ranges. **You still receive every message**, no matter who you are tracking.

### Member list

Each row shows a status dot (beacon colour if they have a beacon running, otherwise green when close and blue when not), the name, proximity and signal strength in dBm, and the distance. The host's radar shows pending join requests above the radar.

### Haptics

Your phone vibrates when the **tracked** member comes within **2 m**. Toggle this off globally in [App Settings](#app-settings) → *Haptic Proximity Alerts*.

### Showing the code again

Tap the **QR icon** in the top bar to reopen the "Scan to Join" dialog — useful when somebody arrives late. The button is hidden while the ring is closed to new members.

---

## Beacons

A beacon is a loud, coloured, vibrating "come here" signal that every member's phone sees on their radar, in their member list, and in the chat.

### Raising one

Tap **New Beacon** on the radar (the button reads **My Beacon** while one of yours is running). In the sheet:

1. **Select Preset** — ✨ Found Something, 📍 Regroup Here, ⚠️ Caution, ⚡ Custom, 🚨 Emergency. Picking one fills in the message, colour and rhythm; edit any of them afterwards.
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

Reached from the host setup screen's **Event Settings** card or its **All Settings** link, or from **Settings** in the radar's top bar (hosts only). This is the only screen that owns the event switches — the host setup screen deliberately carries none, so there is only one copy of each. Changes apply to the live PingRing — press **Save Changes**, or **Cancel** to discard.

**Connectivity & Participation**
- **Accept New Members** — allow nearby users to discover and join. Turning it off rejects further attempts, updates the BLE status flags and hides the QR button until it is back on.
- **Discoverable** — list this session on nearby phones' **Nearby PingRings** list. Off by default. Does not affect joining by code.
- **Approve Each Join** — hold each new member on the radar until you allow or deny them. Off by default. Requests you never answer are refused after two minutes.
- **Track Host Only** — ask every guest's radar to follow you. Guests can see the picker but cannot change it. You are never locked out of tracking whoever you like.
- **Ultra-wideband (UWB)** — sub-metre ranging. When it is off, the row says *why*: this device has no UWB radio; ranging needs permission (tap the row to grant it); or ranging needs Android 12 or newer.
- **High Bandwidth (Wi-Fi)** — always on, nothing to change.
- **Chat Notifications** — notifications for this event.
- **Max Capacity** — 8 members (1 host + 7 guests), enforced by the host.

**Danger Zone**
- **End PingRing** (host) — removes every member and returns them all to the home page. Confirmed with a dialog.
- **Leave PingRing** (guest) — stops you sharing your position. The host and other members are unaffected. Confirmed with a dialog.

---

## App Settings

The gear icon on the home screen, before hosting or joining. These are your device-wide preferences.

- **Appearance → Theme Mode** — *System Default*, *Light Theme* or *Dark Theme*. PingRing uses a fixed violet brand palette in both modes; it deliberately does not follow Material You wallpaper colours, so a ring looks the same on everyone's phone.
- **Alerts & Hardware Haptics**
  - **Haptic Proximity Alerts** — vibrate when a tracked member comes within 2 m. Disabled with a reason on phones with no vibrator.
  - **In-app Notification Banners** — alerts for active beacons.
  - **Chat Push Notifications** — system notifications for incoming messages.
  - **Notification Sound** — tone on incoming messages.
  - **Notification Vibration** — vibration on incoming messages. Disabled on phones with no vibrator.

A **Developer Options** card (LAN Debug Transport) appears in debug builds on a device set up for development. See [Building & Testing](building.md) for how to run with it.

---

## Leaving and ending

- **Guest** — the radar's back arrow (top left) opens *Leave this PingRing?* → **Leave**. You stop sharing your position; the event carries on without you.
- **Host** — the back arrow opens *End this PingRing?* → **End PingRing**. Every member is removed and returned to the home page, and a BLE closure packet is broadcast so their phones react immediately.
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
| "Full or not accepting members" under a nearby PingRing | The host closed the ring or it is at 8/8. |
| "Direction locked · 42°" | A UWB fix, with a bearing. The needle points that way. |
| "BLE proximity mode" | No bearing for this peer — distance is inferred from signal strength, so only the ring tint changes. PingRing does not invent a direction. |
| "N devices want to join" card | **Approve Each Join** is on. Allow or Deny each one; unanswered requests time out after two minutes. |
| Distance shown as "—" in the member list | No fix for that peer yet, or it has gone stale (15 s). |
| A member's dot turning green | They are close (hot). Blue means not close. |
| Distance holds while somebody has walked off | A dropped transport is held open for 45 s. The app cannot tell "out of range" from "left", so it treats the member as unreachable rather than claiming they left. |

---

<div align="center">
  <sub>Found something the app doesn't do yet? It is probably already on the <a href="roadmap.md">Roadmap</a>.</sub>
</div>

[← Back to README](../README.md)



