import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tla2sany.semantic.OpDeclNode;
import tla2sany.semantic.SymbolNode;
import tlc2.tool.Action;
import tlc2.tool.ITool;
import tlc2.tool.StateVec;
import tlc2.tool.TLCState;
import tlc2.util.Context;
import tlc2.value.IValue;
import util.UniqueString;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplorerTest {
    /** Minimal concrete TLCState for use in tests. Only toString() is needed. */
    private static final TLCState STUB_STATE = new TLCState() {
        @Override public String toString() { return "x = 1"; }
        @Override public String toString(TLCState s) { return "x = 1"; }
        @Override public TLCState bind(UniqueString n, IValue v) { throw new UnsupportedOperationException(); }
        @Override public TLCState bind(SymbolNode n, IValue v) { throw new UnsupportedOperationException(); }
        @Override public TLCState unbind(UniqueString n) { throw new UnsupportedOperationException(); }
        @Override public IValue lookup(UniqueString n) { throw new UnsupportedOperationException(); }
        @Override public boolean containsKey(UniqueString n) { throw new UnsupportedOperationException(); }
        @Override public TLCState copy() { throw new UnsupportedOperationException(); }
        @Override public TLCState deepCopy() { throw new UnsupportedOperationException(); }
        @Override public StateVec addToVec(StateVec v) { throw new UnsupportedOperationException(); }
        @Override public void deepNormalize() { throw new UnsupportedOperationException(); }
        @Override public long fingerPrint() { throw new UnsupportedOperationException(); }
        @Override public boolean allAssigned() { return true; }
        @Override public Set<OpDeclNode> getUnassigned() { throw new UnsupportedOperationException(); }
        @Override public TLCState createEmpty() { throw new UnsupportedOperationException(); }
    };

    private ITool mockTool;
    private Explorer explorer;

    @BeforeEach
    void setUp() {
        mockTool = mock(ITool.class);

        StateVec initVec = new StateVec(1);
        initVec.addElement(STUB_STATE);
        when(mockTool.getInitStates()).thenReturn(initVec);

        explorer = new Explorer(mockTool);
        // Populate state 0 so doNext(0) can find it.
        explorer.doInit();
    }

    /**
     * If getNextStates throws, rawSuccessors must propagate the exception
     * instead of silently swallowing it and returning an empty transition list.
     */
    @Test
    void doNext_propagatesExceptionFromGetNextStates() {
        // Action is final; construct one with null pred since the mock tool
        // throws before TLC ever reads the Action's fields.
        Action action = new Action(null, Context.Empty);
        when(mockTool.getActions()).thenReturn(new Action[]{action});
        RuntimeException boom = new RuntimeException("test explosion from getNextStates");
        when(mockTool.getNextStates(any(Action.class), any(TLCState.class))).thenThrow(boom);

        assertThrows(RuntimeException.class, () -> explorer.doNext(0));
    }
}
