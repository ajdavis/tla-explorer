"""
Use case 3 -- example traces as spec regression tests.

Captures an interesting trace via interactive exploration and uses
--replay to assert it is still permitted by the spec after any future
spec edit. A second trace encodes a mutual-exclusion violation that the
spec must reject. Both become permanent regression tests.

The key property: once the LLM (or a human) finds an interesting
trace, no further AI is needed. --replay is a deterministic Java program
that either matches or doesn't.

Run from the project root:
    python3 examples/regression.py
"""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from explorer import Explorer, replay, SPEC_DIR, MUTEX


def write_trace(dump: list[dict]) -> str:
    """Write a dump list to a temp JSON file and return its path."""
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False
    ) as f:
        json.dump({"ok": True, "dump": dump}, f)
        return f.name


def main() -> None:
    spec, cfg = MUTEX

    # --- capture a valid trace interactively ---
    # p1 requests the lock, then enters the critical section.
    with Explorer(spec, cfg, cwd=SPEC_DIR) as ex:
        r = ex.send("init")
        assert r["ok"], r
        init_id = r["states"][0]["id"]

        r = ex.send(f"step {init_id}")
        req1 = next(t for t in r["transitions"] if t["action"] == "Request1")

        r = ex.send(f"step {req1['id']}")
        enter1 = next(t for t in r["transitions"] if t["action"] == "Enter1")

        # dump gives the exact TLC state text needed for --replay
        r = ex.send(f"dump {enter1['id']}")
        assert r["ok"], r
        valid_dump = r["dump"]

    # The valid trace must replay cleanly against the spec.
    valid_path = write_trace(valid_dump)
    passed, output = replay(spec, cfg, valid_path, cwd=SPEC_DIR)
    assert passed, f"valid trace failed --replay:\n{output}"

    # --- construct an invalid trace ---
    # Append a step where p2 also becomes critical.  Enter2 requires
    # p1 != "critical", so this transition cannot follow from the state
    # where p1 = "critical".
    critical_text = valid_dump[-1]["text"]          # p1="critical", p2="idle"
    violation_text = critical_text.replace(         # p1="critical", p2="critical"
        'p2 = "idle"', 'p2 = "critical"'
    )
    bad_dump = valid_dump + [{"action": "Enter2", "text": violation_text}]
    bad_path = write_trace(bad_dump)
    passed, output = replay(spec, cfg, bad_path, cwd=SPEC_DIR)
    assert not passed, f"mutual-exclusion violation passed --replay:\n{output}"

    print("regression: OK")


if __name__ == "__main__":
    main()
