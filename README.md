# tla-explorer

A small Java program that drives the TLA+ model checker's internal `Tool` API as an interactive, line-oriented state explorer. Given a `.tla` spec and a `.cfg` config, it enumerates initial states and successors on demand, remembers every state it has emitted, and can replay a saved sequence of states against the spec.

`Explorer.java` is a single file (no build system, no dependencies beyond `tla2tools.jar`). It is intended for two audiences:

- **Java programmers** who want a concrete, readable example of using TLC's `tlc2.tool.ITool` API (`getInitStates`, `getActions`, `getNextStates`) outside the model checker's BFS driver.
- **LLM-driven workflows** that want to step a spec one transition at a time, the same way a human would in the TLA+ Toolbox, but over stdio.

## Why a stdio explorer

TLC's normal mode is exhaustive model checking: it enumerates the entire reachable state space and reports invariant violations. That's the right tool when you have a property to check. It is the wrong tool when you want to *witness* a specific behavior --- "show me a trace where two leaders coexist, then the client writes to the new one and reads from the old one" --- because encoding that goal as a TLA+ predicate is often harder than describing it in English.

`Explorer.java` exposes the same primitives TLC uses internally, but as a synchronous request/response loop:

```
init                -- list initial states
next  <id>          -- raw TLC successors of state <id>
step  <id>          -- successors with PlusCal "MainLoop" plumbing collapsed
trace <id>          -- ordered path from an initial state to <id>
dump  <id>          -- replayable path (every raw step, no collapsing)
exit
```

Each response is a single JSON line. States are assigned integer ids on first sight and remembered forever, so backtracking is free: re-issue `step <earlier-id>` to fork a different branch without re-deriving anything.

This makes it natural to drive from a script, a notebook, or an LLM agent. The state text is `TLCState.toString()` --- the same `var = value` dump TLA+ users already recognize --- so the driver doesn't need to encode TLA+ values, only choose successor ids.

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

- **`explore.py`** --- Use case 1: interactive trace exploration. Navigates the Mutex spec state graph, checks backtracking, exercises `init`, `step`, `trace`.
- **`conformance.py`** --- Use case 2: conformance monitoring. Walks a simulated event log through the Explorer and verifies each observed transition is permitted by the spec.
- **`regression.py`** --- Use case 3: spec regression tests. Captures a trace of a valid execution and an invalid mutual-exclusion violation, then asserts that `--replay` accepts one and rejects the other.

All three import from `examples/explorer.py`, which wraps the Explorer subprocess and handles the JSONL stdio protocol.

## Example session

Spec is `LeaseGuard.tla` (a PlusCal protocol with `Write`, `Commit`, `Read`, `BecomeLeader`, etc., wrapped in a `MainLoop` dispatcher label). Lines starting with `>` are sent to the explorer; the others are its replies (one JSON line each, abbreviated here).

```
> init
{"ok":true,"states":[{"id":0,"text":"currentTerm = <<0,0,0>>\nstate = <<\"follower\",\"follower\",\"follower\">>\n..."}]}
> step 0
{"ok":true,"from":0,"transitions":[
  {"id":1,"action":"BecomeLeader","text":"... state = <<\"leader\",\"follower\",\"follower\">> ..."},
  {"id":2,"action":"Tick","text":"... clocks = <<1,1,1>> ..."}]}
> step 1
{"ok":true,"from":1,"transitions":[
  {"id":3,"action":"Write","text":"..."}, ...]}
> trace 3
{"ok":true,"trace":[
  {"id":0,"action":"","text":"..."},
  {"id":1,"action":"BecomeLeader","text":"..."},
  {"id":3,"action":"Write","text":"..."}]}
```

`step` vs `next`: `step` skips through PlusCal's `MainLoop` plumbing label (which only sets `pc[self] := "<branch>"`) so each returned transition is a semantic protocol action. `next` is the raw TLC view --- useful when the spec has no PlusCal `MainLoop` dispatch, or when you want to inspect the dispatcher itself.

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

This generalizes to runtime conformance monitoring of a real implementation. If your production system logs `(action, post-state)` events in a form that maps onto the spec's variables, you can feed that log into the explorer and assert that the implementation's observed behavior is allowed by the spec. Divergences show up as "no successor matched trace text" at the offending step. The spec becomes a live oracle, not a one-time design document.

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

| command       | response shape                                                                          |
|---------------|------------------------------------------------------------------------------------------|
| `init`        | `{"ok":true,"states":[{"id":N,"text":"..."}, ...]}`                                     |
| `next <id>`   | `{"ok":true,"from":N,"transitions":[{"id":M,"action":"X","text":"..."}, ...]}`          |
| `step <id>`   | same shape as `next`, with PlusCal `MainLoop` collapsed                                  |
| `trace <id>`  | `{"ok":true,"trace":[{"id":N,"action":"","text":"..."}, ...]}` (init first)              |
| `dump <id>`   | `{"ok":true,"dump":[{"action":"","text":"..."}, ...]}` (raw, replayable)                 |
| `exit`/`quit` | terminates the process                                                                   |
| unknown line  | `{"ok":false,"error":"..."}` and the process keeps running                               |

Any thrown exception is reported as `{"ok":false,"error":"..."}` and does not crash the process. The dispatch boundary catches `Throwable` so the loop survives malformed input. Inside `getNextStates`, only `EvalException` (TLC's "action guard was false" signal) is silently continued; any other throwable propagates to the outer handler and is reported as an error.

### State identity

The explorer compares states by **normalized text**, not by TLC fingerprint. The normalization (`canon` in the source) collapses whitespace and alphabetically sorts record field names, because TLC's `RecordValue.normalize` orders fields by intern order, and intern order is not stable across the command sequences a driver might issue. Sorting field names by code gives a canonical form that doesn't depend on TLC's internal `UniqueString` table.

TLC fingerprints would be a tempting alternative but they depend on `SYMMETRY` perms and on type-aware `compareTo`, which throws on heterogeneous records. Text-based comparison is slower but robust across spec variants.

### `MainLoop` collapsing

PlusCal's typical idiom is a `while TRUE do ... end while` body with a dispatch label (here named `MainLoop`) that sets `pc[self] := "<branch>"` for one of N branches. From TLC's view this is two transitions: `MainLoop` (chooses the branch) and then the branch itself (does the work). For interactive exploration the dispatch is noise; `step` and `trace` collapse it so each visible transition is the semantically meaningful one.

If the spec uses a different dispatcher name or no PlusCal at all, use `next` and `dump` instead of `step` and `trace`.

## Files

- `Explorer.java` --- the program. Single file, ~500 lines, no third-party dependencies. The replay logic and the interactive loop share the state-text-comparison code, so they cannot drift.

## License

MIT.
