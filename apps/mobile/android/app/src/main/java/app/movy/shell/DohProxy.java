package app.movy.shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fetches body-less requests (GET/HEAD/OPTIONS) for configured hosts by connecting to a DoH-resolved address, so
 * sites keep working when the network's resolver poisons a name they depend on. TLS still verifies the real host name.
 */
final class DohProxy {
    /** A fully buffered upstream response. */
    static final class Reply {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        Reply(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        InputStream stream() {
            return new ByteArrayInputStream(body);
        }
    }

    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 15000;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final String[] DROP_REQUEST = {"host", "accept-encoding", "connection", "content-length",
            "cookie", "transfer-encoding", "upgrade-insecure-requests"};

    private final DohResolver resolver = new DohResolver();

    Reply fetch(String method, String host, String path, Map<String, String> requestHeaders) throws IOException {
        List<InetAddress> addrs = resolver.resolve(host);
        IOException last = null;
        for (InetAddress addr : addrs) {
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(addr, 443), CONNECT_MS);
                raw.setSoTimeout(READ_MS);
                return exchange(raw, method, host, path, requestHeaders);
            } catch (IOException e) {
                last = e;
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        throw last != null ? last : new IOException("no address");
    }

    private static Reply exchange(Socket raw, String method, String host, String path, Map<String, String> headers)
            throws IOException {
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
        try {
            tls.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                throw new SSLPeerUnverifiedException("hostname verification failed");
            }
            OutputStream out = tls.getOutputStream();
            out.write(buildRequest(method, host, path, headers).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return parse(readAll(tls.getInputStream()), "HEAD".equals(method));
        } finally {
            tls.close();
        }
    }

    static String buildRequest(String method, String host, String path, Map<String, String> headers) {
        StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (isDropped(h.getKey()) || h.getValue().indexOf('\r') >= 0 || h.getValue().indexOf('\n') >= 0) continue;
            req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        return req.append("\r\n").toString();
    }

    private static boolean isDropped(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String d : DROP_REQUEST) if (d.equals(n)) return true;
        return false;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) throw new IOException("response too large");
        }
        return out.toByteArray();
    }

    /** Parses a close-delimited HTTP/1.x response (identity or chunked body). */
    static Reply parse(byte[] raw, boolean head) throws IOException {
        int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        if (split < 0) throw new IOException("bad response");
        String[] lines = new String(raw, 0, split, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] status = lines[0].split(" ");
        if (status.length < 2 || !status[0].startsWith("HTTP/")) throw new IOException("bad status line");
        int code;
        try {
            code = Integer.parseInt(status[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) headers.put(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim());
        }
        byte[] body = new byte[0];
        if (!head) {
            int start = split + 4;
            if (isChunked(headers)) {
                body = dechunk(raw, start);
            } else {
                body = new byte[raw.length - start];
                System.arraycopy(raw, start, body, 0, body.length);
            }
        }
        return new Reply(code, headers, body);
    }

    private static boolean isChunked(Map<String, String> headers) {
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase("transfer-encoding") && h.getValue().toLowerCase(Locale.ROOT).contains("chunked")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] dechunk(byte[] raw, int start) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int pos = start;
        while (true) {
            int eol = indexOf(raw, crlf, pos);
            if (eol < 0) throw new IOException("bad chunk");
            String size = new String(raw, pos, eol - pos, StandardCharsets.ISO_8859_1);
            int semi = size.indexOf(';');
            int len;
            try {
                len = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size");
            }
            pos = eol + 2;
            if (len == 0) return out.toByteArray();
            if (pos + len > raw.length) throw new IOException("short chunk");
            out.write(raw, pos, len);
            pos += len + 2;
        }
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        for (int i = from; i <= hay.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && hay[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }
}
