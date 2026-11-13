package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;

import org.junit.Test;

public class OrientationPolicyTest {
    @Test public void phoneIsPortraitInTheAppAndLandscapeInFullscreen() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationPolicy.forState(true, false));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationPolicy.forState(true, true));
    }

    @Test public void tabletFollowsTheUsersRotationSetting() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, OrientationPolicy.forState(false, false));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, OrientationPolicy.forState(false, true));
    }

    @Test public void phoneThresholdIs600dp() {
        assertTrue(OrientationPolicy.isPhone(411));
        assertTrue(OrientationPolicy.isPhone(599));
        assertFalse(OrientationPolicy.isPhone(600));
        assertFalse(OrientationPolicy.isPhone(800));
    }
}
