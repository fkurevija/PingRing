"""Mock ranging source for PingRing's LAN debug transport.

The Android Emulator has no UWB radio and no BLE radio, so over the LAN debug
transport (tools/lan_debug_relay.py) nothing in the app can ever measure how
far the other device is: the radar gets a peer, but no distance, no azimuth,
and therefore no arrow, no hot/cold ring and no proximity haptics to test.
This script closes that gap by joining the same event as a third, invisible
participant and streaming scripted range reports to the other members, which
the app then renders through its real ranging path.

Run it next to the relay and the app:

    python tools/lan_debug_relay.py
    python tools/mock_radar.py <event-id> --pattern approach
    python tools/mock_radar.py <event-id> --pattern orbit --distance 3
    python tools/mock_radar.py <event-id> --peer a1b2c3d4 --name Alice

Every member of the event is driven by default; --peer narrows that down to
particular peer ids. Stop it with Ctrl+C.

Protocol: the same newline-delimited JSON envelopes the relay speaks. The
script joins as a plain member (so it can never end the event) and sends one
"data" envelope per peer per sample, targeted at that peer, carrying a
"mock-range" report as its payload:

    {"type": "data", "eventId": "...", "senderPeerId": "mock-radar",
     "targetPeerId": "a1b2c3d4",
     "payload": "{\"type\":\"mock-range\",\"payload\":\"{\\\"peerId\\\":...}\"}"}

which the app turns into exactly the update a real UWB ranging result would
produce (see DiscoveryRepository.updateMockRanging).
"""
import argparse
import asyncio
import json
import sys

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8942
SCRIPT_PEER_ID = "mock-radar"

# approach: walks between --start and --end metres, in place. This is the one
#   that crosses the < 2 m proximity haptic twice per period.
# orbit:   stands at --distance metres while the bearing sweeps a full circle,
#   to exercise the compass-corrected arrow.
# walk:    both at once - the other device circles you while walking closer.
# static:  a frozen reading, for checking that a steady state looks right.
PATTERNS = ("approach", "orbit", "walk", "static")

# Never report a negative distance, however silly the arguments are.
MIN_DISTANCE_METERS = 0.0

def _triangle(phase, start, end):
    """Ping-pong between `start` and `end`, one leg per half cycle."""
    leg = phase * 2.0
    if leg <= 1.0:
        return start + (end - start) * leg
    return end + (start - end) * (leg - 1.0)


def sample(pattern, phase, start, end, distance, azimuth, elevation):
    """The (distance, azimuth, elevation) report for one point in a cycle.

    `phase` is the position within one cycle, from 0.0 to 1.0 (wrapping), so
    every pattern stays continuous across a cycle boundary. Units are metres
    and degrees, matching the real ranging report.
    """
    phase = phase % 1.0
    if pattern == "static":
        return max(MIN_DISTANCE_METERS, distance), azimuth % 360.0, elevation
    if pattern == "orbit":
        return max(MIN_DISTANCE_METERS, distance), (azimuth + 360.0 * phase) % 360.0, elevation
    if pattern == "walk":
        return (
            max(MIN_DISTANCE_METERS, _triangle(phase, start, end)),
            (azimuth + 360.0 * phase) % 360.0,
            elevation,
        )
    if pattern == "approach":
        return max(MIN_DISTANCE_METERS, _triangle(phase, start, end)), azimuth % 360.0, elevation
    raise ValueError(f"unknown pattern {pattern!r}")


def build_range_envelope(event_id, peer_id, distance, azimuth, elevation, name=None,
                         sender_peer_id=SCRIPT_PEER_ID):
    """The "data" envelope carrying one range report for `peer_id`."""
    report = {
        "peerId": peer_id,
        "distance": round(distance, 3),
        "azimuth": round(azimuth, 2),
        "elevation": round(elevation, 2),
    }
    if name:
        report["name"] = name
    inner = {"type": "mock-range", "payload": json.dumps(report, separators=(",", ":"))}
    return {
        "type": "data",
        "eventId": event_id,
        "senderPeerId": sender_peer_id,
        "targetPeerId": peer_id,
        "payload": json.dumps(inner, separators=(",", ":")),
    }


class MockRadar:
    """Joins one event and keeps sending scripted range reports to its members."""

    def __init__(self, args):
        self.args = args
        self.known_peers = set()
        self.reader = None
        self.writer = None
        self.stopped = asyncio.Event()

    # --- connection -------------------------------------------------------

    async def connect(self):
        """Open the relay connection and join the event. False if unreachable."""
        try:
            reader, writer = await asyncio.open_connection(self.args.host, self.args.port)
        except OSError as exc:
            print(
                f"[mock] cannot reach the relay at {self.args.host}:{self.args.port}: {exc}\n"
                "[mock] is `python tools/lan_debug_relay.py` running?",
                flush=True,
            )
            return False
        self.writer = writer
        self.reader = reader
        await self.send({
            "type": "join",
            "eventId": self.args.event,
            "senderPeerId": SCRIPT_PEER_ID,
            "role": "member",
        })
        print(
            f"[mock] joined event {self.args.event} at {self.args.host}:{self.args.port} "
            f"as {SCRIPT_PEER_ID}",
            flush=True,
        )
        return True

    async def send(self, envelope):
        line = (json.dumps(envelope, separators=(",", ":")) + "\n").encode("utf-8")
        try:
            self.writer.write(line)
            await self.writer.drain()
        except Exception as exc:
            # The relay went away, most often because the host ended the event.
            print(f"[mock] connection to the relay lost: {exc}", flush=True)
            self.stopped.set()

    def stop(self):
        """Ask both loops to finish.

        The socket is closed as well, because the read loop is parked on
        readline() and would otherwise not notice the stop until the relay
        happened to say something.
        """
        self.stopped.set()
        if self.writer is not None:
            try:
                self.writer.close()
            except Exception:
                pass

    async def close(self):
        if self.writer is None:
            return
        try:
            self.writer.close()
            await self.writer.wait_closed()
        except Exception:
            pass

    # --- members ----------------------------------------------------------

    def targets(self):
        if self.args.peer:
            return list(self.args.peer)
        return sorted(peer for peer in self.known_peers if peer != SCRIPT_PEER_ID)

    async def handle_incoming(self, envelope):
        envelope_type = envelope.get("type")
        peer_id = envelope.get("senderPeerId")
        if envelope_type == "peer-joined" and peer_id:
            self.known_peers.add(peer_id)
            print(f"[mock] peer {peer_id} joined the event", flush=True)
        elif envelope_type == "peer-left" and peer_id:
            self.known_peers.discard(peer_id)
            print(f"[mock] peer {peer_id} left the event", flush=True)
        elif envelope_type == "event-closed":
            print(f"[mock] event {self.args.event} was closed by {peer_id}; stopping", flush=True)
            self.stopped.set()

    async def read_loop(self):
        while not self.stopped.is_set():
            line = await self.reader.readline()
            if not line:
                # EOF: either the relay went away, or stop() closed the socket
                # underneath us to wake this loop up.
                if not self.stopped.is_set():
                    print("[mock] the relay closed the connection", flush=True)
                self.stopped.set()
                return
            try:
                envelope = json.loads(line.decode("utf-8").strip())
            except json.JSONDecodeError:
                continue
            await self.handle_incoming(envelope)


    # --- streaming --------------------------------------------------------

    async def stream_loop(self):
        loop = asyncio.get_running_loop()
        started = loop.time()
        interval = 1.0 / self.args.rate
        last_printed = 0.0
        announced_waiting = False
        while not self.stopped.is_set():
            targets = self.targets()
            if not targets:
                if not self.args.peer and not announced_waiting:
                    print("[mock] waiting for members to join the event", flush=True)
                    announced_waiting = True
                await asyncio.sleep(interval)
                continue
            announced_waiting = False

            phase = ((loop.time() - started) / self.args.period) % 1.0
            readings = []
            for index, peer_id in enumerate(targets):
                # --spread gives each driven peer its own slice of the circle
                # so their pins do not sit on top of each other.
                base_azimuth = self.args.azimuth
                if self.args.spread:
                    base_azimuth += 360.0 * index / len(targets)
                distance, azimuth, elevation = sample(
                    self.args.pattern,
                    phase,
                    self.args.start,
                    self.args.end,
                    self.args.distance,
                    base_azimuth,
                    self.args.elevation,
                )
                readings.append((peer_id, distance, azimuth))
                await self.send(build_range_envelope(
                    self.args.event,
                    peer_id,
                    distance,
                    azimuth,
                    elevation,
                    name=self.args.name,
                ))

            now = loop.time()
            if now - last_printed >= 0.5:
                last_printed = now
                summary = "  ".join(
                    f"{peer_id}: {distance:5.2f} m @ {azimuth:5.1f} deg"
                    for peer_id, distance, azimuth in readings
                )
                print(f"[mock] {summary}", flush=True)
            await asyncio.sleep(interval)

    async def run(self):
        """Stream until stopped. Returns False if the relay was unreachable."""
        if not await self.connect():
            return False
        try:
            await asyncio.gather(self.read_loop(), self.stream_loop())
        finally:
            self.stop()
            await self.close()
        return True


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Stream scripted distance/azimuth reports to a PingRing LAN debug event.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("event", help="the event id the app is hosting or joining")
    parser.add_argument("--host", default=DEFAULT_HOST, help="relay host")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="relay port")
    parser.add_argument("--pattern", choices=PATTERNS, default="approach",
                        help="approach = walk in place, orbit = circle at a fixed distance, "
                             "walk = both, static = frozen reading")
    parser.add_argument("--peer", action="append", default=[],
                        help="only drive this peer id (repeatable); default: every member of the event")
    parser.add_argument("--name", default=None,
                        help="display name to give the driven peer(s) on the radar")
    parser.add_argument("--start", type=float, default=10.0, help="distance at the start of a leg (metres)")
    parser.add_argument("--end", type=float, default=0.4, help="distance at the end of a leg (metres)")
    parser.add_argument("--distance", type=float, default=3.0,
                        help="distance for the orbit and static patterns (metres)")
    parser.add_argument("--azimuth", type=float, default=0.0, help="starting bearing (degrees)")
    parser.add_argument("--elevation", type=float, default=0.0, help="elevation (degrees)")
    parser.add_argument("--period", type=float, default=20.0, help="seconds for one full cycle")
    parser.add_argument("--rate", type=float, default=5.0, help="reports sent per second per peer")
    parser.add_argument("--spread", action="store_true",
                        help="give each driven peer its own slice of the circle instead of the same bearing")
    args = parser.parse_args(argv)
    if args.rate <= 0:
        parser.error("--rate must be greater than zero")
    if args.period <= 0:
        parser.error("--period must be greater than zero")
    return args


async def main(argv):
    args = parse_args(argv)
    targets = ", ".join(args.peer) if args.peer else "every member"
    print(
        f"[mock] driving event {args.event} ({targets}) with the '{args.pattern}' pattern: "
        f"one cycle every {args.period}s, {args.rate} reports/s. Press Ctrl+C to stop.",
        flush=True,
    )
    return 0 if await MockRadar(args).run() else 1


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main(sys.argv[1:])))
    except KeyboardInterrupt:
        print("[mock] stopped", flush=True)
        sys.exit(0)



