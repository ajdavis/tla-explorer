import tlc2.tool.Action;
import tlc2.tool.ITool;
import tlc2.tool.StateVec;
import tlc2.tool.TLCState;
import tlc2.tool.impl.FastTool;
import tlc2.value.impl.BoolValue;
import tlc2.value.impl.FcnRcdValue;
import tlc2.value.impl.IntValue;
import tlc2.value.impl.IntervalValue;
import tlc2.value.impl.ModelValue;
import tlc2.value.impl.RecordValue;
import tlc2.value.impl.SetEnumValue;
import tlc2.value.impl.StringValue;
import tlc2.value.impl.TupleValue;
import tlc2.value.impl.Value;
import tlc2.value.impl.ValueVec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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
              .append(",\"state\":").append(stateJson(s))
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
              .append(",\"state\":").append(stateJson(s))
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
              .append(",\"state\":").append(stateJson(states.get(id)))
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
              .append(",\"state\":").append(stateJson(t.state))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String stateJson(TLCState s) {
        Value r = new RecordValue(s);
        r.deepNormalize();
        return valueToJson(r);
    }

    private static String valueToJson(Value v) {
        if (v instanceof IntValue)
            return String.valueOf(((IntValue) v).val);
        if (v instanceof StringValue)
            return jsonStr(((StringValue) v).val.toString());
        if (v instanceof BoolValue)
            return ((BoolValue) v).val ? "true" : "false";
        if (v instanceof ModelValue)
            return "{\"$mv\":" + jsonStr(((ModelValue) v).val.toString()) + "}";
        if (v instanceof RecordValue) {
            RecordValue r = (RecordValue) v;
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < r.names.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(jsonStr(r.names[i].toString())).append(":").append(valueToJson(r.values[i]));
            }
            return sb.append("}").toString();
        }
        if (v instanceof TupleValue) {
            TupleValue t = (TupleValue) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < t.elems.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(valueToJson(t.elems[i]));
            }
            return sb.append("]").toString();
        }
        if (v instanceof FcnRcdValue) {
            FcnRcdValue f = (FcnRcdValue) v;
            if (f.intv != null) {
                if (f.intv.low == 1) {
                    // Sequence: domain is 1..n, serialize as JSON array.
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < f.values.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(valueToJson(f.values[i]));
                    }
                    return sb.append("]").toString();
                }
                // Integer-range function not starting at 1.
                StringBuilder sb = new StringBuilder("{\"$fn\":[");
                for (int i = 0; i < f.values.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("[").append(f.intv.low + i).append(",")
                      .append(valueToJson(f.values[i])).append("]");
                }
                return sb.append("]}").toString();
            }
            // General function with explicit domain array.
            StringBuilder sb = new StringBuilder("{\"$fn\":[");
            for (int i = 0; i < f.domain.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("[").append(valueToJson(f.domain[i])).append(",")
                  .append(valueToJson(f.values[i])).append("]");
            }
            return sb.append("]}").toString();
        }
        if (v instanceof IntervalValue) {
            IntervalValue iv = (IntervalValue) v;
            return "{\"$interval\":[" + iv.low + "," + iv.high + "]}";
        }
        if (v instanceof SetEnumValue) {
            SetEnumValue s = (SetEnumValue) v;
            ValueVec elems = s.elems;
            StringBuilder sb = new StringBuilder("{\"$set\":[");
            for (int i = 0; i < elems.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(valueToJson(elems.elementAt(i)));
            }
            return sb.append("]}").toString();
        }
        // Unknown value type: fall back to the TLA+ string representation.
        return jsonStr(v.toString());
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
     * Dump format: JSON object with "dump" array of {action, state}.
     * States are matched by deep-normalized JSON equality (Gson
     * JsonElement.equals: order-insensitive for objects, order-sensitive
     * for arrays).
     */
    public static int doReplay(String specDir, String specName, String cfgAbs,
                                String tracePath) throws Exception {
        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(tracePath)));
        JsonArray dump = JsonParser.parseString(raw)
                .getAsJsonObject().getAsJsonArray("dump");
        List<String> actions = new ArrayList<>();
        List<JsonElement> wantStates = new ArrayList<>();
        for (JsonElement entry : dump) {
            JsonObject obj = entry.getAsJsonObject();
            actions.add(obj.get("action").getAsString());
            wantStates.add(obj.get("state"));
        }
        if (wantStates.isEmpty()) {
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
            if (JsonParser.parseString(stateJson(s)).equals(wantStates.get(0))) {
                current = s;
                break;
            }
        }
        if (current == null) {
            System.out.println("FAIL: no init state matches trace[0]");
            return 1;
        }
        System.out.println("OK  step 0 <init>");

        Action[] allActions = tool.getActions();
        for (int i = 1; i < wantStates.size(); i++) {
            String wantAction = actions.get(i);
            JsonElement wantState = wantStates.get(i);
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
                    if (JsonParser.parseString(stateJson(s)).equals(wantState)) {
                        match = s;
                        break;
                    }
                }
                if (match != null) break;
            }
            if (match == null) {
                System.out.println("FAIL step " + i + " " + wantAction
                        + ": no successor matched trace state"
                        + " (searched " + totalSuccs + " candidates across "
                        + matchingActions.size() + " action instances)");
                return 1;
            }
            System.out.println("OK  step " + i + " " + wantAction);
            current = match;
        }
        System.out.println("PASS: trace of " + wantStates.size() + " steps replayed cleanly");
        return 0;
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
