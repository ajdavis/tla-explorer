# tla-explorer

A small Java program that drives the TLA+ model checker's internal `Tool` API as an interactive, line-oriented state explorer. Given a `.tla` spec and a `.cfg` config, it enumerates initial states and successors on demand, remembers every state it has emitted, and can replay a saved sequence of states against the spec.

`Explorer.java` is a single file built with Maven. Its only runtime dependency is `tla2tools.jar`, which also bundles Gson (used for JSON parsing in replay mode).

## Why a stdio explorer

- Trace-checking: given a JSON trace log from an implementation, check the behavior was allowed by the spec.
- Runtime conformance monitoring: continuous trace-checking. (Will eventually require a pruning feature to limit RAM use.)
- AI-powered exploration: given a prose description of a behavior, e.g. "a leader is elected, then another, then the first crashes," an LLM can use the tool over stdio to find a sequence of states, or else it can discover that sequence is not allowed by the spec.
- Example-based testing of specs: store traces from a spec as JSON and check they're allowed or prohibited (as desired) by later versions of the same spec.

`Explorer.java` exposes the same primitives TLC uses internally, but as a synchronous request/response loop:

```
init                -- list initial states
next  <id>          -- successors of state <id> (one TLC step)
trace <id>          -- ordered path from an initial state to <id>
dump  <id>          -- replayable path (no ids, for --replay)
exit
```

Each response is a single JSON line. States are assigned integer ids on first sight and remembered forever, so backtracking is free: re-issue `next <earlier-id>` to fork a different branch without re-deriving anything.

This makes it natural to drive from a script, a notebook, or an LLM agent. States are serialized as structured JSON using the [ITF (Informal Trace Format)](https://apalache-mc.org/docs/adr/015adr-trace.html) used by Apalache and Quint, so traces produced by the explorer are compatible with those tools.

## Build and run

Requires Java 11+, Maven, and Python 3.10+. Download TLA+'s `tla2tools.jar` (v1.8.0) into the project root:

```bash
curl -L -o tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar
```

Build with Maven:

```bash
mvn package -DskipTests
```

The runnable jar is `target/tla-explorer-1.0-SNAPSHOT.jar`. Interactive mode:

```bash
java -cp tla2tools.jar:target/tla-explorer-1.0-SNAPSHOT.jar Explorer YourSpec.tla YourSpec.cfg
```

The first line printed is `{"ok":true,"ready":true}`. Then send commands on stdin, one per line.

Replay mode (see "Traces as spec unit tests" below):

```bash
java -cp tla2tools.jar:target/tla-explorer-1.0-SNAPSHOT.jar Explorer --replay YourSpec.tla YourSpec.cfg saved-trace.json
```

Exit code 0 means the trace still replays; non-zero means the spec diverged from the saved trace.

### Running tests

```bash
./run_tests.sh
```

This runs the Java unit tests (`mvn test`) and all three Python example programs. `tla2tools.jar` must be present in the project root. The examples in `examples/` double as tests: they exercise the live Explorer subprocess against the Mutex spec and assert correct behavior.

### Pre-commit hook

The hook scripts live in `.githooks/` and are tracked by git, but git hooks are always local---git never installs them automatically on clone. After checking out, run once:

```bash
git config core.hooksPath .githooks
```

This points git at the tracked directory. The pre-commit hook runs `./run_tests.sh` before every commit.

## Examples

The `examples/` directory contains three self-contained Python programs that demonstrate each use case. Each also runs as a test (via `run_tests.sh`).

- **`explore.py`** --- Use case 1: interactive trace exploration. Navigates the Mutex spec state graph, checks backtracking, exercises `init`, `next`, `trace`.
- **`conformance.py`** --- Use case 2: conformance monitoring. Walks an event log through the Explorer and verifies each observed transition is permitted by the spec.
- **`regression.py`** --- Use case 3: spec regression tests. Captures a trace of a valid execution and an invalid mutual-exclusion violation, then asserts that `--replay` accepts one and rejects the other.

All three import from `examples/explorer.py`, which wraps the Explorer subprocess and handles the JSONL stdio protocol.

## Example session

Spec is `LeaseGuard.tla` (a PlusCal protocol with `Write`, `Commit`, `Read`, `BecomeLeader`, etc.). Lines starting with `>` are sent to the explorer; the others are its replies (one JSON line each, abbreviated here).

```
> init
{"ok":true,"states":[{"id":0,"state":{"currentTerm":[{"#bigint":"0"},{"#bigint":"0"},{"#bigint":"0"}],"state":["follower","follower","follower"],...}}]}
> next 0
{"ok":true,"from":0,"transitions":[
  {"id":1,"action":"BecomeLeader","state":{"state":["leader","follower","follower"],...}},
  {"id":2,"action":"Tick","state":{"clocks":[{"#bigint":"1"},{"#bigint":"1"},{"#bigint":"1"}],...}}]}
> next 1
{"ok":true,"from":1,"transitions":[
  {"id":3,"action":"Write","state":{...}}, ...]}
> trace 3
{"ok":true,"trace":[
  {"id":0,"action":"","state":{...}},
  {"id":1,"action":"BecomeLeader","state":{...}},
  {"id":3,"action":"Write","state":{...}}]}
```

## Wider ideas

The explorer is small but it exists because it's the missing link in a few related practices.

### Trace exploration

When you write a spec, the first question is usually "does it reach the states I think it does?" Exhaustive model checking answers a stronger question --- "does it ever violate this invariant?" --- but it doesn't help you confirm that, say, the system can even reach a configuration with two concurrent leaders, or with a partitioned quorum, or with a stale committed entry that an old leader is about to serve.

Random simulation (`tlc2.TLC -simulate`) gives you sample traces, but you can't aim it. Hand-written canary invariants like `~(twoLeaders /\ clientWroteToNew /\ clientReadFromOld)` work but require you to encode the goal in TLA+ first, and a counterexample to a deeply-nested canary can be hundreds of steps with no continuity between them.

Interactive exploration is the third option: walk the state graph by hand, with the goal in your head, picking successors that look like progress. The explorer makes that cheap because it remembers state, returns successors as a flat list of ids, and lets you backtrack to any state for free.

Driving the loop from an LLM works well because protocol goals are usually easy to express in English ("elect a new leader without forcing the old one to step down, then have the client write to it") and the choice at each step is informed by the actual state dump, which the LLM can read.

### Trace checking and runtime conformance monitoring

A trace is a sequence `s_0 --a_1--> s_1 --a_2--> s_2 ...` Once you have one --- from interactive exploration, from a TLC counterexample, or from a real system's log --- you can ask: does the spec actually permit this? "Replay" is the answer: walk the trace step by step, and at each step verify that there's an enabled action with the named label that produces the next state.

`Explorer --replay` does exactly this. For each pair `(action_i, state_i)` it asks TLC's `getNextStates(action_i, state_{i-1})` for every Action with that name (PlusCal expands one labeled action with N processes into N Action objects sharing a name), and checks that one of them produces a state matching `state_i`. If yes, advance; if no, fail loudly with the step number, the action name, and the number of candidate successors that were considered.

This generalizes to runtime conformance monitoring of a real implementation. If your production system logs `(action, post-state)` events in a form that maps onto the spec's variables, you can feed that log into the explorer and assert that the implementation's observed behavior is allowed by the spec. Divergences show up as "no successor matched trace state" at the offending step. The spec becomes a live oracle, not a one-time design document.

### Example traces as spec unit tests

A saved trace is also a regression test for the spec itself. If the spec was supposed to permit `multi_leader_old_read.trace.json` (the "old leader serves a correct committed read after a new leader is elected" scenario), then any spec edit that breaks that ability has either fixed a bug or introduced one --- either way you want to know immediately, not three weeks later when you try to interactively reconstruct the trace and can't.

Symmetrically, a saved trace can encode a *bug* that the spec was later fixed to forbid. The stale-read trace from LeaseGuard, captured against the pre-fix variant (missing the 2-epsilon safety margin on lease validity), should *no longer* replay against the fixed spec. If it does, the fix has regressed.

Concretely, a regression harness around `--replay` looks like this:

```bash
run() {
    local label="$1" expected="$2" cfg="$3" trace="$4"
    out=$(java -cp tla2tools.jar:. Explorer --replay Spec.tla "$cfg" "$trace" 2>&1)
    actual=FAIL
    echo "$out" | grep -q "^PASS:" && actual=PASS
    [ "$actual" = "$expected" ] && echo "OK $label" || { echo "BAD $label (want $expected, got $actual)"; return 1; }
}

run multi_leader_old_read  PASS trace.cfg traces/multi_leader_old_read.trace.json
run stale_read_unreachable FAIL trace.cfg traces/stale_read.trace.json
```

The LLM (or the human) is needed once to find each interesting trace. After that, no AI is involved in regression: replay is a deterministic Java program that either matches or doesn't.

## Protocol details

### Commands

| command       | response shape                                                                              |
|---------------|----------------------------------------------------------------------------------------------|
| `init`        | `{"ok":true,"states":[{"id":N,"state":{...}}, ...]}`                                       |
| `next <id>`   | `{"ok":true,"from":N,"transitions":[{"id":M,"action":"X","state":{...}}, ...]}`            |
| `trace <id>`  | `{"ok":true,"trace":[{"id":N,"action":"","state":{...}}, ...]}` (init first, includes ids) |
| `dump <id>`   | `{"ok":true,"dump":[{"action":"","state":{...}}, ...]}` (no ids, for `--replay`)           |
| `exit`/`quit` | terminates the process                                                                      |
| unknown line  | `{"ok":false,"error":"..."}` and the process keeps running                                  |

State objects use [ITF (Informal Trace Format)](https://apalache-mc.org/docs/adr/015adr-trace.html): integers become `{"#bigint":"n"}`, sets `{"#set":[...]}`, functions `{"#map":[[k,v],...]}`, records native JSON objects, sequences native JSON arrays.

Any thrown exception is reported as `{"ok":false,"error":"..."}` and does not crash the process. The dispatch boundary catches `Throwable` so the loop survives malformed input and propagates real errors (including `EvalException` for spec evaluation failures like division by zero) as error responses rather than silently swallowing them.

### State identity

The explorer serializes states with `deepNormalize()` before JSON conversion, which recursively sorts set elements and record fields into a canonical order. Replay mode compares states using Gson's `JsonElement.equals`, which is order-insensitive for JSON objects and order-sensitive for arrays---exactly the right semantics for TLA+ records (unordered) vs sequences (ordered).

## Files

- `Explorer.java` --- the program. Single file, no third-party dependencies beyond Gson (bundled in `tla2tools.jar`). The replay logic and the interactive loop share `stateJson()`, so serialization cannot drift between them.

## License

MIT.
