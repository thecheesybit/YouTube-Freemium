package app.movy.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FullscreenPolicyTest {
    @Test public void reattachWhenViewKeptThePortraitSize() {
        assertTrue(FullscreenPolicy.needsReattach(1440, 3168, 3168, 1440));
        assertTrue(FullscreenPolicy.needsReattach(3168, 1440, 3168, 1000));
    }

    @Test public void noReattachWhenSizesMatchOrUnmeasured() {
        assertFalse(FullscreenPolicy.needsReattach(3168, 1440, 3168, 1440));
        assertFalse(FullscreenPolicy.needsReattach(0, 0, 0, 0));
        assertFalse(FullscreenPolicy.needsReattach(100, 100, 0, 1440));
    }
}
