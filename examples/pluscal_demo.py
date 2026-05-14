"""
Demonstrates the difference between `next` and `step` for a PlusCal spec.

Counter.tla is the TLA+ translation of:

    --algorithm Counter
    variables x = 0;
    begin
      while x < 3 do
        Inc: x := x + 1;
      end while;
    end algorithm;

PlusCal's unlabeled while loop compiles to a MainLoop action that only
updates pc (the program counter). With `next` you see every raw TLC
transition, including those MainLoop hops. With `step`, MainLoop hops are
transparently skipped and you see only the semantically meaningful
transitions (Inc and the terminal state).
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from explorer import Explorer, SPEC_DIR

COUNTER = ("Counter.tla", "Counter.cfg")


def walk(ex: Explorer, init_id: int, command: str) -> None:
    print(f"\n--- {command} ---")
    state_id = init_id
    prev_state = None
    for _ in range(20):
        r = ex.send(f"{command} {state_id}")
        assert r["ok"], r
        if not r["transitions"]:
            print("(no successors)")
            break
        t = r["transitions"][0]
        if t["state"] == prev_state:  # self-loop (e.g. Terminating)
            break
        print(json.dumps({"action": t["action"], "state": t["state"]}))
        prev_state = t["state"]
        state_id = t["id"]


def main() -> None:
    with Explorer(*COUNTER, cwd=SPEC_DIR) as ex:
        r = ex.send("init")
        assert r["ok"], r
        init = r["states"][0]
        print("initial state:")
        print(json.dumps(init["state"]))
        init_id = init["id"]

        walk(ex, init_id, "next")
        walk(ex, init_id, "step")


if __name__ == "__main__":
    main()
