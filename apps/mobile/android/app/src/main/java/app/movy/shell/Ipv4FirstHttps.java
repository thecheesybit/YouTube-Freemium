package app.movy.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal HTTPS client that tries IPv4 addresses first, with short connect timeouts. HttpURLConnection walks every
 * AAAA address in order, so on a network whose IPv6 is black-holed it stalls for minutes (the WebView hides this with
 * happy eyeballs). Small JSON calls only: HTTP/1.0, no chunking, no redirects.
 */
public final class Ipv4FirstHttps {
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final int MAX_BODY = 512 * 1024;

    private Ipv4FirstHttps() {}

    public static Response request(String method, String host, String path, Map<String, String> headers, String body,
                                   int connectTimeoutMs, int readTimeoutMs) throws IOException {
        IOException last = null;
        for (InetAddress addr : ipv4First(InetAddress.getAllByName(host))) {
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(addr, 443), connectTimeoutMs);
                raw.setSoTimeout(readTimeoutMs);
                return exchange(raw, method, host, path, headers, body);
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

    /** Probe: sends the request and returns only the HTTP status from the status line (the body is never read). */
    public static int status(String method, String host, String path, Map<String, String> headers,
                             int connectTimeoutMs, int readTimeoutMs) throws IOException {
        IOException last = null;
        for (InetAddress addr : ipv4First(InetAddress.getAllByName(host))) {
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(addr, 443), connectTimeoutMs);
                raw.setSoTimeout(readTimeoutMs);
                return statusExchange(raw, method, host, path, headers);
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

    private static int statusExchange(Socket raw, String method, String host, String path, Map<String, String> headers)
            throws IOException {
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
        try {
            tls.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                throw new SSLPeerUnverifiedException("hostname verification failed");
            }
            StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(host).append("\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            req.append("\r\n");
            OutputStream out = tls.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return parseStatusLine(tls.getInputStream());
        } finally {
            tls.close();
        }
    }

    /** Reads up to the first line feed and returns the status code. */
    static int parseStatusLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n' && line.length() < 256) line.append((char) c);
        String[] parts = line.toString().trim().split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("bad status line");
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status");
        }
    }

    static List<InetAddress> ipv4First(InetAddress[] all) {
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : all) if (a.getAddress().length == 4) out.add(a);
        for (InetAddress a : all) if (a.getAddress().length != 4) out.add(a);
        return out;
    }

    private static Response exchange(Socket raw, String method, String host, String path, Map<String, String> headers,
                                     String body) throws IOException {
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
        try {
            tls.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
                throw new SSLPeerUnverifiedException("hostname verification failed");
            }
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(host).append("\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            if (body != null) req.append("Content-Length: ").append(payload.length).append("\r\n");
            req.append("\r\n");
            OutputStream out = tls.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            return parse(readAll(tls.getInputStream()));
        } finally {
            tls.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) throw new IOException("response too large");
        }
        return out.toByteArray();
    }

    static Response parse(byte[] raw) throws IOException {
        String text = new String(raw, StandardCharsets.UTF_8);
        int split = text.indexOf("\r\n\r\n");
        if (split < 0 || !text.startsWith("HTTP/")) throw new IOException("bad response");
        String[] statusLine = text.substring(0, text.indexOf("\r\n")).split(" ");
        if (statusLine.length < 2) throw new IOException("bad status line");
        int status;
        try {
            status = Integer.parseInt(statusLine[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status");
        }
        return new Response(status, text.substring(split + 4));
    }
}
