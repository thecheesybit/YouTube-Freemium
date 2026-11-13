package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class CacheIndexTest {
    @Test public void totalsFollowPutReplaceAndRemove() {
        CacheIndex i = new CacheIndex();
        i.put("a", 100, 1, 1);
        i.put("b", 50, 1, 2);
        assertEquals(150, i.totalBytes());
        i.put("a", 30, 1, 3); // replaced, not added
        assertEquals(80, i.totalBytes());
        assertTrue(i.remove("b"));
        assertFalse(i.remove("b"));
        assertEquals(30, i.totalBytes());
        assertEquals(1, i.count());
        i.clear();
        assertEquals(0, i.totalBytes());
    }

    @Test public void overflowListsLeastRecentlyUsedFirstUntilItFits() {
        CacheIndex i = new CacheIndex();
        i.put("old", 40, 1, 1);
        i.put("mid", 40, 1, 2);
        i.put("new", 40, 1, 3);
        i.touch("old", 4); // old is now the most recent
        assertEquals(Collections.emptyList(), i.overflow(120));
        assertEquals(Arrays.asList("mid"), i.overflow(100));
        assertEquals(Arrays.asList("mid", "new"), i.overflow(50));
        assertEquals("the index itself is untouched", 120, i.totalBytes());
    }

    @Test public void expiredUsesFirstStoreTimeNotLastAccess() {
        long day = CachePolicy.DAY_MS;
        CacheIndex i = new CacheIndex();
        i.put("a", 1, 1000, 1000);
        i.put("b", 1, 1000 + 20 * day, 1000 + 20 * day);
        i.touch("a", 1000 + 29 * day); // being used does not extend its life
        assertEquals(Arrays.asList("a"), i.expired(1000 + 30 * day, 30 * day));
        assertEquals(new java.util.HashSet<>(Arrays.asList("a", "b")), new java.util.HashSet<>(i.expired(1000 + 50 * day, 30 * day)));
    }
}
