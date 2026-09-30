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
import random

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


def test_signal_only_reports_carry_dbm_and_no_distance():
    # The whole point of --rssi-only: a report with no distance is the BLE case,
    # which the app runs through its real smoothing filter. If it also carried a
    # distance it would be a ranging fix and the filter would be skipped, so the
    # noise flags would prove nothing.
    radio = mock_radar.Radio(noise_db=3.0, rng=random.Random(1))
    envelope = mock_radar.build_range_envelope(
        "E", "M1", 3.0, 90.0, 0.0, radio=radio, as_rssi=True
    )
    report = json.loads(json.loads(envelope["payload"])["payload"])
    assert "distance" not in report, report
    assert isinstance(report["rssi"], int), report
    # Plausible for 3 m: the app's own curve puts it near -69 dBm.
    assert -80 < report["rssi"] < -60, report
    print("[test] a signal-only report carries dBm and no distance, so the app filters it")


def test_a_default_run_still_sends_no_signal_field():
    # Backwards compatibility: with no noise configured the report is exactly
    # what it was before, so the script stays usable against an older app that
    # does not know the field at all.
    envelope = mock_radar.build_range_envelope("E", "M1", 1.25, 90.0, -3.0, name="Alice")
    report = json.loads(json.loads(envelope["payload"])["payload"])
    assert report == {
        "peerId": "M1", "distance": 1.25, "azimuth": 90.0,
        "elevation": -3.0, "name": "Alice",
    }, report
    print("[test] with no noise configured the report is unchanged from before")


def test_noise_is_symmetric_around_the_true_reading():
    # Symmetric, not uniform: a radio's error is not a flat offset, and a
    # one-sided perturbation would flatter the smoothing filter.
    radio = mock_radar.Radio(noise_db=4.0, rng=random.Random(4))
    clean = mock_radar.rssi_for_distance(3.0)
    readings = [radio.apply(clean) for _ in range(4000)]
    readings = [r for r in readings if r is not None]
    mean = sum(readings) / len(readings)
    assert abs(mean - clean) < 0.4, f"mean {mean} drifted from {clean}"
    assert max(readings) > clean + 8 and min(readings) < clean - 8, (
        "the noise should spread both ways"
    )
    print(f"[test] noise is symmetric: mean {mean:.2f} dBm around a true {clean:.2f}")


def test_outliers_are_rare_and_always_too_strong():
    # A reflected packet arrives with *more* power than the path deserves, so
    # every outlier should read as nearer than the peer. An outlier that reads
    # as farther away is a different failure and would not be modelling anything.
    radio = mock_radar.Radio(noise_db=3.0, outlier_rate=0.05, rng=random.Random(9))
    clean = mock_radar.rssi_for_distance(6.0)
    readings = [r for r in (radio.apply(clean) for _ in range(6000)) if r is not None]
    spikes = [r for r in readings if r > clean + 10]
    assert len(spikes) > 50, f"expected plenty of outliers, saw {len(spikes)}"
    assert all(r > clean for r in spikes)
    print(f"[test] {len(spikes)} of {len(readings)} readings are multi-path spikes, all too strong")


def test_dropout_removes_readings_rather_than_inventing_them():
    # A lost advert must not be replaced with a stale or zero value: the app
    # would then have nothing to test, since the reading it receives looks fine.
    radio = mock_radar.Radio(dropout_rate=0.5, rng=random.Random(3))
    clean = mock_radar.rssi_for_distance(3.0)
    sent = [radio.apply(clean) for _ in range(1000)]
    dropped = sum(1 for r in sent if r is None)
    assert 400 < dropped < 600, f"expected about half dropped, saw {dropped}"
    assert all(r is None or r > -96 for r in sent), "a dropped sample must be None, not a value"
    print(f"[test] dropout removes {dropped}/1000 readings outright")


def test_noise_is_reproducible_from_a_seed():
    # Two runs with the same seed must be identical, or a bug found in the
    # filter cannot be replayed.
    a = mock_radar.Radio(noise_db=3.0, outlier_rate=0.1, rng=random.Random(42))
    b = mock_radar.Radio(noise_db=3.0, outlier_rate=0.1, rng=random.Random(42))
    assert [a.apply(-70) for _ in range(50)] == [b.apply(-70) for _ in range(50)]
    print("[test] the same seed replays the same noise exactly")


def test_impossible_probabilities_are_rejected():
    # --outliers 5 reads as plausible but would never fire, so it is an error
    # rather than something to clamp quietly.
    for argv in (["E", "--outliers", "5"], ["E", "--dropout", "-1"]):
        try:
            mock_radar.parse_args(argv)
            raise AssertionError(f"{argv} should have been rejected")
        except SystemExit as exit_error:
            assert exit_error.code != 0
    print("[test] an out-of-range probability is refused rather than clamped")


async def main():
    test_trajectories_stay_in_range()
    test_approach_walks_from_far_to_point_blank_range()
    test_orbit_sweeps_the_full_circle()
    test_range_envelope_shape()
    test_signal_only_reports_carry_dbm_and_no_distance()
    test_a_default_run_still_sends_no_signal_field()
    test_noise_is_symmetric_around_the_true_reading()
    test_outliers_are_rare_and_always_too_strong()
    test_dropout_removes_readings_rather_than_inventing_them()
    test_noise_is_reproducible_from_a_seed()
    test_impossible_probabilities_are_rejected()
    await test_range_reports_reach_the_addressed_peer()
    await test_every_member_is_driven_by_default()
    await test_script_gives_up_when_the_relay_is_gone()
    print("[test] ALL CHECKS PASSED")


if __name__ == "__main__":
    asyncio.run(main())

