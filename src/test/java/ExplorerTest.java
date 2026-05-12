import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

class ExplorerTest {

    // --- helpers ---

    private static Explorer explorerFor(String specName) {
        URL url = ExplorerTest.class.getClassLoader().getResource(specName + ".tla");
        File f = new File(url.getFile());
        String cfg = new File(f.getParent(), specName).getAbsolutePath();
        return new Explorer(f.getParent(), specName, cfg);
    }

    // --- exception-propagation tests ---

    /**
     * A Next action with infinite recursion throws StackOverflowError.
     * That is not an EvalException, so it must propagate instead of being
     * silently swallowed as "action not enabled".
     */
    @Test
    void doNext_propagatesStackOverflowError() {
        Explorer ex = explorerFor("StackOverflow");
        ex.doInit();
        assertThrows(StackOverflowError.class, () -> ex.doNext(0));
    }

    // --- EvalException / "action not enabled" tests ---

    /**
     * A Next action that always divides by zero throws EvalException.
     * That is the TLC signal for "action not enabled in this state", so
     * rawSuccessors catches it and skips the action: doNext returns no successors.
     */
    @Test
    void doNext_returnsEmptyTransitions_forDivByZeroAction() {
        Explorer ex = explorerFor("DivByZero");
        ex.doInit();
        String result = ex.doNext(0);
        assertTrue(result.contains("\"ok\":true"), result);
        assertTrue(result.contains("\"transitions\":[]"), result);
    }

}
