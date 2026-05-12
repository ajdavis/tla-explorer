"""
Use case 2 -- conformance monitoring.

A real system logs (action, resulting_state) events. This program verifies
that a sequence of such events is a permitted execution of the spec, by
walking the Explorer's state graph step by step and checking that each
observed transition is enabled.

A good log (one that follows the spec) passes. A bad log (one where the
implementation did something the spec forbids) fails with a precise error
pointing to the first diverging step.

Run from the project root:
    python3 examples/conformance.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from explorer import Explorer, SPEC_DIR, MUTEX, norm


# Simulated event log from a real (or imagined) implementation.
# Each entry is (action_name, resulting_state).
# The first entry uses action="" for the initial state.
# State text is written in a simplified form; norm() handles the TLC
# "/\ varname = value" formatting when comparing.
GOOD_LOG: list[tuple[str, str]] = [
    ("",         'p1 = "idle" p2 = "idle"'),
    ("Request1", 'p1 = "waiting" p2 = "idle"'),
    ("Enter1",   'p1 = "critical" p2 = "idle"'),
    ("Exit1",    'p1 = "idle" p2 = "idle"'),
]

# A bad log: the implementation enters the critical section without first
# requesting -- the spec requires p1 = "waiting" before Enter1 can fire.
BAD_LOG: list[tuple[str, str]] = [
    ("",       'p1 = "idle" p2 = "idle"'),
    ("Enter1", 'p1 = "critical" p2 = "idle"'),
]


def check_log(ex: Explorer, log: list[tuple[str, str]]) -> tuple[bool, str]:
    """Verify that log is a permitted execution according to the spec.

    Returns (conforms, message). On failure the message names the first
    step where no matching transition was found.
    """
    r = ex.send("init")
    assert r["ok"], r

    current_id = next(
        (s["id"] for s in r["states"] if norm(s["text"]) == norm(log[0][1])),
        None,
    )
    if current_id is None:
        return False, f"no initial state matches log[0] {log[0][1]!r}"

    for step, (action, state_text) in enumerate(log[1:], 1):
        r = ex.send(f"next {current_id}")
        assert r["ok"], r
        match = next(
            (t for t in r["transitions"]
             if t["action"] == action and norm(t["text"]) == norm(state_text)),
            None,
        )
        if match is None:
            available = [(t["action"], norm(t["text"])) for t in r["transitions"]]
            return False, (
                f"step {step}: ({action!r}, {state_text!r}) not permitted; "
                f"available transitions: {available}"
            )
        current_id = match["id"]

    return True, "ok"


def main() -> None:
    spec, cfg = MUTEX

    with Explorer(spec, cfg, cwd=SPEC_DIR) as ex:
        ok, msg = check_log(ex, GOOD_LOG)
        assert ok, f"good log rejected: {msg}"

    with Explorer(spec, cfg, cwd=SPEC_DIR) as ex:
        ok, msg = check_log(ex, BAD_LOG)
        assert not ok, f"bad log should have been rejected but was accepted"

    print("conformance: OK")


if __name__ == "__main__":
    main()
