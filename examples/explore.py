"""
Use case 1 -- trace exploration.

Demonstrates walking a spec's state graph interactively to confirm
reachability properties that are easy to express in English but awkward
to encode as TLA+ invariants.

Spec: Mutex.tla -- two processes sharing a single lock.
Goal: show that process 1 can reach the critical section, and that the
mutual-exclusion property holds there (p2 cannot enter while p1 is critical).

Commands used: init (list initial states), step <id> (successor transitions),
trace <id> (path back to init).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from explorer import Explorer, SPEC_DIR, MUTEX


def main() -> None:
    spec, cfg = MUTEX
    with Explorer(spec, cfg, cwd=SPEC_DIR) as ex:

        # --- initial states ---
        r = ex.send("init")
        assert r["ok"], r
        assert len(r["states"]) == 1, \
            f"expected 1 initial state, got {len(r['states'])}"
        init = r["states"][0]
        assert init["state"] == {"p1": "idle", "p2": "idle"}, init["state"]
        init_id = init["id"]

        # --- from init, only Request1 and Request2 are enabled ---
        r = ex.send(f"step {init_id}")
        assert r["ok"], r
        actions = {t["action"] for t in r["transitions"]}
        assert actions == {"Request1", "Request2"}, \
            f"expected {{Request1, Request2}} from init, got {actions}"

        # --- follow Request1 -- p1 is now waiting ---
        req1 = next(t for t in r["transitions"] if t["action"] == "Request1")
        assert req1["state"]["p1"] == "waiting", req1["state"]

        # --- from (waiting, idle): Enter1 and Request2 are enabled; Enter2 is not ---
        r = ex.send(f"step {req1['id']}")
        assert r["ok"], r
        actions = {t["action"] for t in r["transitions"]}
        assert "Enter1"   in actions, f"Enter1 not enabled; got {actions}"
        assert "Request2" in actions, f"Request2 not enabled; got {actions}"
        assert "Enter2" not in actions, \
            f"Enter2 wrongly enabled when p2=idle; got {actions}"

        # --- follow Enter1 -- p1 is now in the critical section ---
        enter1 = next(t for t in r["transitions"] if t["action"] == "Enter1")
        assert enter1["state"]["p1"] == "critical", enter1["state"]
        assert enter1["state"]["p2"] == "idle",     enter1["state"]

        # --- mutual exclusion: Enter2 must not be available while p1 is critical ---
        r = ex.send(f"step {enter1['id']}")
        assert r["ok"], r
        actions = {t["action"] for t in r["transitions"]}
        assert "Enter2" not in actions, \
            f"mutual exclusion violated: Enter2 enabled while p1=critical; got {actions}"
        assert "Exit1"    in actions, f"Exit1 not enabled from critical; got {actions}"
        assert "Request2" in actions, f"Request2 not enabled from critical; got {actions}"

        # --- trace from p1=critical back to init is exactly [init, Request1, Enter1] ---
        r = ex.send(f"trace {enter1['id']}")
        assert r["ok"], r
        trace_actions = [t["action"] for t in r["trace"]]
        assert trace_actions == ["", "Request1", "Enter1"], \
            f"unexpected trace: {trace_actions}"

        # --- backtrack: step from init again to show free backtracking ---
        r = ex.send(f"step {init_id}")
        assert r["ok"], r
        assert {t["action"] for t in r["transitions"]} == {"Request1", "Request2"}

    print("explore: OK")


if __name__ == "__main__":
    main()
