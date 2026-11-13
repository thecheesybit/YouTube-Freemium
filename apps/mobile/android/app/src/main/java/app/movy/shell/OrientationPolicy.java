package app.movy.shell;

import android.content.pm.ActivityInfo;

/**
 * Which orientation the activity requests. Phones (smallest width below 600dp): portrait everywhere except fullscreen
 * video, which is sensor-landscape (both landscape sides) whatever the device's rotation lock says; the site's own
 * screen.orientation.lock is ignored. Tablets follow the user's rotation setting (unspecified; FULL_USER in
 * fullscreen, which still respects the lock). TV keeps its manifest orientation (callers skip the policy there).
 */
final class OrientationPolicy {
    private OrientationPolicy() {}

    enum DeviceOrientation { PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE, REVERSE_PORTRAIT, UNKNOWN }

    static boolean isPhone(int smallestWidthDp) {
        return smallestWidthDp < UiTokens.COMPACT_MAX_SW_DP;
    }

    static int forState(boolean isPhone, boolean fullscreen) {
        if (isPhone) {
            return fullscreen ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        return fullscreen ? ActivityInfo.SCREEN_ORIENTATION_FULL_USER : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    static DeviceOrientation fromDegrees(int rotationDegrees) {
        if (rotationDegrees < 0) return DeviceOrientation.UNKNOWN;
        if (rotationDegrees >= 315 || rotationDegrees < 45) return DeviceOrientation.PORTRAIT;
        if (rotationDegrees >= 45 && rotationDegrees < 135) return DeviceOrientation.REVERSE_LANDSCAPE;
        if (rotationDegrees >= 135 && rotationDegrees < 225) return DeviceOrientation.REVERSE_PORTRAIT;
        if (rotationDegrees >= 225 && rotationDegrees < 315) return DeviceOrientation.LANDSCAPE;
        return DeviceOrientation.UNKNOWN;
    }

    static boolean isLandscape(int degrees) {
        DeviceOrientation o = fromDegrees(degrees);
        return o == DeviceOrientation.LANDSCAPE || o == DeviceOrientation.REVERSE_LANDSCAPE;
    }

    static boolean isPortrait(int degrees) {
        DeviceOrientation o = fromDegrees(degrees);
        return o == DeviceOrientation.PORTRAIT || o == DeviceOrientation.REVERSE_PORTRAIT;
    }
}
