package app.movy.shell;

import java.util.List;
import java.util.function.Function;

/** Pure health rules: classify a probe result and pick a failover suggestion. Unit tested. */
final class HealthClassifier {
    static final long UP_MAX_MS = 1500;
    static final long SLOW_MAX_MS = 5000;

    private HealthClassifier() {}

    /**
     * @param httpStatus HTTP status, or a value {@code <= 0} when no response arrived (error or timeout)
     * @param elapsedMs  time to the response head
     */
    static HealthChecker.Status classify(int httpStatus, long elapsedMs) {
        if (httpStatus <= 0 || httpStatus >= 500) return HealthChecker.Status.DOWN;
        if (elapsedMs <= UP_MAX_MS) return HealthChecker.Status.UP;
        if (elapsedMs <= SLOW_MAX_MS) return HealthChecker.Status.SLOW;
        return HealthChecker.Status.DOWN;
    }

    /** HEAD is retried as a ranged GET when the site refuses or does not implement it. */
    static boolean headNeedsFallback(int headStatus) {
        return headStatus <= 0 || headStatus == 405 || headStatus == 501 || headStatus == 400;
    }

    /** First UP source in display order (excluding {@code excludeId}), else the first SLOW one, else null. */
    static Source suggest(List<Source> ordered, Function<String, HealthChecker.Status> status, String excludeId) {
        Source slow = null;
        for (Source s : ordered) {
            if (s.id.equals(excludeId)) continue;
            HealthChecker.Status st = status.apply(s.id);
            if (st == HealthChecker.Status.UP) return s;
            if (st == HealthChecker.Status.SLOW && slow == null) slow = s;
        }
        return slow;
    }
}
