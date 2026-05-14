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
