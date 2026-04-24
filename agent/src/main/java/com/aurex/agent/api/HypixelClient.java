package com.aurex.agent.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hypixel {@code /v2/player} client.
 *
 * <p>Key design points:
 * <ul>
 *   <li><b>Async-only.</b> {@link #fetch(UUID)} returns a {@link CompletableFuture}
 *       — callers on the render thread never block on network.</li>
 *   <li><b>Rate-limited.</b> Every fetch goes through a shared {@link RateLimiter}
 *       so we stay well under Hypixel's 300-req-per-5-min ceiling. Actual limit
 *       is advertised per-key in the {@code RateLimit-*} response headers.</li>
 *   <li><b>Dedicated executor.</b> A small fixed pool keeps HTTP latency off
 *       whatever caller thread invokes us; render-thread hands are always clean.</li>
 *   <li><b>No request dedup here.</b> Two {@code fetch(sameUUID)} calls will run
 *       two HTTP requests. Dedup lives in {@link StatsCache} where it belongs.</li>
 *   <li><b>Returns {@code null}</b> for "no Bedwars data" (nicked / never played).
 *       The future completes normally with {@code null} — it does NOT fail.
 *       Completes exceptionally only on transport / parse errors.</li>
 * </ul>
 */
public final class HypixelClient {

    private static final String ENDPOINT = "https://api.hypixel.net/v2/player?uuid=";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final String USER_AGENT = com.aurex.agent.Version.USER_AGENT;

    private final String apiKey;
    private final RateLimiter limiter;
    private final ExecutorService executor;
    private final Consumer<String> logger;
    private final Gson gson = new Gson();

    public HypixelClient(String apiKey) {
        this(apiKey, RateLimiter.defaultHypixel(), defaultExecutor(), NO_LOG);
    }

    /**
     * Construct with a logging sink. Each completed fetch produces one line so
     * callers can audit which UUIDs actually hit the API vs. got served from
     * cache. The sink is called on background executor threads — keep it cheap
     * and thread-safe. {@link #NO_LOG} disables logging entirely.
     */
    public HypixelClient(String apiKey, Consumer<String> logger) {
        this(apiKey, RateLimiter.defaultHypixel(), defaultExecutor(), logger);
    }

    public HypixelClient(String apiKey, RateLimiter limiter, ExecutorService executor) {
        this(apiKey, limiter, executor, NO_LOG);
    }

    public HypixelClient(String apiKey, RateLimiter limiter, ExecutorService executor, Consumer<String> logger) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        this.apiKey = apiKey;
        this.limiter = limiter;
        this.executor = executor;
        this.logger = logger != null ? logger : NO_LOG;
    }

    /** No-op logger. Use instead of {@code null} to avoid branches on every log call. */
    public static final Consumer<String> NO_LOG = msg -> {};

    /**
     * Fetch stats for a UUID.
     *
     * <p>The returned future completes:
     * <ul>
     *   <li>normally with a {@link BedwarsStats} when the player has Bedwars data</li>
     *   <li>normally with {@code null} when the player is nicked or never played Bedwars</li>
     *   <li>exceptionally on network error, non-200 HTTP status, or parse failure</li>
     * </ul>
     */
    public CompletableFuture<BedwarsStats> fetch(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> fetchLogged(uuid), executor);
    }

    /** Graceful shutdown for the CLI harness. Agent keeps the executor alive for the JVM's life. */
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Thin wrapper around {@link #fetchBlocking} that logs one line per
     * completed call: outcome + total wall-clock time (queue-wait + HTTP).
     * Separating this from the HTTP logic keeps {@code fetchBlocking} focused
     * on the request/response itself.
     */
    private BedwarsStats fetchLogged(UUID uuid) {
        long start = System.nanoTime();
        String tag = shortUuid(uuid);
        try {
            BedwarsStats stats = fetchBlocking(uuid);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (stats == null) {
                logger.accept("api: " + tag + " -> no-data (" + ms + "ms)");
            } else {
                logger.accept("api: " + tag + " -> " + stats.displayName
                        + " ✫" + stats.stars
                        + " FKDR=" + String.format("%.2f", stats.fkdr)
                        + " (" + ms + "ms)");
            }
            return stats;
        } catch (RuntimeException re) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // Unwrap the "real" cause for the log — supplyAsync wraps IOExceptions
            // in RuntimeException in fetchBlocking; showing the cause is more useful.
            Throwable cause = re.getCause() != null ? re.getCause() : re;
            logger.accept("api: " + tag + " FAILED after " + ms + "ms: " + cause);
            throw re;
        }
    }

    /** First 8 hex chars of the undashed UUID — enough to eyeball which player without doxxing the full id in logs. */
    private static String shortUuid(UUID uuid) {
        String s = uuid.toString().replace("-", "");
        return s.substring(0, 8);
    }

    private BedwarsStats fetchBlocking(UUID uuid) {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for rate-limit token", e);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT + uuid.toString().replace("-", ""));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("API-Key", apiKey);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                // Key-level auth rejection. Typed so StatsCache can latch the
                // session-wide authFailed flag; AgentImpl surfaces a one-shot
                // chat warning and suppresses further fetches until the user
                // runs AX-hypixel <newkey>.
                String body = readErrorBody(conn);
                logger.accept("api: auth rejected (HTTP " + code + ")"
                        + (body.isEmpty() ? "" : " body=" + body));
                throw new HypixelAuthException("Hypixel rejected API key (HTTP " + code + ")"
                        + (body.isEmpty() ? "" : ": " + body));
            }
            if (code == 404) {
                // Hypixel returns 404 for unknown UUIDs. Treat as "no data".
                return null;
            }
            if (code == 429) {
                // Hypixel says: respect RateLimit-Reset (seconds until next window).
                // We throw — the caller can retry if they want — but we also burn
                // those seconds on this thread so we don't immediately try again
                // and dig a deeper hole.
                int resetSec = parseIntHeader(conn, "RateLimit-Reset", 10);
                String body = readErrorBody(conn);
                logger.accept("api: rate limited (HTTP 429), sleeping " + resetSec + "s"
                        + (body.isEmpty() ? "" : " body=" + body));
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(Math.max(1, resetSec)));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Hypixel rate limit hit (429); slept " + resetSec + "s");
            }
            if (code != 200) {
                String body = readErrorBody(conn);
                logger.accept("api: non-200 (HTTP " + code + ")"
                        + (body.isEmpty() ? "" : " body=" + body));
                throw new IOException("HTTP " + code + " from Hypixel"
                        + (body.isEmpty() ? "" : ": " + body));
            }

            // Observability: if the server tells us we're nearly out of budget,
            // surface it via the logger so it lands in agent.log. A future pass
            // can feed RateLimit-Limit back into the limiter dynamically to raise
            // the floor for higher-tier keys.
            int remaining = parseIntHeader(conn, "RateLimit-Remaining", -1);
            if (remaining >= 0 && remaining < 5) {
                logger.accept("api: WARN low RateLimit-Remaining=" + remaining);
            }

            InputStream in = conn.getInputStream();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = gson.fromJson(reader, JsonObject.class);
                if (root == null) {
                    throw new IOException("empty response body");
                }
                if (root.has("success") && !root.get("success").getAsBoolean()) {
                    String cause = root.has("cause") ? root.get("cause").getAsString() : "unknown";
                    throw new IOException("Hypixel error: " + cause);
                }
                if (!root.has("player") || root.get("player").isJsonNull()) {
                    // Valid response, no player object -> nicked / never logged in.
                    return null;
                }
                JsonObject player = root.getAsJsonObject("player");
                return BedwarsStats.fromPlayerJson(uuid, player);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int parseIntHeader(HttpURLConnection conn, String name, int defaultValue) {
        String v = conn.getHeaderField(name);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Slurp the error-stream body (truncated) for logging. Best-effort — any
     * failure returns empty string. Truncated to 200 chars so a giant error
     * page can't bloat agent.log. Hypixel's error bodies are usually
     * {@code {"success":false,"cause":"..."}} which fits easily.
     */
    private static String readErrorBody(HttpURLConnection conn) {
        InputStream err = conn.getErrorStream();
        if (err == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int total = 0;
            int n;
            while (total < 1024 && (n = err.read(buf)) != -1) {
                baos.write(buf, 0, n);
                total += n;
            }
            String s = new String(baos.toByteArray(), StandardCharsets.UTF_8)
                    .replace('\n', ' ').replace('\r', ' ').trim();
            if (s.length() > 200) s = s.substring(0, 197) + "...";
            return s;
        } catch (IOException e) {
            return "";
        } finally {
            try { err.close(); } catch (IOException ignored) {}
        }
    }

    private static ExecutorService defaultExecutor() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Aurex-HypixelClient-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(4, tf);
    }
}
