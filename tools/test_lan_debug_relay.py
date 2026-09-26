"""Integration test for the LAN debug relay (tools/lan_debug_relay.py).

Run with `python tools/test_lan_debug_relay.py`. It loads the relay module,
starts it on throwaway localhost ports and drives it over plain sockets, so it
needs nothing beyond the standard library.

Covers the "mock a true connection" behaviour the debug transport relies on: a
peer that disappears is announced to the others immediately, a host that ends
an event boots its members right away, a short host blip does not, and message
forwarding plus clean shutdown still work.
"""
import asyncio
import importlib.util
import json
import pathlib
import signal
import socket
import sys

RELAY_PATH = pathlib.Path(__file__).with_name("lan_debug_relay.py")
NEXT_PORT = 19100


def load_relay():
    spec = importlib.util.spec_from_file_location("relay_under_test", RELAY_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Peer:
    """One test client talking to the relay."""

    def __init__(self, reader, writer):
        self.reader = reader
        self.writer = writer

    @classmethod
    async def join(cls, port, event_id, peer_id, role=None):
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        peer = cls(reader, writer)
        envelope = {"type": "join", "eventId": event_id, "senderPeerId": peer_id}
        if role is not None:
            envelope["role"] = role
        await peer.send(envelope)
        return peer

    async def send(self, envelope):
        self.writer.write((json.dumps(envelope) + "\n").encode("utf-8"))
        await self.writer.drain()

    async def next_envelope(self, timeout=2.0):
        line = await asyncio.wait_for(self.reader.readline(), timeout)
        assert line, "connection was closed while waiting for an envelope"
        return json.loads(line.decode("utf-8"))

    async def expect(self, envelope_type, timeout=2.0):
        envelope = await self.next_envelope(timeout)
        assert envelope.get("type") == envelope_type, f"expected {envelope_type}, got {envelope}"
        return envelope

    async def expect_eof(self, timeout=2.0):
        while True:
            line = await asyncio.wait_for(self.reader.readline(), timeout)
            if not line:
                return

    async def expect_silence(self, timeout=0.6):
        """Asserts nothing arrives within `timeout`."""
        try:
            envelope = await asyncio.wait_for(self.reader.readline(), timeout)
        except asyncio.TimeoutError:
            return
        raise AssertionError(f"expected no envelope, got {envelope!r}")

    async def close(self):
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except Exception:
            pass


async def start_relay(port, grace=5.0):
    global NEXT_PORT
    module = load_relay()
    module.HOST_RECONNECT_GRACE_SECONDS = grace
    sys.argv = ["lan_debug_relay.py", str(port)]
    task = asyncio.ensure_future(module.main())
    for _ in range(100):
        try:
            _, writer = await asyncio.open_connection("127.0.0.1", port)
        except OSError:
            await asyncio.sleep(0.05)
        else:
            writer.close()
            break
    else:
        raise AssertionError(f"relay never listened on {port}")
    NEXT_PORT += 1
    return module, task, port


async def stop_relay(task):
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)


async def test_member_disconnect_notifies_peers():
    module, task, port = await start_relay(NEXT_PORT)
    try:
        host = await Peer.join(port, "E", "host", role="host")
        a = await Peer.join(port, "E", "A", role="member")
        await host.expect("peer-joined")  # A
        await a.expect("peer-joined")  # host

        b = await Peer.join(port, "E", "B", role="member")
        await host.expect("peer-joined")  # B
        await a.expect("peer-joined")  # B
        await b.expect("peer-joined")  # host
        await b.expect("peer-joined")  # A

        await b.close()
        left_at_host = await host.expect("peer-left")
        left_at_a = await a.expect("peer-left")
        assert left_at_host["senderPeerId"] == "B", left_at_host
        assert left_at_a["senderPeerId"] == "B", left_at_a
        print("[test] member disconnect -> peers get peer-left")

        await host.close()
        await a.close()
    finally:
        await stop_relay(task)


async def test_explicit_leave_notifies_peers():
    module, task, port = await start_relay(NEXT_PORT)
    try:
        host = await Peer.join(port, "E", "host", role="host")
        c = await Peer.join(port, "E", "C", role="member")
        await host.expect("peer-joined")
        await c.expect("peer-joined")

        await c.send({"type": "leave", "eventId": "E", "senderPeerId": "C"})
        left = await host.expect("peer-left")
        assert left["senderPeerId"] == "C", left
        print("[test] explicit leave -> peers get peer-left")

        await host.close()
    finally:
        await stop_relay(task)


async def test_host_end_boots_members():
    module, task, port = await start_relay(NEXT_PORT)
    try:
        host = await Peer.join(port, "E", "host", role="host")
        m1 = await Peer.join(port, "E", "M1", role="member")
        m2 = await Peer.join(port, "E", "M2", role="member")
        await host.expect("peer-joined")  # M1
        await host.expect("peer-joined")  # M2
        await m1.expect("peer-joined")  # host
        await m1.expect("peer-joined")  # M2
        await m2.expect("peer-joined")  # host
        await m2.expect("peer-joined")  # M1

        await host.send({"type": "end", "eventId": "E", "senderPeerId": "host"})
        for member in (m1, m2):
            closed = await member.expect("event-closed")
            assert closed["senderPeerId"] == "host", closed
            await member.expect_eof()
        print("[test] host end -> members get event-closed and are disconnected")

        late = await Peer.join(port, "E", "M3", role="member")
        await late.expect_silence()
        print("[test] ended event leaves no stale members behind")

        await late.close()
        await host.close()
    finally:
        await stop_relay(task)


async def test_host_blip_does_not_end_event():
    module, task, port = await start_relay(NEXT_PORT, grace=0.5)
    try:
        host = await Peer.join(port, "E", "host", role="host")
        member = await Peer.join(port, "E", "M1", role="member")
        await host.expect("peer-joined")
        await member.expect("peer-joined")

        await host.close()
        await member.expect("peer-left")  # the host vanished for now

        host = await Peer.join(port, "E", "host", role="host")
        rejoined = await member.expect("peer-joined")
        assert rejoined["senderPeerId"] == "host", rejoined
        await member.expect_silence(timeout=1.0)  # no event-closed
        print("[test] host reconnect within the grace period keeps the event alive")

        await host.close()
        await member.close()
    finally:
        await stop_relay(task)


async def test_host_never_returns_ends_event():
    module, task, port = await start_relay(NEXT_PORT, grace=0.4)
    try:
        host = await Peer.join(port, "E", "host", role="host")
        member = await Peer.join(port, "E", "M1", role="member")
        await host.expect("peer-joined")
        await member.expect("peer-joined")

        await host.close()  # e.g. the host app was force-stopped
        await member.expect("peer-left")
        closed = await member.expect("event-closed", timeout=3.0)
        assert closed["senderPeerId"] == "host", closed
        await member.expect_eof()
        print("[test] host that never returns -> members are booted after the grace period")
    finally:
        await stop_relay(task)
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
async def test_legacy_member_without_role_cannot_end_event():
    module, task, port = await start_relay(NEXT_PORT)
    try:
        host = await Peer.join(port, "E", "host", role="host")
        legacy = await Peer.join(port, "E", "OLD")  # no role field
        await host.expect("peer-joined")
        await legacy.expect("peer-joined")

        await legacy.send({"type": "end", "eventId": "E", "senderPeerId": "OLD"})
        left = await host.expect("peer-left")
        assert left["senderPeerId"] == "OLD", left
        await host.expect_silence(timeout=1.0)  # a member cannot close the event
        print("[test] a role-less/legacy peer is a member and cannot end the event")

        await host.close()
    finally:
        await stop_relay(task)


async def test_forwarding_and_pings_still_work():
    module, task, port = await start_relay(NEXT_PORT)
    try:
        host = await Peer.join(port, "E", "host", role="host")
        m1 = await Peer.join(port, "E", "M1", role="member")
        m2 = await Peer.join(port, "E", "M2", role="member")
        await host.expect("peer-joined")  # M1
        await host.expect("peer-joined")  # M2
        await m1.expect("peer-joined")  # host
        await m1.expect("peer-joined")  # M2
        await m2.expect("peer-joined")  # host
        await m2.expect("peer-joined")  # M1

        await m1.send({"type": "ping", "eventId": "E", "senderPeerId": "M1"})
        await host.expect_silence()

        await m1.send({
            "type": "data",
            "eventId": "E",
            "senderPeerId": "M1",
            "targetPeerId": None,
            "payload": '{"text":"hi"}',
        })
        broadcast = await host.expect("data")
        assert broadcast["payload"] == '{"text":"hi"}', broadcast
        await m2.expect("data")

        await m1.send({
            "type": "data",
            "eventId": "E",
            "senderPeerId": "M1",
            "targetPeerId": "M2",
            "payload": '{"text":"dm"}',
        })
        targeted = await m2.expect("data")
        assert targeted["payload"] == '{"text":"dm"}', targeted
        await host.expect_silence()
        print("[test] pings are ignored, broadcasts and targeted messages still work")

        for peer in (host, m1, m2):
            await peer.close()
    finally:
        await stop_relay(task)


async def test_busy_port_reports_conflict():
    blocker = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    blocker.bind(("0.0.0.0", 0))
    blocker.listen(1)
    port = blocker.getsockname()[1]
    try:
        module = load_relay()
        sys.argv = ["lan_debug_relay.py", str(port)]
        exit_code = await asyncio.wait_for(module.main(), 5)
        assert exit_code == 1, exit_code
        print(f"[test] busy port {port}: reported the conflict instead of crashing")
    finally:
        blocker.close()


async def test_shutdown_releases_port():
    module, task, port = await start_relay(NEXT_PORT)
    host = await Peer.join(port, "E", "host", role="host")
    member = await Peer.join(port, "E", "M1", role="member")
    await host.expect("peer-joined")

    signal.raise_signal(signal.SIGINT)
    exit_code = await asyncio.wait_for(task, 5)
    assert exit_code == 0, exit_code

    await member.expect_eof()
    await host.expect_eof()
    try:
        _, stray = await asyncio.open_connection("127.0.0.1", port)
    except OSError:
        print(f"[test] Ctrl+C still shuts the relay down cleanly and frees {port}")
    else:
        stray.close()
        raise AssertionError(f"relay still listening on {port} after shutdown")


async def main():
    await test_member_disconnect_notifies_peers()
    await test_explicit_leave_notifies_peers()
    await test_host_end_boots_members()
    await test_host_blip_does_not_end_event()
    await test_host_never_returns_ends_event()
    await test_legacy_member_without_role_cannot_end_event()
    await test_forwarding_and_pings_still_work()
    await test_busy_port_reports_conflict()
    await test_shutdown_releases_port()
    print("[test] ALL CHECKS PASSED")


if __name__ == "__main__":
    asyncio.run(main())