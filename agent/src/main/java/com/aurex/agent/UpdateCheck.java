package com.aurex.agent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * M19 — one-shot "update available" chat reminder.
 *
 * <p>Called from {@link AgentImpl#onServerJoin()} after the M18 whitelist
 * gate passes. Fetches {@code version.txt} from the public repo on a
 * background thread and, if the returned string differs from
 * {@link Version#VERSION}, prints one yellow line in client chat:
 *
 * <pre>§eUpdate available: 0.1.0 -> 0.2.0. Re-run the installer to update.</pre>
 *
 * <p>Fires at most once per JVM run (boolean guard, set eagerly so a
 * transient fetch failure doesn't lead to retry spam on lobby hops).
 * Silent on any error — the reminder is advisory, not load-bearing.
 */
public final class UpdateCheck {

    private static final String VERSION_URL =
            "https://github.com/Nexortge/Aurex/raw/main/version.txt";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /**
     * One-shot guard. Set eagerly inside {@link #maybeAnnounceOnce} before
     * the fetch starts so a failed attempt still prevents a second
     * (equally-likely-to-fail) attempt on the next world-join.
     */
    private static volatile boolean announced = false;

    private UpdateCheck() {}

    /**
     * Kick off a background version check; print one chat line per JVM
     * run when {@code version.txt} disagrees with {@link Version#VERSION}.
     * Safe to call repeatedly — subsequent calls no-op.
     */
    public static void maybeAnnounceOnce(final ClassLoader mcLoader) {
        if (announced) return;
        announced = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String latest = fetchVersion();
                    if (latest == null || latest.isEmpty()) return;
                    if (latest.equals(Version.VERSION)) {
                        Agent.log("update: on latest (" + Version.VERSION + ")");
                        return;
                    }
                    Agent.log("update: running " + Version.VERSION
                            + ", latest is " + latest);
                    Agent.sendClientChat(Agent.PREFIX + "§eUpdate available: "
                            + Version.VERSION + " §e-> §e" + latest
                            + "§e. Re-run the installer to update.", mcLoader);
                } catch (Throwable t) {
                    Agent.log("update: check failed: " + t);
                }
            }
        }, "Aurex-UpdateCheck");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Blocking GET against {@link #VERSION_URL}. Returns the trimmed body
     * on HTTP 200, {@code null} on any non-200, parse error, or network
     * failure. Uses a per-minute cache-buster — Fastly caches the raw URL
     * the same way it did for the whitelist gist, and we want at most one
     * stale minute between a {@code version.txt} push and the in-game
     * reminder firing for a session that spans the edit.
     */
    private static String fetchVersion() {
        HttpURLConnection conn = null;
        try {
            String cacheBust = Long.toString(System.currentTimeMillis() / 60_000L);
            String url = VERSION_URL + "?t=" + cacheBust;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", Version.USER_AGENT);
            conn.setRequestProperty("Accept", "text/plain");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) {
                Agent.log("update: non-200 HTTP " + code);
                return null;
            }
            try (Reader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[256];
                int n;
                while ((n = r.read(buf)) != -1 && sb.length() < 256) {
                    sb.append(buf, 0, n);
                }
                return sb.toString().trim();
            }
        } catch (IOException ioe) {
            Agent.log("update: fetch failed: " + ioe);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
