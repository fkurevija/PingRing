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
import signal
import sys

DEFAULT_PORT = 8942

# How often the idle shutdown wait wakes up so Python-level signal handlers are
# given a chance to run. Windows event loops can sit in a native wait that
# Ctrl+C cannot interrupt, so a bounded wait keeps shutdown responsive.
SIGNAL_POLL_INTERVAL_SECONDS = 0.25


class Client:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.reader = reader
        self.writer = writer
        self.event_id = None
        self.peer_id = None


# eventId -> list of Client
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
                print(f"[relay] {client.peer_id} joined event {event_id} ({len(group)} member(s))", flush=True)

            elif envelope_type == "data" and client.event_id:
                group = groups.get(client.event_id, [])
                target_peer_id = envelope.get("targetPeerId")
                for other in group:
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
        if client.event_id and client.event_id in groups:
            remaining = [c for c in groups[client.event_id] if c is not client]
            if remaining:
                groups[client.event_id] = remaining
            else:
                # Never keep empty groups around; event ids are single-use.
                del groups[client.event_id]
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
    groups.clear()

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
