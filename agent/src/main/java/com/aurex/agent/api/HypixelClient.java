package com.aurex.agent.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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

/**
 * Hypixel {@code /v2/player} client.
 *
 * <p>Key design points:
 * <ul>
 *   <li><b>Async-only.</b> {@link #fetch(UUID)} returns a {@link CompletableFuture}
 *       — callers on the render thread never block on network.</li>
 *   <li><b>Rate-limited.</b> Every fetch goes through a shared {@link RateLimiter}
 *       so we never exceed Hypixel's 120 req/min cliff.</li>
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
    private static final String USER_AGENT = "Aurex/0.0.1 (+github:aurex)";

    private final String apiKey;
    private final RateLimiter limiter;
    private final ExecutorService executor;
    private final Gson gson = new Gson();

    public HypixelClient(String apiKey) {
        this(apiKey, RateLimiter.defaultHypixel(), defaultExecutor());
    }

    public HypixelClient(String apiKey, RateLimiter limiter, ExecutorService executor) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        this.apiKey = apiKey;
        this.limiter = limiter;
        this.executor = executor;
    }

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
        return CompletableFuture.supplyAsync(() -> fetchBlocking(uuid), executor);
    }

    /** Graceful shutdown for the CLI harness. Agent keeps the executor alive for the JVM's life. */
    public void shutdown() {
        executor.shutdown();
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
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(Math.max(1, resetSec)));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Hypixel rate limit hit (429); slept " + resetSec + "s");
            }
            if (code != 200) {
                throw new IOException("HTTP " + code + " from Hypixel");
            }

            // Observability: if the server tells us we're nearly out of budget,
            // surface that to stderr. Not currently used to adjust the client-side
            // limiter — M5 keeps the guard conservative and static; a future pass
            // can feed RateLimit-Limit back into the limiter dynamically.
            int remaining = parseIntHeader(conn, "RateLimit-Remaining", -1);
            if (remaining >= 0 && remaining < 5) {
                System.err.println("[HypixelClient] low RateLimit-Remaining=" + remaining);
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
