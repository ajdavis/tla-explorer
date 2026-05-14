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

    @Test
    void doInit_serializesAllValueTypes() {
        Explorer ex = explorerFor("AllTypes");
        String result = ex.doInit();
        assertTrue(result.contains("\"ok\":true"), result);
        // Primitives
        assertTrue(result.contains("\"vi\":42"), result);
        assertTrue(result.contains("\"vs\":\"hello\""), result);
        assertTrue(result.contains("\"vb\":true"), result);
        // Record: fields sorted alphabetically by normalize()
        assertTrue(result.contains("\"vrcd\":{\"x\":1,\"y\":\"world\"}"), result);
        // Sequence
        assertTrue(result.contains("\"vseq\":[1,2,3]"), result);
        // Set
        assertTrue(result.contains("\"vset\":{\"$set\":[1,2,3]}"), result);
        // Model value
        assertTrue(result.contains("\"vmv\":{\"$mv\":\"mv1\"}"), result);
        // General function
        assertTrue(result.contains("\"vfcn\":{\"$fn\":[[2,20],[4,40]]}"), result);
        // Integer interval
        assertTrue(result.contains("\"vintv\":{\"$interval\":[1,3]}"), result);
    }

}
