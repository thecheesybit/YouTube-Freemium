package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class CookiePersistenceTest {
    @Test
    public void missingReturnsOnlyAbsentNames() {
        assertEquals(Arrays.asList("sid=abc"), CookiePersistence.missing("pers=1", "sid=abc; pers=1"));
        assertTrue(CookiePersistence.missing("a=1; b=2", "a=1; b=2").isEmpty());
        assertEquals(Arrays.asList("a=1", "b=2"), CookiePersistence.missing(null, "a=1; b=2"));
        assertTrue(CookiePersistence.missing("a=1", null).isEmpty());
    }

    @Test
    public void valuesMayContainEqualsSigns() {
        assertEquals(Collections.singletonList("tok=a=b=="), CookiePersistence.missing("", "tok=a=b=="));
    }

    @Test
    public void originKeepsSchemeAndHostOnly() {
        assertEquals("https://x.example.com", CookiePersistence.origin("https://x.example.com/a/b?q=1"));
        assertNull(CookiePersistence.origin("about:blank"));
        assertNull(CookiePersistence.origin(null));
    }
}
