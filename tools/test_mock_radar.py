"""Tests for the mock ranging source (tools/mock_radar.py).

Run with `python tools/test_mock_radar.py`. Both modules are loaded from this
directory and driven over real sockets, so this needs nothing beyond the
standard library: no relay running, no device attached.

Covers the scripted trajectories themselves, and - the point of the tool - that
the range reports it produces survive the relay and arrive at exactly the peer
they were addressed to, in the shape the app parses (see
DiscoveryRepository.updateMockRanging).
"""
import asyncio
import importlib.util
import json
import pathlib

TOOLS = pathlib.Path(__file__).parent


def load(module_name):
    spec = importlib.util.spec_from_file_location(
        f"{module_name}_under_test", TOOLS / f"{module_name}.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


relay_test = load("test_lan_debug_relay")
mock_radar = load("mock_radar")

# The radius the app's proximity haptic reacts to (RadarScreen), i.e. the
# reading an "approach" script has to be able to produce.
CLOSE_RANGE_METERS = 2.0


def test_trajectories_stay_in_range():
    start, end, distance, azimuth, elevation = 10.0, 0.4, 3.0, 30.0, -5.0
    for pattern in mock_radar.PATTERNS:
        for step in range(241):
            # Spans a cycle and a bit more, so the wrap is covered too.
            phase = step / 240.0
            got_distance, got_azimuth, got_elevation = mock_radar.sample(
                pattern, phase, start, end, distance, azimuth, elevation
            )
            floor = mock_radar.MIN_DISTANCE_METERS
            assert floor <= got_distance <= max(start, end, distance) + 1e-6, (pattern, phase, got_distance)
            assert 0.0 <= got_azimuth < 360.0, (pattern, phase, got_azimuth)
            assert got_elevation == elevation, (pattern, phase, got_elevation)
    print("[test] every pattern stays inside its configured bounds")


def test_approach_walks_from_far_to_point_blank_range():
    closest = min(
        mock_radar.sample("approach", phase, 10.0, 0.4, 3.0, 0.0, 0.0)[0]
        for phase in (i / 200.0 for i in range(200))
    )
    farthest = max(
        mock_radar.sample("approach", phase, 10.0, 0.4, 3.0, 0.0, 0.0)[0]
        for phase in (i / 200.0 for i in range(200))
    )
    assert abs(closest - 0.4) < 1e-6, closest
    assert abs(farthest - 10.0) < 1e-6, farthest
    assert closest < CLOSE_RANGE_METERS, closest
    print("[test] an approach walks the full 10 m -> 0.4 m range, through the close-range haptic threshold")


def test_orbit_sweeps_the_full_circle():
    bearings = {round(mock_radar.sample("orbit", i / 100.0, 10.0, 0.4, 3.0, 0.0, 0.0)[1], 1)
                for i in range(100)}
    assert len(bearings) > 90, len(bearings)
    print(f"[test] an orbit sweeps {len(bearings)} distinct bearings at a fixed distance")


def test_range_envelope_shape():
    envelope = mock_radar.build_range_envelope("E", "M1", 1.25, 90.0, -3.0, name="Alice")
    assert envelope["type"] == "data", envelope
    assert envelope["eventId"] == "E", envelope
    assert envelope["targetPeerId"] == "M1", envelope
    inner = json.loads(envelope["payload"])
    assert inner["type"] == "mock-range", inner
    report = json.loads(inner["payload"])
    assert report == {
        "peerId": "M1",
        "distance": 1.25,
        "azimuth": 90.0,
        "elevation": -3.0,
        "name": "Alice",
    }, report
    # The app tolerates a report without a name, so the field must be optional.
    assert "name" not in json.loads(
        json.loads(mock_radar.build_range_envelope("E", "M1", 1.0, 0.0, 0.0)["payload"])["payload"]
    )
    print("[test] a range report is wrapped in the data envelope the app parses")


async def read_range_report(peer, timeout=5.0):
    """Read envelopes off `peer` until one carries a mock-range report."""
    while True:
        envelope = await peer.next_envelope(timeout)
        if envelope.get("type") != "data":
            continue
        inner = json.loads(envelope["payload"])
        if inner.get("type") == "mock-range":
            return envelope, json.loads(inner["payload"])


async def start_script(event, port, extra_args=()):
    args = mock_radar.parse_args(
        [event, "--port", str(port), "--rate", "25", "--period", "2", *extra_args]
    )
    script = mock_radar.MockRadar(args)
    task = asyncio.ensure_future(script.run())
    return script, task


async def stop_script(script, task):
    script.stop()
    await asyncio.wait_for(task, 5)


async def test_range_reports_reach_the_addressed_peer():
    _, relay_task, port = await relay_test.start_relay(relay_test.NEXT_PORT)
    host = member = None
    script = task = None
    try:
        host = await relay_test.Peer.join(port, "E", "host", role="host")
        member = await relay_test.Peer.join(port, "E", "M1", role="member")
        await host.expect("peer-joined")
        await member.expect("peer-joined")

        script, task = await start_script("E", port, ["--peer", "M1", "--name", "Alice"])
        await member.expect("peer-joined")  # the script itself
        await host.expect("peer-joined")

        envelope, report = await read_range_report(member)
        assert envelope["senderPeerId"] == mock_radar.SCRIPT_PEER_ID, envelope
        assert report["peerId"] == "M1", report
        assert report["name"] == "Alice", report
        assert 0.0 <= report["distance"] <= 10.0, report
        # Reports are addressed, so the host must not see them.
        await host.expect_silence(timeout=1.0)
        print("[test] a scripted range reaches exactly the peer it was addressed to")
    finally:
        if script is not None:
            await stop_script(script, task)
        for peer in (host, member):
            if peer is not None:
                await peer.close()
        await relay_test.stop_relay(relay_task)


async def test_every_member_is_driven_by_default():
    _, relay_task, port = await relay_test.start_relay(relay_test.NEXT_PORT)
    host = member = None
    script = task = None
    try:
        host = await relay_test.Peer.join(port, "E", "host", role="host")
        # The script comes up first and has nobody to drive yet.
        script, task = await start_script("E", port)
        await host.expect("peer-joined")  # the script
        await host.expect_silence(timeout=0.5)  # nothing is streamed while alone

        member = await relay_test.Peer.join(port, "E", "M1", role="member")
        await host.expect("peer-joined")
        await member.expect("peer-joined")
        _, report = await read_range_report(member)
        assert report["peerId"] == "M1", report
        assert "name" not in report, report
        print("[test] with no --peer, every member that joins starts being driven")
    finally:
        if script is not None:
            await stop_script(script, task)
        for peer in (host, member):
            if peer is not None:
                await peer.close()
        await relay_test.stop_relay(relay_task)


async def test_script_gives_up_when_the_relay_is_gone():
    # A port nothing is listening on: the script must explain itself rather
    # than crash with a traceback.
    _, relay_task, port = await relay_test.start_relay(relay_test.NEXT_PORT)
    await relay_test.stop_relay(relay_task)
    try:
        await start_script("E", port)
    except SystemExit as exit_error:
        assert exit_error.code == 1, exit_error.code
        print("[test] no relay running -> a clear message instead of a traceback")
    else:
        raise AssertionError("expected SystemExit when the relay is not listening")


async def test_every_member_is_driven_by_default():
    _, relay_task, port = await relay_test.start_relay(relay_test.NEXT_PORT)
    host = member = None
    script = task = None
    try:
        # The script comes up to an empty event and has nobody to drive yet.
        script, task = await start_script("E", port)
        await asyncio.sleep(0.3)

        host = await relay_test.Peer.join(port, "E", "host", role="host")
        await host.expect("peer-joined")  # the script
        _, host_report = await read_range_report(host)
        assert host_report["peerId"] == "host", host_report

        member = await relay_test.Peer.join(port, "E", "M1", role="member")
        await host.expect("peer-joined")
        await member.expect("peer-joined")
        _, member_report = await read_range_report(member)
        assert member_report["peerId"] == "M1", member_report
        assert "name" not in member_report, member_report
        print("[test] with no --peer, every member that joins starts being driven")
    finally:
        if script is not None:
            await stop_script(script, task)
        for peer in (host, member):
            if peer is not None:
                await peer.close()
        await relay_test.stop_relay(relay_task)


async def test_script_gives_up_when_the_relay_is_gone():
    # A port nothing is listening on: the script must explain itself and report
    # failure rather than crash with a traceback.
    _, relay_task, port = await relay_test.start_relay(relay_test.NEXT_PORT)
    await relay_test.stop_relay(relay_task)
    _, task = await start_script("E", port)
    assert await asyncio.wait_for(task, 5) is False
    print("[test] no relay running -> a clear message instead of a traceback")


async def main():
    test_trajectories_stay_in_range()
    test_approach_walks_from_far_to_point_blank_range()
    test_orbit_sweeps_the_full_circle()
    test_range_envelope_shape()
    await test_range_reports_reach_the_addressed_peer()
    await test_every_member_is_driven_by_default()
    await test_script_gives_up_when_the_relay_is_gone()
    print("[test] ALL CHECKS PASSED")


if __name__ == "__main__":
    asyncio.run(main())

