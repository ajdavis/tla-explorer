import org.junit.jupiter.api.Test;
import tlc2.tool.EvalException;

import java.io.File;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

class ExplorerTest {

    private static Explorer explorerFor(String specName) {
        URL url = ExplorerTest.class.getClassLoader().getResource(specName + ".tla");
        File f = new File(url.getFile());
        String cfg = new File(f.getParent(), specName).getAbsolutePath();
        return new Explorer(f.getParent(), specName, cfg);
    }

    @Test
    void doNext_propagatesStackOverflowError() {
        Explorer ex = explorerFor("StackOverflow");
        ex.doInit();
        assertThrows(StackOverflowError.class, () -> ex.doNext(0));
    }

    @Test
    void doNext_propagatesEvalException_forDivByZeroAction() {
        Explorer ex = explorerFor("DivByZero");
        ex.doInit();
        assertThrows(EvalException.class, () -> ex.doNext(0));
    }

}
