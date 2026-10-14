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
    python tools/mock_radar.py <event-id> --radio uwb --pattern orbit --distance 3
    python tools/mock_radar.py <event-id> --radio wifi-rtt --pattern approach
    python tools/mock_radar.py <event-id> --radio ble --pattern approach --noise-db 3 --seed 42
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

which the app routes through its BLE RSSI, Wi-Fi RTT distance, or UWB-shaped
radar update path. The measurement is always synthetic; Android radio APIs are
not involved.
"""
import argparse
import asyncio
import json
import math
import random
import sys

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8942
SCRIPT_PEER_ID = "mock-radar"

# Mirrors RssiDistanceEstimator, so a report the script builds is one the app
# could plausibly have received from a real radio. Changing these here without
# changing them there would make the mock quietly wrong rather than obviously
# wrong, which is the failure mode worth avoiding.
RSSI_AT_ONE_METER = -59.0
PATH_LOSS_EXPONENT = 2.2
SENSITIVITY_FLOOR_DBM = -96.0

# The same two bounds RssiDistanceEstimator uses, for the same reason: they are
# what the app could ever report, so a script has to stay inside them to be
# worth running. Below the minimum is a bogus 0.0 m reading; above the maximum
# the estimate is dominated by noise and multi-path and the app clamps it there
# anyway, so a scripted 200 m would render as exactly 40 m and quietly test
# nothing. Past the point where the curve falls under the noise floor the RSSI
# saturates too, so every distance beyond it reports the same dBm - the mock
# would be claiming a peer at 60 m and one at 600 m were indistinguishable.
MIN_DISTANCE_METERS = 0.3
MAX_DISTANCE_METERS = 40.0


def clamp_distance(metres):
    """Confines a scripted distance to what the app could actually report."""
    return max(MIN_DISTANCE_METERS, min(MAX_DISTANCE_METERS, float(metres)))


def rssi_for_distance(metres):
    """The RSSI a real peer at this distance would report, absent noise.

    The inverse of the app's estimator, so a scripted distance and a scripted
    dBm mean the same thing. Clamped at both ends to the same bounds the app
    uses, because an RSSI computed from an out-of-range distance is not a value
    any radio could produce.
    """
    rssi = RSSI_AT_ONE_METER - 10.0 * PATH_LOSS_EXPONENT * math.log10(clamp_distance(metres))
    return max(SENSITIVITY_FLOOR_DBM, min(0.0, rssi))

# approach: walks between --start and --end metres, in place. This is the one
#   that crosses the < 2 m proximity haptic twice per period.
# orbit:   stands at --distance metres while the bearing sweeps a full circle,
#   to exercise the compass-corrected arrow.
# walk:    both at once - the other device circles you while walking closer.
# static:  a frozen reading, for checking that a steady state looks right.
PATTERNS = ("approach", "orbit", "walk", "static")

# The scripted range, confined to what RssiDistanceEstimator could report.
# Arguments outside it are refused at parse time instead - see parse_args - so a
# leg that would be silently flattened never runs.
MIN_DISTANCE_METERS = 0.3
MAX_DISTANCE_METERS = 40.0


def _gaussian(rng):
    """Box-Muller: two uniforms in, one standard normal out.

    Real RSSI noise is roughly symmetric around the true value, and a uniform
    perturbation would not be: it would make far readings as noisy as near ones
    in absolute dBm, or in metres, which is not how a radio misbehaves.

    The generator is passed in rather than drawn from the module, so seeding the
    Radio seeds this too - otherwise --seed would fix the outliers and the
    dropouts but leave the underlying jitter running from the global RNG, and a
    "reproducible" run would still differ.
    """
    u1 = rng.random()
    u2 = rng.random()
    # log(0) is -inf, so keep the first uniform away from exactly zero.
    return math.sqrt(-2.0 * math.log(1.0 - u1)) * math.cos(2.0 * math.pi * u2)


class Radio:
    """The noise a real radio puts on a measurement.

    This is what the script was missing. A scripted trajectory is smooth by
    construction, so it can only ever show that the radar draws a clean signal
    correctly - never that the filters survive a dirty one. The app smooths RSSI
    over a two-second window precisely to absorb the things modelled here:

      noise    - symmetric jitter in dBm. This is what the window exists for.
      outlier  - a single wildly wrong reading. Multi-path does this: a packet
                 reflects off a wall or a body and arrives with far more power
                 than the path deserves, reading as a peer right in your face.
      dropout  - adverts simply not arriving, which is what a peer walking past
                 a crowd looks like from the other end.
      floor    - a peer below the noise floor, which must read as *gone* at once
                 rather than being averaged away by the ten good readings the
                 window still holds.
    """

    def __init__(
        self,
        noise_db=0.0,
        outlier_rate=0.0,
        dropout_rate=0.0,
        range_noise_m=0.0,
        range_outlier_rate=0.0,
        rng=None,
    ):
        self.noise_db = noise_db
        self.outlier_rate = outlier_rate
        self.dropout_rate = dropout_rate
        self.range_noise_m = range_noise_m
        self.range_outlier_rate = range_outlier_rate
        self.rng = rng or random.Random()

    def apply(self, rssi):
        """The reading actually sent this sample, or None if it was lost."""
        if self.dropout_rate > 0.0 and self.rng.random() < self.dropout_rate:
            return None
        value = float(rssi)
        if self.noise_db > 0.0:
            value += _gaussian(self.rng) * self.noise_db
        if self.outlier_rate > 0.0 and self.rng.random() < self.outlier_rate:
            # Wildly *stronger*, not weaker: the failure mode of a reflected
            # packet. A peer reads as closer than it is for one sample, which is
            # the case a naive average gets visibly wrong.
            value += abs(_gaussian(self.rng)) * self.noise_db * 4.0 + 12.0
        return int(round(max(SENSITIVITY_FLOOR_DBM, min(0.0, value))))

    def apply_range(self, distance):
        """Add range uncertainty and the positive bias of an NLOS RTT result."""
        value = float(distance)
        if self.range_noise_m > 0.0:
            value += _gaussian(self.rng) * self.range_noise_m
        if self.range_outlier_rate > 0.0 and self.rng.random() < self.range_outlier_rate:
            value += abs(_gaussian(self.rng)) * self.range_noise_m * 4.0 + 1.0
        return clamp_distance(value)

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
        return clamp_distance(distance), azimuth % 360.0, elevation
    if pattern == "orbit":
        return clamp_distance(distance), (azimuth + 360.0 * phase) % 360.0, elevation
    if pattern == "walk":
        return (
            clamp_distance(_triangle(phase, start, end)),
            (azimuth + 360.0 * phase) % 360.0,
            elevation,
        )
    if pattern == "approach":
        return clamp_distance(_triangle(phase, start, end)), azimuth % 360.0, elevation
    raise ValueError(f"unknown pattern {pattern!r}")


def build_range_envelope(
    event_id,
    peer_id,
    distance,
    azimuth,
    elevation,
    name=None,
    sender_peer_id=SCRIPT_PEER_ID,
    radio=None,
    as_rssi=False,
    source="uwb",
    accuracy_m=0.8,
    bearing_noise_deg=0.0,
):
    """The "data" envelope carrying one range report for `peer_id`.

    Returns None when this sample was dropped, so the caller sends nothing that
    tick - a lost advert, rather than a report of zero or of the last value.
    """
    # None from the radio means this advert was dropped. Absent a radio there is
    # simply no noise configured, which is not the same thing and must not be
    # mistaken for a lost sample.
    if radio is not None:
        noisy = radio.apply(rssi_for_distance(distance))
        if noisy is None:
            return None
    else:
        noisy = None
    # The source determines the report shape: BLE has RSSI only, RTT has range
    # but no bearing, and UWB has range plus bearing.
    report = {"peerId": peer_id}
    if as_rssi:
        # Without a radio there is no reading to send, and sending the clean
        # inverse would silently turn a lossy test into an ideal one.
        if noisy is None:
            return None
        report["rssi"] = noisy
        report["source"] = "BLE_RSSI"
    else:
        measured_distance = radio.apply_range(distance) if radio is not None else distance
        report["distance"] = round(measured_distance, 3)
        if noisy is not None:
            report["rssi"] = noisy
        if source == "wifi-rtt":
            report["source"] = "WIFI_RTT"
            report["accuracyMeters"] = round(accuracy_m, 2)
        else:
            measured_azimuth = azimuth
            if radio is not None and bearing_noise_deg > 0.0:
                measured_azimuth += _gaussian(radio.rng) * bearing_noise_deg
            report["azimuth"] = round(measured_azimuth % 360.0, 2)
            report["elevation"] = round(elevation, 2)
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
        # Seeded so a failing run can be replayed exactly. Noise that changes on
        # every attempt is untestable: you cannot tell a filter that coped from
        # one that got a lucky draw. getattr with defaults because the test
        # harness builds a Namespace by hand rather than through parse_args.
        self.radio = Radio(
            getattr(args, "noise_db", 0.0),
            getattr(args, "outlier_rate", 0.0),
            getattr(args, "dropout_rate", 0.0),
            getattr(args, "range_noise_m", 0.0),
            getattr(args, "range_outliers", 0.0),
            random.Random(getattr(args, "seed", None)),
        )

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
                envelope = build_range_envelope(
                    self.args.event,
                    peer_id,
                    distance,
                    azimuth,
                    elevation,
                    name=self.args.name,
                    radio=self.radio,
                    as_rssi=self.args.radio == "ble",
                    source=self.args.radio,
                    accuracy_m=self.args.accuracy_m,
                    bearing_noise_deg=self.args.bearing_noise_deg,
                )
                # A dropped sample sends nothing at all: the point of --dropout
                # is a peer whose adverts simply fail to arrive, and repeating
                # the last value or sending a zero would both be a lie the
                # filter could hide behind.
                if envelope is not None:
                    await self.send(envelope)

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
    parser.add_argument("--radio", choices=("ble", "wifi-rtt", "uwb"), default=None,
                        help="synthetic measurement model: BLE RSSI, Wi-Fi RTT distance, or UWB distance and bearing")
    parser.add_argument("--rssi-only", action="store_true",
                        help="legacy alias for --radio ble")
    parser.add_argument("--noise-db", type=float, default=0.0,
                        help="standard deviation of symmetric jitter added to each reading, in dBm "
                             "(try 3.0 for a realistic indoor peer)")
    parser.add_argument("--outliers", type=float, default=0.0, metavar="RATE",
                        help="probability (0-1) that a single reading is wildly too strong, as a "
                             "multi-path reflection does. These are what the two-second window exists "
                             "to absorb, so this is the flag that tests it")
    parser.add_argument("--dropout", type=float, default=0.0, metavar="RATE",
                        help="probability (0-1) that an advert never arrives. The reading is dropped "
                             "entirely rather than replaced with a stale value")
    parser.add_argument("--range-noise-m", type=float, default=None,
                        help="standard deviation of range error in metres (default: 0.8 for RTT, 0.05 for UWB)")
    parser.add_argument("--range-outliers", type=float, default=0.0, metavar="RATE",
                        help="probability (0-1) of a positive non-line-of-sight range bias")
    parser.add_argument("--accuracy-m", type=float, default=0.8,
                        help="reported Wi-Fi RTT distance uncertainty in metres")
    parser.add_argument("--bearing-noise-deg", type=float, default=2.0,
                        help="standard deviation of UWB bearing error in degrees")
    parser.add_argument("--seed", type=int, default=None,
                        help="seed the noise, so a failing run replays identically")
    args = parser.parse_args(argv)
    if args.rssi_only and args.radio not in (None, "ble"):
        parser.error("--rssi-only cannot be combined with --radio other than ble")
    args.radio = "ble" if args.rssi_only else (args.radio or "uwb")
    if args.range_noise_m is None:
        args.range_noise_m = 0.8 if args.radio == "wifi-rtt" else 0.05
    if args.rate <= 0:
        parser.error("--rate must be greater than zero")
    if args.period <= 0:
        parser.error("--period must be greater than zero")
    for name, value in (("--noise-db", args.noise_db), ("--outliers", args.outliers),
                        ("--dropout", args.dropout), ("--range-noise-m", args.range_noise_m),
                        ("--accuracy-m", args.accuracy_m), ("--bearing-noise-deg", args.bearing_noise_deg),
                        ("--range-outliers", args.range_outliers)):
        if value < 0:
            parser.error(f"{name} must not be negative")
    # A probability outside 0-1 is always a typo, and a silent clamp would hide
    # it: --outliers 5 reads as plausible but would never fire.
    for name, value in (("--outliers", args.outliers), ("--dropout", args.dropout)):
        if value > 1:
            parser.error(f"{name} is a probability and must be between 0 and 1")
    if args.range_outliers > 1:
        parser.error("--range-outliers is a probability and must be between 0 and 1")
    if args.radio == "wifi-rtt" and args.accuracy_m == 0:
        parser.error("--accuracy-m must be greater than zero for Wi-Fi RTT")
    # Distances outside the app's own range are refused rather than clamped. A
    # silent clamp here is the worst outcome: the run looks like it worked, the
    # radar shows a plausible number, and what was actually tested was the
    # ceiling rather than the trajectory asked for.
    for name, value in (("--start", args.start), ("--end", args.end), ("--distance", args.distance)):
        if not MIN_DISTANCE_METERS <= value <= MAX_DISTANCE_METERS:
            parser.error(
                f"{name} must be between {MIN_DISTANCE_METERS} and {MAX_DISTANCE_METERS} m; "
                f"{value} is beyond what the app could report, and would be clamped to it"
            )
    return args


async def main(argv):
    args = parse_args(argv)
    targets = ", ".join(args.peer) if args.peer else "every member"
    print(
        f"[mock] synthetic {args.radio} reports driving event {args.event} ({targets}) "
        f"with the '{args.pattern}' pattern: "
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
