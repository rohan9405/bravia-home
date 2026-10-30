package io.github.rohan9405.feed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Plain HTTPS GETs with short timeouts and a size cap, so one slow poster host cannot stall a refresh. */
final class Net {
    private static final int TIMEOUT_MS = 8000;

    private Net() {
    }

    static byte[] get(String url, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CleanYourTV-Feed/1.0");
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return read(in, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    /** A GET whose error answers matter too: the status and the body, whatever the status. */
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    static Response request(String url, String header, String value, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "CleanYourTV-Feed/1.0");
        if (header != null) c.setRequestProperty(header, value);
        try {
            int code = c.getResponseCode();
            InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return new Response(code, "");
            try (InputStream s = in) {
                return new Response(code, new String(read(s, maxBytes), java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] read(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("too large");
        }
        return out.toByteArray();
    }
}
