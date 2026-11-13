package app.movy.shell;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Network access of the asset cache (replaced by a fake in tests). */
interface Fetcher {
    final class Result implements java.io.Closeable {
        final int status;
        /** Response headers, one value per name (multiple values joined with ", "). */
        final Map<String, String> headers;
        final InputStream body;
        private final Runnable onClose;

        Result(int status, Map<String, String> headers, InputStream body, Runnable onClose) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.onClose = onClose;
        }

        @Override
        public void close() {
            try {
                if (body != null) body.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            if (onClose != null) onClose.run();
        }
    }

    /** Conditional values are applied when non-null. */
    Result fetch(String url, Map<String, String> requestHeaders, String ifNoneMatch, String ifModifiedSince) throws IOException;
}
