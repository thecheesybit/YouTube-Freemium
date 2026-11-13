package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import app.movy.shell.HealthChecker.Status;

public class HealthClassifierTest {
    private static Source src(String id) {
        return new Source(id, id, "https://" + id + ".example/", Collections.singletonList(id + ".example"), 0xFF000000, true);
    }

    private static final List<Source> ORDER = Arrays.asList(src("a"), src("b"), src("c"), src("d"));

    private static java.util.function.Function<String, Status> statuses(Object... kv) {
        Map<String, Status> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Status) kv[i + 1]);
        return id -> m.getOrDefault(id, Status.UNKNOWN);
    }

    @Test public void fastIsUp() { assertEquals(Status.UP, HealthClassifier.classify(200, 300)); }
    @Test public void redirectIsUp() { assertEquals(Status.UP, HealthClassifier.classify(302, 1500)); }
    @Test public void clientErrorIsUp() { assertEquals(Status.UP, HealthClassifier.classify(403, 800)); }
    @Test public void slowResponse() { assertEquals(Status.SLOW, HealthClassifier.classify(200, 1501)); }
    @Test public void slowUpperBound() { assertEquals(Status.SLOW, HealthClassifier.classify(200, 5000)); }
    @Test public void tooSlowIsDown() { assertEquals(Status.DOWN, HealthClassifier.classify(200, 5001)); }
    @Test public void serverErrorIsDown() { assertEquals(Status.DOWN, HealthClassifier.classify(503, 100)); }
    @Test public void noResponseIsDown() { assertEquals(Status.DOWN, HealthClassifier.classify(0, 100)); }

    @Test public void headFallback() {
        assertTrue(HealthClassifier.headNeedsFallback(405));
        assertTrue(HealthClassifier.headNeedsFallback(0));
        assertFalse(HealthClassifier.headNeedsFallback(200));
        assertFalse(HealthClassifier.headNeedsFallback(403));
    }

    @Test public void suggestsFirstUpInOrder() {
        Source s = HealthClassifier.suggest(ORDER, statuses("b", Status.SLOW, "c", Status.UP, "d", Status.UP), "a");
        assertEquals("c", s.id);
    }

    @Test public void excludesTheActiveSource() {
        Source s = HealthClassifier.suggest(ORDER, statuses("a", Status.UP, "b", Status.UP), "a");
        assertEquals("b", s.id);
    }

    @Test public void fallsBackToFirstSlow() {
        Source s = HealthClassifier.suggest(ORDER, statuses("b", Status.DOWN, "c", Status.SLOW, "d", Status.SLOW), "a");
        assertEquals("c", s.id);
    }

    @Test public void nothingWhenAllDownOrUnknown() {
        assertNull(HealthClassifier.suggest(ORDER, statuses("b", Status.DOWN, "c", Status.UNKNOWN), "a"));
    }
}
