"""
Use case 2 -- runtime conformance monitoring.

Two Python threads compete for a mutex using threading.Lock. Each
announces its state transitions (idle/waiting/critical) by putting
events on a shared queue. A monitor consumes those events in real
time, reconstructs the global TLA+ state, and checks each transition
against the Mutex spec via Explorer.

The good scenario follows the spec. The bad scenario has p1 jump
directly from idle to critical (skipping waiting), which the monitor
catches immediately.
"""
import queue
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from explorer import Explorer, SPEC_DIR, MUTEX

_STEP = 0.05   # seconds between state transitions; gives the monitor time to check

Event = tuple[str, str] | None   # (pid, new_state), or None sentinel


def good_thread(pid: str, lock: threading.Lock, events: "queue.Queue[Event]") -> None:
    """Obeys the spec: idle -> waiting -> critical -> idle."""
    events.put((pid, "waiting"))
    time.sleep(_STEP)
    lock.acquire()
    events.put((pid, "critical"))
    time.sleep(_STEP)
    lock.release()
    events.put((pid, "idle"))


def bad_thread(pid: str, lock: threading.Lock, events: "queue.Queue[Event]") -> None:
    """Violates the spec: jumps idle -> critical without announcing waiting."""
    lock.acquire()
    events.put((pid, "critical"))
    time.sleep(_STEP)
    lock.release()
    events.put((pid, "idle"))


def monitor(ex: Explorer, events: "queue.Queue[Event]", initial: dict[str, str]) -> tuple[bool, str]:
    """
    Consume events until the None sentinel, verifying each resulting
    global state against the spec. Returns (conforms, message).
    """
    r = ex.send("init")
    assert r["ok"], r
    current_id = next((s["id"] for s in r["states"] if s["state"] == initial), None)
    if current_id is None:
        return False, f"no initial state matches {initial}"

    state = dict(initial)
    while (event := events.get()) is not None:
        pid, new_process_state = event
        state = {**state, pid: new_process_state}

        r = ex.send(f"next {current_id}")
        assert r["ok"], r
        match = next((t for t in r["transitions"] if t["state"] == state), None)
        if match is None:
            available = [t["state"] for t in r["transitions"]]
            return False, (
                f"{pid} -> {new_process_state!r}: state {state} not permitted; "
                f"available: {available}"
            )
        current_id = match["id"]

    return True, "ok"


def run_scenario(
    thread_fns: dict[str, callable],
    initial: dict[str, str] | None = None,
) -> tuple[bool, str]:
    """Launch threads, run the monitor concurrently, return (conforms, message)."""
    if initial is None:
        initial = {pid: "idle" for pid in thread_fns}

    events: "queue.Queue[Event]" = queue.Queue()
    lock = threading.Lock()

    def launch():
        workers = [
            threading.Thread(target=fn, args=(pid, lock, events))
            for pid, fn in thread_fns.items()
        ]
        for t in workers:
            t.start()
        for t in workers:
            t.join()
        events.put(None)  # signal monitor to stop

    launcher = threading.Thread(target=launch)
    launcher.start()

    spec, cfg = MUTEX
    with Explorer(spec, cfg, cwd=SPEC_DIR) as ex:
        result = monitor(ex, events, initial)

    launcher.join()
    return result


def main() -> None:
    ok, msg = run_scenario({"p1": good_thread, "p2": good_thread})
    assert ok, f"good scenario rejected: {msg}"

    ok, msg = run_scenario({"p1": bad_thread, "p2": good_thread})
    assert not ok, f"bad scenario should have been rejected but was accepted"

    print("conformance: OK")


if __name__ == "__main__":
    main()
