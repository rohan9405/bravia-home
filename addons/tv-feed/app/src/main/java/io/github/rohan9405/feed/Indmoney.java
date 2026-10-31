package io.github.rohan9405.feed;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * INDmoney's official MCP server, read-only (portfolio:read market:read), as its own client: the TV holds the sign-in
 * and renews it itself, so nothing else (no computer, no other app) has to be on. The sign-in comes from the computer
 * once (my-tv/indmoney-pair.sh); after that the refresh token is traded for a new one at every renewal, and the new one
 * is saved before it is used. When INDmoney refuses it, the sign-in is marked as needing a reconnect.
 */
final class Indmoney {
    private static final String PREFS = "indmoney";
    private static final String MCP = "https://mcp.indmoney.com/mcp";
    private static final String TOKEN = "https://mcp.indmoney.com/token";
    private static final String RESOURCE = "https://mcp.indmoney.com/mcp";
    private static final int TIMEOUT_MS = 15000;

    /** INDmoney refused the sign-in: someone has to sign in again. */
    static final class ReconnectNeeded extends Exception {
        ReconnectNeeded(String why) {
            super(why);
        }
    }

    private final SharedPreferences prefs;
    private String session;

    Indmoney(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Stores a sign-in made on the computer: the registered client and its refresh token. */
    static void pair(Context ctx, String clientId, String clientSecret, String refreshToken) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
                .putString("client_id", clientId.trim()).putString("client_secret", clientSecret.trim())
                .putString("refresh_token", refreshToken.trim()).commit();
    }

    boolean paired() {
        return !prefs.getString("refresh_token", "").isEmpty();
    }

    boolean reconnectNeeded() {
        return prefs.getBoolean("reconnect", false);
    }

    /** One tool's answer: the JSON inside the tool result's text. */
    JSONObject tool(String name, JSONObject args) throws IOException, ReconnectNeeded {
        if (session == null) {
            call(new JSONObject(map("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", new JSONObject(map(
                    "protocolVersion", "2025-06-18", "capabilities", new JSONObject(),
                    "clientInfo", new JSONObject(map("name", "bravia-home", "version", "1")))))));
            call(new JSONObject(map("jsonrpc", "2.0", "method", "notifications/initialized")));
        }
        JSONObject res = call(new JSONObject(map("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", new JSONObject(map("name", name, "arguments", args)))));
        try {
            JSONObject result = res.getJSONObject("result");
            JSONArray content = result.getJSONArray("content");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < content.length(); i++) text.append(content.getJSONObject(i).optString("text"));
            if (result.optBoolean("isError")) throw new IOException(name + ": " + text);
            JSONObject outer = new JSONObject(text.toString());
            Object inner = outer.opt("result");
            return inner instanceof String ? new JSONObject((String) inner) : outer;
        } catch (JSONException e) {
            throw new IOException(name + ": unexpected answer", e);
        }
    }

    private JSONObject call(JSONObject body) throws IOException, ReconnectNeeded {
        String token = accessToken();
        HttpURLConnection c = open(MCP);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (session != null) c.setRequestProperty("Mcp-Session-Id", session);
        try {
            write(c, body.toString());
            int code = c.getResponseCode();
            if (code == 401) {
                prefs.edit().remove("access_token").apply();   // renewed on the next try
                throw new IOException("MCP 401");
            }
            if (code / 100 != 2) throw new IOException("MCP " + code);
            String sid = c.getHeaderField("mcp-session-id");
            if (sid != null) session = sid;
            String raw = read(c.getInputStream());
            if (raw.trim().isEmpty()) return new JSONObject();
            if (raw.startsWith("event:") || raw.startsWith("data:")) {
                StringBuilder data = new StringBuilder();
                for (String line : raw.split("\n")) if (line.startsWith("data:")) data.append(line.substring(5));
                raw = data.toString();
            }
            return new JSONObject(raw);
        } catch (JSONException e) {
            throw new IOException("MCP: not JSON", e);
        } finally {
            c.disconnect();
        }
    }

    /** A token good for at least five more minutes: the saved one, or a new one for the refresh token. */
    private synchronized String accessToken() throws IOException, ReconnectNeeded {
        if (!paired()) throw new ReconnectNeeded("not signed in");
        String access = prefs.getString("access_token", "");
        if (!access.isEmpty() && prefs.getLong("expires_at", 0) - System.currentTimeMillis() > 5 * 60_000) return access;
        String form = "grant_type=refresh_token&refresh_token=" + enc(prefs.getString("refresh_token", ""))
                + "&client_id=" + enc(prefs.getString("client_id", "")) + "&client_secret=" + enc(prefs.getString("client_secret", ""))
                + "&resource=" + enc(RESOURCE);
        HttpURLConnection c = open(TOKEN);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            write(c, form);
            int code = c.getResponseCode();
            if (code == 400 || code == 401) {
                String why = read(c.getErrorStream());
                prefs.edit().putBoolean("reconnect", true).apply();
                throw new ReconnectNeeded("refresh refused: " + code + " " + why);
            }
            if (code / 100 != 2) throw new IOException("token " + code);
            JSONObject t = new JSONObject(read(c.getInputStream()));
            access = t.getString("access_token");
            SharedPreferences.Editor e = prefs.edit().putString("access_token", access)
                    .putLong("expires_at", System.currentTimeMillis() + 1000L * t.optLong("expires_in", 3600))
                    .putBoolean("reconnect", false);
            // INDmoney swaps the refresh token at every renewal: the new one has to be on disk before anything else.
            if (t.has("refresh_token")) e.putString("refresh_token", t.getString("refresh_token"));
            e.commit();
            return access;
        } catch (JSONException e) {
            throw new IOException("token: not JSON", e);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        // Cloudflare in front of INDmoney turns away Java's default agent.
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; BRAVIA) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
        return c;
    }

    private static void write(HttpURLConnection c, String body) throws IOException {
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0 && out.size() < (4 << 20)) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.Map<String, Object> map(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
