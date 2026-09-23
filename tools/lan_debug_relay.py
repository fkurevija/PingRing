#!/usr/bin/env python3
"""Debug-only relay server for PingRing's LAN debug transport.

This lets you test PingRing between the Android Emulator (which has no real
Bluetooth/Wi-Fi Direct radios) and a physical device by having both connect
to a small relay running on your development machine instead of talking to
each other directly.

Usage:
    python tools/lan_debug_relay.py [port]

Then in the app, on a DEBUG build, go to Settings > Developer Options and
enable "Use LAN Debug Transport":
  - On the Emulator, leave the host as "10.0.2.2" (always maps to your PC).
  - On a physical device connected via adb, run once from your PC:
        adb reverse tcp:8942 tcp:8942
    then set the host in Settings to "127.0.0.1".

Protocol: newline-delimited JSON objects ("envelopes") of the form
    {"type": "join", "eventId": "...", "senderPeerId": "..."}
    {"type": "peer-joined", "eventId": "...", "senderPeerId": "..."}
    {"type": "data", "eventId": "...", "senderPeerId": "...",
     "targetPeerId": null-or-"...", "payload": "<json-encoded DTO>"}

The relay groups connections by eventId. "data" envelopes with a
targetPeerId are forwarded only to that peer; envelopes with no
targetPeerId are broadcast to every other peer in the same event.
"""
import asyncio
import json
import sys

DEFAULT_PORT = 8942


class Client:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.reader = reader
        self.writer = writer
        self.event_id = None
        self.peer_id = None


# eventId -> list of Client
groups = {}


async def send(client, envelope):
    try:
        client.writer.write((json.dumps(envelope) + "\n").encode("utf-8"))
        await client.writer.drain()
    except Exception as exc:
        print(f"[relay] failed to send to {client.peer_id}: {exc}")


async def handle_client(reader, writer):
    peer = writer.get_extra_info("peername")
    client = Client(reader, writer)
    print(f"[relay] connection opened from {peer}")
    try:
        while True:
            line = await reader.readline()
            if not line:
                break
            try:
                envelope = json.loads(line.decode("utf-8").strip())
            except json.JSONDecodeError:
                continue

            envelope_type = envelope.get("type")
            event_id = envelope.get("eventId")

            if envelope_type == "join" and event_id:
                client.event_id = event_id
                client.peer_id = envelope.get("senderPeerId")
                group = groups.setdefault(event_id, [])
                # Tell every existing member a new peer joined, and tell the
                # newcomer about everyone already present.
                for other in group:
                    await send(other, {
                        "type": "peer-joined",
                        "eventId": event_id,
                        "senderPeerId": client.peer_id,
                    })
                    await send(client, {
                        "type": "peer-joined",
                        "eventId": event_id,
                        "senderPeerId": other.peer_id,
                    })
                group.append(client)
                print(f"[relay] {client.peer_id} joined event {event_id} ({len(group)} member(s))")

            elif envelope_type == "data" and client.event_id:
                group = groups.get(client.event_id, [])
                target_peer_id = envelope.get("targetPeerId")
                for other in group:
                    if other is client:
                        continue
                    if target_peer_id is not None and other.peer_id != target_peer_id:
                        continue
                    await send(other, envelope)
    except Exception as exc:
        print(f"[relay] connection error: {exc}")
    finally:
        if client.event_id and client.event_id in groups:
            groups[client.event_id] = [c for c in groups[client.event_id] if c is not client]
        writer.close()
        print(f"[relay] connection closed from {peer} ({client.peer_id})")


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    server = await asyncio.start_server(handle_client, host="0.0.0.0", port=port)
    print(f"[relay] listening on 0.0.0.0:{port}")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
