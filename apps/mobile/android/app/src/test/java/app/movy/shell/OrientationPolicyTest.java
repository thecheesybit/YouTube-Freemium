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

    @Test public void fromDegrees_classifiesAnglesCorrectly() {
        assertEquals(OrientationPolicy.DeviceOrientation.PORTRAIT, OrientationPolicy.fromDegrees(0));
        assertEquals(OrientationPolicy.DeviceOrientation.PORTRAIT, OrientationPolicy.fromDegrees(20));
        assertEquals(OrientationPolicy.DeviceOrientation.PORTRAIT, OrientationPolicy.fromDegrees(350));

        assertEquals(OrientationPolicy.DeviceOrientation.REVERSE_LANDSCAPE, OrientationPolicy.fromDegrees(90));
        assertEquals(OrientationPolicy.DeviceOrientation.REVERSE_PORTRAIT, OrientationPolicy.fromDegrees(180));
        assertEquals(OrientationPolicy.DeviceOrientation.LANDSCAPE, OrientationPolicy.fromDegrees(270));

        assertEquals(OrientationPolicy.DeviceOrientation.UNKNOWN, OrientationPolicy.fromDegrees(-1));
    }

    @Test public void isLandscapeAndPortrait() {
        assertTrue(OrientationPolicy.isLandscape(90));
        assertTrue(OrientationPolicy.isLandscape(270));
        assertFalse(OrientationPolicy.isLandscape(0));

        assertTrue(OrientationPolicy.isPortrait(0));
        assertTrue(OrientationPolicy.isPortrait(180));
        assertFalse(OrientationPolicy.isPortrait(270));
    }
}
