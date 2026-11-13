package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LeanPolicyTest {
    @Test public void httpCacheIsTrimmedOnlyAboveTheLimit() {
        assertFalse(LeanPolicy.shouldTrimHttpCache(0));
        assertFalse(LeanPolicy.shouldTrimHttpCache(100L * 1024 * 1024));
        assertTrue(LeanPolicy.shouldTrimHttpCache(100L * 1024 * 1024 + 1));
    }

    @Test public void backgroundDestroysAfterSixtySeconds() {
        assertFalse(LeanPolicy.backgroundTimeoutReached(1000, 60_999));
        assertTrue(LeanPolicy.backgroundTimeoutReached(1000, 61_000));
    }

    @Test public void trimMemoryLevels() {
        assertEquals(LeanPolicy.TrimAction.NONE, LeanPolicy.onTrimMemory(5));
        assertEquals(LeanPolicy.TrimAction.RELEASE_RAM, LeanPolicy.onTrimMemory(10));
        assertEquals(LeanPolicy.TrimAction.RELEASE_RAM, LeanPolicy.onTrimMemory(15));
        assertEquals(LeanPolicy.TrimAction.BACKGROUND, LeanPolicy.onTrimMemory(20));
        assertEquals(LeanPolicy.TrimAction.RELEASE_RAM, LeanPolicy.onTrimMemory(60));
    }
}
