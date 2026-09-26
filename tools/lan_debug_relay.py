#!/usr/bin/env python3
"""Debug-only relay server for PingRing's LAN debug transport.

This lets you test PingRing between the Android Emulator (which has no real
Bluetooth/Wi-Fi Direct radios) and a physical device by having both connect
to a small relay running on your development machine instead of talking to
each other directly.

Usage:
    python tools/lan_debug_relay.py [port]

Stop it with Ctrl+C (or SIGTERM): the listening socket and every open
connection are closed cleanly, so the port is immediately free to reuse and no
traceback is printed on the way out.

Then in the app, on a DEBUG build, go to Settings > Developer Options and
enable "Use LAN Debug Transport":
  - On the Emulator, leave the host as "10.0.2.2" (always maps to your PC).
  - On a physical device connected via adb, run once from your PC:
        adb reverse tcp:8942 tcp:8942
    then set the host in Settings to "127.0.0.1".

Protocol: newline-delimited JSON objects ("envelopes").

Clients -> relay:
    {"type": "join", "eventId": "...", "senderPeerId": "...",
     "role": "host"|"member"}
    {"type": "data", "eventId": "...", "senderPeerId": "...",
     "targetPeerId": null-or-"...", "payload": "<json-encoded DTO>"}
    {"type": "leave", "eventId": "...", "senderPeerId": "..."}
    {"type": "end", "eventId": "...", "senderPeerId": "..."}
    {"type": "ping", "eventId": "...", "senderPeerId": "..."}

Relay -> clients:
    {"type": "peer-joined", "eventId": "...", "senderPeerId": "..."}
    {"type": "peer-left", "eventId": "...", "senderPeerId": "..."}
    {"type": "event-closed", "eventId": "...", "senderPeerId": "..."}
    {"type": "data", ...} (a forwarded envelope, unchanged)

The relay groups connections by eventId. "data" envelopes with a
targetPeerId are forwarded only to that peer; envelopes with no
targetPeerId are broadcast to every other peer in the same event.

To stand in for a real connection, the relay also mimics how a session
ends rather than just fanning messages out:

  * When a peer leaves - either by sending "leave" or by dropping its
    socket - everyone still in the event gets "peer-left", so the other
    devices remove that peer straight away instead of waiting for their
    own staleness timeout (mirrors Nearby Connections' onDisconnected).

  * When the *host* ends the PingRing the remaining members must be told
    the event is over and be disconnected, exactly as if the host had
    stopped advertising next to them. The app signals this the same way
    it does over the real transport: right before it shuts the event
    down it sends "end", and the relay answers by broadcasting
    "event-closed" and then closing every member's connection.

  * If the host's socket simply disappears (app force-stopped, crash,
    network drop) the relay gives it HOST_RECONNECT_GRACE_SECONDS to
    come back before treating it as an end, so the client's own quick
    reconnect after a blip does not tear the event down for everyone.

"ping" envelopes are heartbeats and are deliberately ignored: if a peer
vanishes without closing its socket, the missing heartbeat does not
end anything - the read side of its connection reports the drop as soon
as the OS notices.
"""
import asyncio
import json
import signal
import sys

DEFAULT_PORT = 8942

# How often the idle shutdown wait wakes up so Python-level signal handlers are
# given a chance to run. Windows event loops can sit in a native wait that
# Ctrl+C cannot interrupt, so a bounded wait keeps shutdown responsive.
SIGNAL_POLL_INTERVAL_SECONDS = 0.25

# Roles a client can claim in its "join" envelope. A join without a role (an
# older build) is treated as a member, which can never end the event.
HOST_ROLE = "host"
MEMBER_ROLE = "member"

# How long a host that vanished without sending "end" is given to reconnect
# before its event is closed for everyone else. Comfortably longer than the
# client's 2s reconnect delay, so a brief blip heals instead of booting.
HOST_RECONNECT_GRACE_SECONDS = 5.0


class Client:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.reader = reader
        self.writer = writer
        self.event_id = None
        self.peer_id = None
        # "host" or "member"; only the host can end the event.
        self.role = MEMBER_ROLE


class Group:
    """Everyone currently connected for one event, plus the host among them."""

    def __init__(self, event_id: str):
        self.event_id = event_id
        self.clients = []
        self.host = None
        # Pending "the host vanished, wait and see" timer, if any.
        self.host_grace_task = None

    def host_peer_id(self):
        return self.host.peer_id if self.host is not None else None


# eventId -> Group
groups = {}

# Every connection that is currently open, whether or not it has joined an event
# yet. Tracked so shutdown() can close each one explicitly instead of leaving
# sockets to be reaped by the interpreter on exit.
active_clients = set()

# Handler coroutines spawned from asyncio's connection callback, kept so
# shutdown() can cancel and await them for an orderly teardown.
client_tasks = set()


async def send(client, envelope):
    try:
        client.writer.write((json.dumps(envelope) + "\n").encode("utf-8"))
        await client.writer.drain()
    except Exception as exc:
        print(f"[relay] failed to send to {client.peer_id}: {exc}", flush=True)


async def close_writer(writer):
    """Best-effort close of a stream writer; never raises."""
    try:
        writer.close()
    except Exception:
        pass
    try:
        await writer.wait_closed()
    except Exception:
        pass


def group_of(client):
    """The [Group] `client` currently belongs to, if any."""
    if not client.event_id:
        return None
    return groups.get(client.event_id)


async def broadcast(group, envelope, exclude=None):
    """Send `envelope` to every client in `group` except `exclude`."""
    for other in group.clients:
        if other is exclude:
            continue
        await send(other, envelope)


async def end_event(group, reason, host_peer_id):
    """Tell everyone the event is over, then disconnect them and forget it.

    Closing the members' sockets is the relay's stand-in for a host walking
    away: their read loop ends and they reconnect only if the app asks them to.
    [host_peer_id] is passed in because the host is already detached from the
    group by the time an event is ended.
    """
    print(f"[relay] event {group.event_id} closed ({reason})", flush=True)
    await broadcast(group, {
        "type": "event-closed",
        "eventId": group.event_id,
        "senderPeerId": host_peer_id,
    })
    members = [c for c in group.clients if c.role != HOST_ROLE]
    if members:
        print(f"[relay] disconnecting {len(members)} member(s) of event {group.event_id}", flush=True)
    for member in members:
        member.event_id = None
        await close_writer(member.writer)
    groups.pop(group.event_id, None)
    group.clients.clear()


async def host_grace_expired(group, host_peer_id):
    """Close an event whose host disappeared without saying goodbye.

    A host that merely blipped reconnects within the grace period and cancels
    this timer; one that was force-stopped does not, so the members are booted
    instead of sitting on a dead event forever.
    """
    await asyncio.sleep(HOST_RECONNECT_GRACE_SECONDS)
    if groups.get(group.event_id) is not group or group.host is not None:
        return
    await end_event(group, f"host {host_peer_id} did not come back", host_peer_id)


async def leave_event(client, reason, ended_by_host=False):
    """Detach `client` from its event and tell the others what happened.

    [ended_by_host] marks a deliberate "the host is ending this PingRing"
    (a "leave"/"end" envelope from the host) rather than a dropped connection,
    so the members are told and booted immediately instead of waiting out
    [HOST_RECONNECT_GRACE_SECONDS].

    Idempotent: a client that already left (or was booted by end_event) is
    ignored, so the disconnect path can always call this unconditionally.
    """
    group = group_of(client)
    if group is None or client not in group.clients:
        client.event_id = None
        return

    was_host = client.role == HOST_ROLE
    event_id = group.event_id
    group.clients.remove(client)
    print(f"[relay] {client.peer_id} left event {event_id} ({reason})", flush=True)

    if was_host:
        group.host = None
        if ended_by_host:
            await end_event(group, reason, client.peer_id)
            client.event_id = None
            return
        # The host vanished mid-session: tell the members right away, then give
        # it HOST_RECONNECT_GRACE_SECONDS to come back before ending the event.
        await broadcast(group, {
            "type": "peer-left",
            "eventId": event_id,
            "senderPeerId": client.peer_id,
        })
        group.host_grace_task = asyncio.ensure_future(host_grace_expired(group, client.peer_id))
    else:
        await broadcast(group, {
            "type": "peer-left",
            "eventId": event_id,
            "senderPeerId": client.peer_id,
        })
        if not group.clients:
            # Never keep empty groups around; event ids are single-use.
            groups.pop(event_id, None)

    client.event_id = None


async def handle_client(client):
    peer = client.writer.get_extra_info("peername")
    print(f"[relay] connection opened from {peer}", flush=True)
    try:
        while True:
            line = await client.reader.readline()
            if not line:
                break
            try:
                envelope = json.loads(line.decode("utf-8").strip())
            except json.JSONDecodeError:
                continue

            envelope_type = envelope.get("type")
            event_id = envelope.get("eventId")

            if envelope_type == "join" and event_id:
                role = envelope.get("role") or MEMBER_ROLE
                if role not in (HOST_ROLE, MEMBER_ROLE):
                    role = MEMBER_ROLE
                # Rejoining (a reconnect, or a switch to another event) counts as
                # leaving the previous one, so groups never hold stale clients.
                await leave_event(client, "rejoined")
                client.event_id = event_id
                client.peer_id = envelope.get("senderPeerId")
                client.role = role
                group = groups.get(event_id)
                if group is None:
                    group = Group(event_id)
                    groups[event_id] = group
                if role == HOST_ROLE and group.host is not None and group.host is not client:
                    print(
                        f"[relay] warning: {client.peer_id} joined event {event_id} as host "
                        f"while {group.host_peer_id()} is already hosting it",
                        flush=True,
                    )
                if group.host_grace_task is not None:
                    # The host that just vanished is back; ignore its absence.
                    group.host_grace_task.cancel()
                    group.host_grace_task = None
                # Tell every existing member a new peer joined, and tell the
                # newcomer about everyone already present.
                for other in group.clients:
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
                group.clients.append(client)
                if role == HOST_ROLE:
                    group.host = client
                print(
                    f"[relay] {client.peer_id} joined event {event_id} as {role} "
                    f"({len(group.clients)} member(s))",
                    flush=True,
                )

            elif envelope_type == "leave" and client.event_id:
                await leave_event(client, "left the event")

            elif envelope_type == "end" and client.event_id:
                await leave_event(
                    client,
                    "host ended the event",
                    ended_by_host=client.role == HOST_ROLE,
                )

            elif envelope_type == "data" and client.event_id:
                group = groups.get(client.event_id)
                if group is None:
                    continue
                target_peer_id = envelope.get("targetPeerId")
                for other in group.clients:
                    if other is client:
                        continue
                    if target_peer_id is not None and other.peer_id != target_peer_id:
                        continue
                    await send(other, envelope)
    except asyncio.CancelledError:
        # Raised by shutdown(); the teardown below still needs to run.
        raise
    except Exception as exc:
        print(f"[relay] connection error: {exc}", flush=True)
    finally:
        active_clients.discard(client)
        # Always route through leave_event so peers are told this client is gone
        # (and, if it was the host, so its event gets ended for the members).
        await leave_event(client, "disconnected")
        await close_writer(client.writer)
        print(f"[relay] connection closed from {peer} ({client.peer_id})", flush=True)


def client_connected(reader, writer):
    """asyncio.start_server callback.

    The connection is registered with the client *before* the handler coroutine
    is scheduled, so shutdown() can always close it even if the handler never
    gets to run, and the handler task is tracked so shutdown() can await it.
    """
    client = Client(reader, writer)
    active_clients.add(client)
    task = asyncio.ensure_future(handle_client(client))
    client_tasks.add(task)
    task.add_done_callback(client_tasks.discard)


async def shutdown(server, serve_task):
    """Stop accepting connections, then close every open one and wait for the
    sockets to be released so the port is free for the next run."""
    print("[relay] shutting down...", flush=True)

    server.close()
    await server.wait_closed()

    # Drop the groups before cancelling handlers: closing down is not the same
    # as ending an event, so no "peer-left"/"event-closed" should be announced.
    for group in list(groups.values()):
        if group.host_grace_task is not None:
            group.host_grace_task.cancel()
    groups.clear()

    pending = [task for task in client_tasks if not task.done()]
    for task in pending:
        task.cancel()
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)

    # Safety net for connections whose handler never started, plus any writer
    # that failed to close during cancellation.
    for client in list(active_clients):
        await close_writer(client.writer)
    active_clients.clear()

    serve_task.cancel()
    for result in await asyncio.gather(serve_task, return_exceptions=True):
        if isinstance(result, Exception):
            print(f"[relay] serve loop stopped with an error: {result}", flush=True)

    print("[relay] stopped, port released", flush=True)


def install_signal_handlers(stop_event):
    """Ask for a clean shutdown on Ctrl+C (SIGINT) and SIGTERM.

    `loop.add_signal_handler()` is not available on Windows' proactor loop, so
    plain `signal.signal()` handlers are installed on every platform; they hand
    control back to the loop thread via `call_soon_threadsafe()`.
    """
    loop = asyncio.get_running_loop()

    def request_stop(signum, _frame):
        print(f"[relay] received signal {signum}, stopping...", flush=True)
        loop.call_soon_threadsafe(stop_event.set)

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, request_stop)
        except (OSError, ValueError):
            # Not on the main thread, or the signal does not exist here; the
            # other signals still bring the relay down.
            pass


async def wait_for_shutdown(stop_event):
    """Block until a shutdown signal arrives, waking up regularly so that
    Python-level signal handlers run even if the loop is stuck in a native
    wait (as happens on Windows)."""
    while not stop_event.is_set():
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=SIGNAL_POLL_INTERVAL_SECONDS)
        except asyncio.TimeoutError:
            continue


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    stop_event = asyncio.Event()
    install_signal_handlers(stop_event)

    # Deliberately no SO_REUSEADDR: on Windows it would let a second relay bind
    # the same port and silently steal traffic, and on POSIX asyncio already
    # enables it. A bind failure simply means another relay is still running.
    try:
        server = await asyncio.start_server(client_connected, host="0.0.0.0", port=port)
    except OSError as exc:
        print(f"[relay] cannot listen on 0.0.0.0:{port}: {exc}", flush=True)
        print("[relay] is another relay already running on that port?", flush=True)
        return 1

    print(f"[relay] listening on 0.0.0.0:{port}", flush=True)
    serve_task = asyncio.ensure_future(server.serve_forever())

    try:
        await wait_for_shutdown(stop_event)
    finally:
        await shutdown(server, serve_task)

    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        # Some consoles deliver Ctrl+C before the loop's signal handler is in
        # place; treat it as an ordinary shutdown instead of a crash.
        print("[relay] interrupted", flush=True)
        sys.exit(0)
