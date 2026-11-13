package app.movy.shell;

import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** TV is always immersive: no system bars, ever. Re-applied on resume, focus, dialogs, IME close and fullscreen exit. */
final class TvImmersive {
    private TvImmersive() {}

    static void apply(Window w) {
        if (w == null) return;
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }
}
