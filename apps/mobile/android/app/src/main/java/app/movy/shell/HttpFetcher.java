package app.movy.shell;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link Fetcher} over HttpURLConnection. No Accept-Encoding is sent on purpose: the platform adds gzip and decodes it. */
final class HttpFetcher implements Fetcher {
    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 15000;
    private static final String[] FORWARD = {"User-Agent", "Accept", "Accept-Language", "Referer", "Origin"};

    @Override
    public Result fetch(String url, Map<String, String> req, String ifNoneMatch, String ifModifiedSince) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setInstanceFollowRedirects(true);
        for (String h : FORWARD) {
            String v = CachePolicy.header(req, h);
            if (v != null) c.setRequestProperty(h, v);
        }
        if (ifNoneMatch != null) c.setRequestProperty("If-None-Match", ifNoneMatch);
        if (ifModifiedSince != null) c.setRequestProperty("If-Modified-Since", ifModifiedSince);
        int status = c.getResponseCode();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            headers.put(e.getKey(), String.join(", ", e.getValue()));
        }
        java.io.InputStream body = status == 200 ? c.getInputStream() : null;
        return new Result(status, headers, body, c::disconnect);
    }
}
