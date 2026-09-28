#!/usr/bin/env python3
"""Set up the adb reverse tunnels and start the LAN debug relay.

This is a standalone replacement for the Gradle `runLanDebugRelay` task, for
use as the Android Studio run configuration.

It exists because that task could not be made compatible with Gradle's
configuration cache: a task action in a .gradle.kts script ends up holding a
reference to the script object, and the configuration cache cannot serialise
that. The failure is not contained to the task - it aborts Gradle project
import in the IDE, which is why the Builder tool window becomes unavailable and
no new build can be launched. Doing the same work in a plain script keeps
Gradle out of the picture entirely.

Usage:
    python tools/start_lan_debug_relay.py [port]

Every attached device gets its own `adb reverse` tunnel. That matters: with a
single command and no -s, adb fails with "more than one device/emulator" as
soon as a phone and an emulator are both attached, which is exactly the setup
this transport is for.
"""

import os
import platform
import signal
import subprocess
import sys
import time

DEFAULT_PORT = 8942
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def find_adb():
    """Locates adb, preferring SDK paths over the ambient PATH.

    Android Studio does not put platform-tools on the PATH of a Gradle daemon,
    and ANDROID_HOME/ANDROID_SDK_ROOT are often unset there, so local.properties
    - which Studio always writes and keeps current - is checked as well.
    """
    exe = "adb.exe" if os.name == "nt" else "adb"

    candidates = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value:
            candidates.append(os.path.join(value, "platform-tools", exe))

    local_properties = os.path.join(REPO_ROOT, "local.properties")
    if os.path.isfile(local_properties):
        with open(local_properties, "r", encoding="utf-8") as handle:
            for line in handle:
                key, _, value = line.partition("=")
                if key.strip() == "sdk.dir":
                    # .properties escapes a Windows drive colon.
                    sdk_dir = value.strip().replace("\\:", ":").replace("\\\\", "\\")
                    candidates.append(os.path.join(sdk_dir, "platform-tools", exe))
                    break

    if platform.system() == "Darwin":
        candidates.append(os.path.expanduser("~/Library/Android/sdk/platform-tools/" + exe))

    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    return exe  # fall back to PATH


def adb(adb_path, *args, timeout=10):
    """Runs adb, returning trimmed stdout or None on a non-zero exit.

    The exit code is the point: adb reports "more than one device/emulator" by
    exiting non-zero, and that is not an exception, so a caller that ignores it
    silently carries on as though the tunnel had been created.
    """
    try:
        result = subprocess.run(
            [adb_path] + list(args),
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        print(f"[PingRing] adb {' '.join(args)} could not run: {exc}")
        return None
    if result.returncode != 0:
        detail = (result.stdout + result.stderr).strip()
        print(f"[PingRing] adb {' '.join(args)} failed: {detail}")
        return None
    return result.stdout.strip()


def find_relay_pids(port):
    """PIDs of running copies of tools/lan_debug_relay.py.

    Matched on the relay script's own filename rather than on the process name,
    because "python" alone is far too broad a thing to kill - this would take
    out anything else the developer happens to be running.

    Returns an empty list when nothing is found or the query is unavailable; an
    inability to enumerate processes must never stop the relay starting.
    """
    script_name = "lan_debug_relay.py"
    try:
        if os.name == "nt":
            # wmic is deprecated and absent on newer Windows, so CIM via
            # PowerShell is the reliable route there.
            command = [
                "powershell", "-NoProfile", "-Command",
                "Get-CimInstance Win32_Process | "
                "Where-Object { $_.Name -like 'python*' -and "
                "$_.CommandLine -like '*" + script_name + "*' } | "
                "Select-Object -ExpandProperty ProcessId",
            ]
        else:
            command = ["pgrep", "-f", script_name]

        result = subprocess.run(
            command, capture_output=True, text=True, timeout=15
        )
    except (OSError, subprocess.SubprocessError):
        return []

    pids = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line.isdigit():
            continue
        pid = int(line)
        # This launcher is itself a python process whose command line contains
        # "lan_debug_relay.py" (it is a substring of "start_lan_debug_relay.py"),
        # so it always matches. Excluding it here keeps the count honest and
        # stops the caller from reporting the launcher as a stale survivor.
        if pid != os.getpid():
            pids.append(pid)
    return pids


def stop_stale_relays(port, wait_seconds=5.0):
    """Stops any relay left over from a previous run.

    A relay still holding the port makes the new one die immediately with
    "address already in use", which looks like the new relay failing to start
    rather than the old one failing to stop.

    Stops are sent politely first and only escalated for a process that has not
    exited, so a relay gets its chance to close its sockets and free the port
    cleanly - that is what keeps the next start from hitting a TIME_WAIT.
    """
    pids = find_relay_pids(port)
    if not pids:
        return 0

    print(f"[PingRing] Stopping {len(pids)} previous relay process(es) before starting...")
    for pid in pids:
        if pid == os.getpid():
            continue
        try:
            os.kill(pid, signal.SIGTERM)
        except (OSError, ProcessLookupError):
            pass

    deadline = time.time() + wait_seconds
    while time.time() < deadline:
        if not find_relay_pids(port):
            print("[PingRing] Previous relay stopped cleanly")
            return len(pids)
        time.sleep(0.25)

    remaining = find_relay_pids(port)
    for pid in remaining:
        if pid == os.getpid():
            continue
        print(f"[PingRing] Relay {pid} ignored the polite stop; forcing it")
        try:
            if os.name == "nt":
                # Windows has no SIGKILL - os.kill only accepts SIGTERM there, so
                # taskkill /F is the only way to actually force the issue.
                subprocess.run(
                    ["taskkill", "/PID", str(pid), "/F"],
                    capture_output=True, text=True, timeout=15,
                )
            else:
                os.kill(pid, getattr(signal, "SIGKILL", signal.SIGTERM))
        except (OSError, subprocess.SubprocessError, ProcessLookupError):
            pass

    time.sleep(0.5)
    still_running = find_relay_pids(port)
    if still_running:
        print(
            f"[PingRing] Warning: relay process(es) {still_running} are still running. "
            f"The new relay may fail to bind port {port}."
        )
    else:
        print("[PingRing] Previous relay stopped")
    return len(pids)


def connected_devices(adb_path):
    """Serials of every device in the 'device' state (i.e. actually ready)."""
    output = adb(adb_path, "devices")
    if not output:
        return []
    serials = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def configure_reverse_tunnels(adb_path, port):
    devices = connected_devices(adb_path)
    if not devices:
        print("[PingRing] No adb devices in state 'device'.")
        print("[PingRing] Attach a phone or start an emulator, then run this again.")
        return 0

    configured = 0
    for serial in devices:
        # -s is required: with more than one target attached, adb refuses to
        # guess which device the tunnel belongs to.
        if adb(adb_path, "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}") is not None:
            print(f"[PingRing] adb reverse tcp:{port} set on {serial}")
            configured += 1
        else:
            print(f"[PingRing] Could not set adb reverse on {serial}; it cannot reach 127.0.0.1:{port}")

    print(
        f"[PingRing] adb reverse configured on {configured} of {len(devices)} device(s): "
        f"{', '.join(devices)}"
    )
    return configured


def main():
    port = DEFAULT_PORT
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print(f"[PingRing] '{sys.argv[1]}' is not a valid port")
            return 2

    adb_path = find_adb()
    print(f"[PingRing] Using adb at {adb_path}")

    # Before anything else, clear out a relay left over from a previous run.
    stop_stale_relays(port)

    configured = configure_reverse_tunnels(adb_path, port)
    if configured == 0:
        # Not fatal: the emulator reaches the relay through 10.0.2.2 without
        # any tunnel, so the relay is started either way.
        print("[PingRing] No tunnels configured; the emulator may still connect via 10.0.2.2")

    relay = os.path.join(REPO_ROOT, "tools", "lan_debug_relay.py")
    print(f"[PingRing] Starting the LAN debug relay on port {port}...")
    # adb reverse is applied per device and can lag a running app, so give the
    # tunnels a moment to land before clients start looking for the relay.
    time.sleep(0.5)
    try:
        # exec so Ctrl+C reaches the relay directly, which is what makes it tear
        # down cleanly and free the port.
        #
        # Flush first: when stdout is a pipe or a file rather than a terminal it
        # is block-buffered, and execv replaces this process image without
        # flushing, so everything printed above would be lost.
        sys.stdout.flush()
        sys.stderr.flush()
        os.execv(sys.executable, [sys.executable, relay, str(port)])
    except OSError as exc:
        print(f"[PingRing] Could not start the relay: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
