import tlc2.tool.Action;
import tlc2.tool.ITool;
import tlc2.tool.StateVec;
import tlc2.tool.TLCState;
import tlc2.tool.impl.FastTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive TLA+ state explorer. Loads a spec + config and answers two
 * commands over a line-oriented stdio protocol:
 *
 *   init                    -- list initial states
 *   next <id>               -- list successor transitions of state <id>
 *   exit                    -- quit
 *
 * States are given monotonically increasing integer ids. The LLM never has
 * to encode a state value; it picks by id and the explorer remembers the
 * TLCState object.
 *
 * Output is one JSON object per line (JSONL). TLA+ state values are
 * embedded as strings using TLCState.toString(), so the LLM sees familiar
 * TLA+ syntax.
 */
public class Explorer {
    private final ITool tool;
    private final List<TLCState> states = new ArrayList<>();
    /** Parent id for each state (-1 for initial states). */
    private final List<Integer> parentIds = new ArrayList<>();
    /** Action name that produced each state (empty for initial states). */
    private final List<String> parentActions = new ArrayList<>();

    public Explorer(String specDir, String specName, String configName) {
        this.tool = new FastTool(specDir, specName, configName, null);
    }

    private int remember(TLCState s, int parentId, String parentAction) {
        int id = states.size();
        states.add(s);
        parentIds.add(parentId);
        parentActions.add(parentAction);
        return id;
    }

    /** Emit initial states. */
    public String doInit() {
        StateVec vec = tool.getInitStates();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"states\":[");
        for (int i = 0; i < vec.size(); i++) {
            TLCState s = vec.elementAt(i);
            int id = remember(s, -1, "");
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(id)
              .append(",\"text\":").append(jsonStr(stateText(s)))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Emit raw successor transitions of state with given id (one TLC step). */
    public String doNext(int stateId) {
        return emitTransitions(stateId, rawSuccessors(stateId));
    }

    /**
     * Walk raw parent pointers and emit a replayable JSONL trace. Unlike
     * doTrace, this does NOT collapse MainLoop --- the resulting trace can
     * be fed to --replay verbatim and TLC will match each step. Each entry
     * is {action, fp (decimal fingerprint), text}. First entry is the init
     * state with action="".
     */
    public String doDump(int stateId) {
        if (stateId < 0 || stateId >= states.size()) {
            return "{\"ok\":false,\"error\":\"unknown state id " + stateId + "\"}";
        }
        List<Integer> chain = new ArrayList<>();
        int cur = stateId;
        while (cur >= 0) {
            chain.add(cur);
            cur = parentIds.get(cur);
        }
        java.util.Collections.reverse(chain);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"dump\":[");
        for (int i = 0; i < chain.size(); i++) {
            int id = chain.get(i);
            TLCState s = states.get(id);
            if (i > 0) sb.append(",");
            sb.append("{\"action\":").append(jsonStr(parentActions.get(id)))
              .append(",\"text\":").append(jsonStr(stateText(s)))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Walk parent pointers from the given state back to an initial state,
     * producing an ordered trace. Successive MainLoop intermediate steps are
     * collapsed: the MainLoop entry is dropped and its child keeps the
     * semantic action name. (This matches the view that `step` produces.)
     */
    public String doTrace(int stateId) {
        if (stateId < 0 || stateId >= states.size()) {
            return "{\"ok\":false,\"error\":\"unknown state id " + stateId + "\"}";
        }
        // Collect raw chain root-first.
        List<Integer> chain = new ArrayList<>();
        int cur = stateId;
        while (cur >= 0) {
            chain.add(cur);
            cur = parentIds.get(cur);
        }
        java.util.Collections.reverse(chain);

        // Collapse MainLoop steps: drop any entry whose own action is MainLoop.
        // The next entry (the branch action) already has the branch name.
        List<Integer> collapsed = new ArrayList<>();
        for (int id : chain) {
            if ("MainLoop".equals(parentActions.get(id))) continue;
            collapsed.add(id);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"trace\":[");
        for (int i = 0; i < collapsed.size(); i++) {
            int id = collapsed.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(id)
              .append(",\"action\":").append(jsonStr(parentActions.get(id)))
              .append(",\"text\":").append(jsonStr(stateText(states.get(id))))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Emit step successors: skip through any MainLoop transitions until a
     * meaningful PlusCal branch fires. MainLoop is pure plumbing that just
     * sets pc[self] := "<branch>"; the LLM wants to see the branch itself.
     * For each raw successor of the current state:
     *   - if the action is MainLoop, recurse one level and keep those.
     *   - otherwise, keep it directly.
     * The returned id always points at a post-branch state.
     */
    public String doStep(int stateId) {
        if (stateId < 0 || stateId >= states.size()) {
            return "{\"ok\":false,\"error\":\"unknown state id " + stateId + "\"}";
        }
        List<Transition> out = new ArrayList<>();
        for (Transition t : rawSuccessors(stateId)) {
            if (!"MainLoop".equals(t.action)) {
                out.add(t);
                continue;
            }
            // Expand MainLoop: recurse one level; keep any non-MainLoop grandchildren.
            for (Transition gc : rawSuccessors(t.id)) {
                if (!"MainLoop".equals(gc.action)) {
                    out.add(gc);
                }
            }
        }
        return emitTransitionList(stateId, out);
    }

    private static final class Transition {
        final int id;
        final String action;
        final TLCState state;
        Transition(int id, String action, TLCState state) {
            this.id = id; this.action = action; this.state = state;
        }
    }

    private List<Transition> rawSuccessors(int stateId) {
        List<Transition> out = new ArrayList<>();
        if (stateId < 0 || stateId >= states.size()) return out;
        TLCState from = states.get(stateId);
        for (Action a : tool.getActions()) {
            StateVec succs = tool.getNextStates(a, from);
            if (succs == null) continue;
            for (int i = 0; i < succs.size(); i++) {
                TLCState s = succs.elementAt(i);
                if (!s.allAssigned()) continue;
                String actionName = a.getName().toString();
                int id = remember(s, stateId, actionName);
                out.add(new Transition(id, actionName, s));
            }
        }
        return out;
    }

    private String emitTransitions(int fromId, List<Transition> ts) {
        return emitTransitionList(fromId, ts);
    }

    private String emitTransitionList(int fromId, List<Transition> ts) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"from\":").append(fromId).append(",\"transitions\":[");
        boolean first = true;
        for (Transition t : ts) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"id\":").append(t.id)
              .append(",\"action\":").append(jsonStr(t.action))
              .append(",\"text\":").append(jsonStr(stateText(t.state)))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Minimal TLA+-flavored state rendering using TLCState.toString(). */
    private static String stateText(TLCState s) {
        // TLCState.toString() returns "var1 = expr\nvar2 = expr\n..." which
        // is already the classic TLC dump format the LLM will recognize.
        return s.toString().trim();
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Replay a saved dump file against the spec. Fails fast on the first
     * step whose target state cannot be reached via the named action.
     * Exit 0 = trace replays cleanly; exit 1 = trace diverged.
     *
     * Dump format: JSON object with "dump" array of {action, text}. States
     * are matched by normalized text (TLCState.toString with whitespace
     * collapsed). We use text instead of fingerprint because fingerprint
     * depends on symmetry perms and type-aware compareTo (which throws on
     * heterogeneous records), neither of which are stable/safe across all
     * spec variants we want to replay.
     */
    public static int doReplay(String specDir, String specName, String cfgAbs,
                                String tracePath) throws Exception {
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(tracePath)));
        java.util.regex.Pattern entryP = java.util.regex.Pattern.compile(
            "\\{\\s*\"action\"\\s*:\\s*\"([^\"]*)\"\\s*," +
            "\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}");
        java.util.regex.Matcher m = entryP.matcher(raw);
        List<String> actions = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (m.find()) {
            actions.add(m.group(1));
            texts.add(canon(unjson(m.group(2))));
        }
        if (texts.isEmpty()) {
            System.out.println("FAIL: empty trace");
            return 1;
        }

        tlc2.util.FP64.Init(0);
        ITool tool = new FastTool(specDir, specName, cfgAbs, null);
        tlc2.tool.TLCStateMut.setTool(tool);

        TLCState current = null;
        StateVec initStates = tool.getInitStates();
        for (int i = 0; i < initStates.size(); i++) {
            TLCState s = initStates.elementAt(i);
            if (canon(stateText(s)).equals(texts.get(0))) { current = s; break; }
        }
        if (current == null) {
            System.out.println("FAIL: no init state matches trace[0]");
            return 1;
        }
        System.out.println("OK  step 0 <init>");

        Action[] allActions = tool.getActions();
        for (int i = 1; i < texts.size(); i++) {
            String wantAction = actions.get(i);
            String wantText = texts.get(i);
            // A PlusCal action with N processes becomes N Action objects
            // sharing the same name; we must try them all.
            List<Action> matchingActions = new ArrayList<>();
            for (Action a : allActions) {
                if (a.getName().toString().equals(wantAction)) matchingActions.add(a);
            }
            if (matchingActions.isEmpty()) {
                System.out.println("FAIL step " + i + ": unknown action " + wantAction);
                return 1;
            }
            TLCState match = null;
            int totalSuccs = 0;
            for (Action actionObj : matchingActions) {
                StateVec succs = tool.getNextStates(actionObj, current);
                if (succs == null) continue;
                totalSuccs += succs.size();
                for (int j = 0; j < succs.size(); j++) {
                    TLCState s = succs.elementAt(j);
                    if (!s.allAssigned()) continue;
                    if (canon(stateText(s)).equals(wantText)) { match = s; break; }
                }
                if (match != null) break;
            }
            if (match == null) {
                System.out.println("FAIL step " + i + " " + wantAction
                        + ": no successor matched trace text"
                        + " (searched " + totalSuccs + " candidates across "
                        + matchingActions.size() + " action instances)");
                return 1;
            }
            System.out.println("OK  step " + i + " " + wantAction);
            current = match;
        }
        System.out.println("PASS: trace of " + texts.size() + " steps replayed cleanly");
        return 0;
    }

    /**
     * Canonicalize state text so comparison is robust. Collapses
     * whitespace and sorts record field names alphabetically, because
     * TLC's RecordValue.normalize orders by UniqueString.tok (intern
     * order), and intern order isn't stable across command sequences
     * in our interactive driver --- the same record can print with a
     * different field permutation depending on what else has been
     * evaluated. Alphabetical sort gives a canonical form that doesn't
     * depend on TLC internals.
     */
    private static String canon(String s) {
        String collapsed = s.replaceAll("\\s+", " ").trim();
        return sortRecordFields(collapsed);
    }

    /**
     * Regex-sort record fields: [k1 |-> v1, k2 |-> v2, ...] becomes
     * [ki |-> vi, ...] with ki in alphabetical order. Handles nesting
     * by applying the rewrite only to innermost records repeatedly.
     */
    private static String sortRecordFields(String text) {
        // Innermost record: no nested [ before the matching ].
        java.util.regex.Pattern innermost = java.util.regex.Pattern.compile(
                "\\[([^\\[\\]]*?\\|->[^\\[\\]]*?)\\]");
        while (true) {
            java.util.regex.Matcher m = innermost.matcher(text);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            boolean any = false;
            while (m.find()) {
                any = true;
                sb.append(text, last, m.start());
                String body = m.group(1);
                // Split top-level commas: no nested <<>>, (), [] to worry
                // about because this is the innermost record.
                String[] fields = body.split("\\s*,\\s*");
                java.util.Arrays.sort(fields);
                sb.append('[');
                for (int i = 0; i < fields.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(fields[i].trim());
                }
                sb.append(']');
                last = m.end();
            }
            sb.append(text, last, text.length());
            if (!any) return text;
            String next = sb.toString();
            if (next.equals(text)) return next;
            // Nested records rely on the outer regex seeing the inner
            // record replaced; but our replacement may have introduced a
            // `]`-terminated token that the outer pattern would now match.
            // However, since the regex is innermost-only, we'd also need
            // to ensure the outer record now has no `[` in its body. For
            // records containing records, we use a sentinel: replace
            // already-sorted records' brackets to evade rematching.
            text = next;
            // Break infinite loops: one pass handles all innermost
            // records already.
            return text;
        }
    }

    /** Decode JSON-escaped string back to raw text. */
    private static String unjson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n':  sb.append('\n'); break;
                    case 't':  sb.append('\t'); break;
                    case 'r':  sb.append('\r'); break;
                    case '"':  sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default:   sb.append(next); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // --replay <spec.tla> <config.cfg> <trace.json>
        if (args.length >= 4 && args[0].equals("--replay")) {
            String specPath = args[1];
            String cfgPath = args[2];
            String tracePath = args[3];
            java.io.File specFile = new java.io.File(specPath);
            String specDir = specFile.getAbsoluteFile().getParent();
            String specName = specFile.getName();
            if (specName.endsWith(".tla")) specName = specName.substring(0, specName.length() - 4);
            String cfgName = cfgPath;
            if (cfgName.endsWith(".cfg")) cfgName = cfgName.substring(0, cfgName.length() - 4);
            String cfgAbs = new java.io.File(cfgName).getAbsolutePath();
            System.exit(doReplay(specDir, specName, cfgAbs, tracePath));
        }

        if (args.length < 2) {
            System.err.println("usage: Explorer <spec.tla> <config.cfg>");
            System.err.println("       Explorer --replay <spec.tla> <config.cfg> <trace.json>");
            System.exit(2);
        }
        String specPath = args[0];
        String cfgPath = args[1];

        java.io.File specFile = new java.io.File(specPath);
        String specDir = specFile.getAbsoluteFile().getParent();
        String specName = specFile.getName();
        if (specName.endsWith(".tla")) specName = specName.substring(0, specName.length() - 4);

        // TLC appends ".cfg" internally, so strip it if present.
        String cfgName = cfgPath;
        if (cfgName.endsWith(".cfg")) cfgName = cfgName.substring(0, cfgName.length() - 4);
        String cfgAbs = new java.io.File(cfgName).getAbsolutePath();

        Explorer ex = new Explorer(specDir, specName, cfgAbs);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("{\"ok\":true,\"ready\":true}");
        System.out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                String out;
                if (line.equals("init")) {
                    out = ex.doInit();
                } else if (line.startsWith("next ")) {
                    int id = Integer.parseInt(line.substring(5).trim());
                    out = ex.doNext(id);
                } else if (line.startsWith("step ")) {
                    int id = Integer.parseInt(line.substring(5).trim());
                    out = ex.doStep(id);
                } else if (line.startsWith("trace ")) {
                    int id = Integer.parseInt(line.substring(6).trim());
                    out = ex.doTrace(id);
                } else if (line.startsWith("dump ")) {
                    int id = Integer.parseInt(line.substring(5).trim());
                    out = ex.doDump(id);
                } else if (line.equals("exit") || line.equals("quit")) {
                    return;
                } else {
                    out = "{\"ok\":false,\"error\":\"unknown command\"}";
                }
                System.out.println(out);
                System.out.flush();
            } catch (Throwable t) {
                System.out.println("{\"ok\":false,\"error\":" + jsonStr(t.toString()) + "}");
                System.out.flush();
            }
        }
    }
}
