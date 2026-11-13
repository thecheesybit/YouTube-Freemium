package app.movy.shell;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * One 5 s idle timer for the whole TV UI. On idle the native views hide and the page is told ({@code __movyTv.idle(true)}
 * in every frame); the first key afterwards only wakes (see {@link IdleGate}).
 */
final class TvIdle {
    interface Host {
        /** Hide or show Island, Settings, hints and the cursor. Main thread. */
        void setNativeIdle(boolean idle);

        /** Tell the page (all frames). Main thread. */
        void setPageIdle(boolean idle);

        /** Chooser or the error panel is up: idle must not blank the screen. */
        boolean idleSuspended();
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IdleGate gate;
    private final Runnable check = this::check;

    TvIdle(Host host) {
        this.host = host;
        this.gate = new IdleGate(IdleGate.IDLE_MS, SystemClock.elapsedRealtime());
        arm();
    }

    boolean isIdle() {
        return gate.isIdle();
    }

    /** True when the event must not reach the app (it only woke the screen). */
    boolean onKey(KeyEvent e) {
        boolean[] woke = new boolean[1];
        IdleGate.Decision d = gate.onKey(e.getAction() == KeyEvent.ACTION_DOWN, e.getKeyCode(), SystemClock.elapsedRealtime(), woke);
        if (woke[0]) wake();
        arm();
        return d == IdleGate.Decision.SWALLOW;
    }

    /** Background: no timer may run. */
    void suspend() {
        main.removeCallbacks(check);
    }

    /** Foreground again: start counting from now. */
    void resume() {
        gate.touch(SystemClock.elapsedRealtime());
        arm();
    }

    /** Native UI came up by itself (a page loaded, a panel opened): restart the countdown. */
    void touch() {
        gate.touch(SystemClock.elapsedRealtime());
        arm();
    }

    private void wake() {
        host.setNativeIdle(false);
        host.setPageIdle(false);
    }

    private void arm() {
        main.removeCallbacks(check);
        if (gate.isIdle()) return;
        main.postDelayed(check, Math.max(50, gate.dueAt() - SystemClock.elapsedRealtime()));
    }

    private void check() {
        if (host.idleSuspended()) {
            gate.touch(SystemClock.elapsedRealtime());
            arm();
            return;
        }
        if (gate.tick(SystemClock.elapsedRealtime())) {
            host.setNativeIdle(true);
            host.setPageIdle(true);
        } else {
            arm();
        }
    }
}
