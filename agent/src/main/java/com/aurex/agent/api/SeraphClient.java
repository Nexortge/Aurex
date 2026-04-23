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
 * Seraph (`api.seraph.si`) client — Cubelify-formatted blacklist tags.
 *
 * <p>Shape mirrors {@link HypixelClient} intentionally: same async contract,
 * same background executor, same rate-limited single-token-per-request policy.
 *
 * <p>Error policy:
 * <ul>
 *   <li>{@code 401} or {@code 403} → future completes exceptionally with
 *       {@link SeraphAuthException}. {@link SeraphCache} flips its session-wide
 *       {@code authFailed} flag on first sight and stops issuing fetches.</li>
 *   <li>{@code 404} → empty {@link SeraphData} (player has no blacklist entry).
 *       Not an error, common case.</li>
 *   <li>Any other transport or parse failure → future completes exceptionally.
 *       {@link SeraphCache} caches the failed future for the TTL window so we
 *       don't hammer a flapping service.</li>
 * </ul>
 *
 * <p>Note: an earlier draft also fetched a detected-client id from
 * {@code /mod/tests/client/{uuid}} — that's a mod-integration test route,
 * not a production lookup, so the column was dropped.
 */
public final class SeraphClient {

    private static final String CUBELIFY_ENDPOINT = "https://api.seraph.si/cubelify/blacklist/";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final String USER_AGENT = "Aurex/0.0.1 (+github:aurex)";

    private final String apiKey;
    private final RateLimiter limiter;
    private final ExecutorService executor;
    private final Consumer<String> logger;
    private final Gson gson = new Gson();

    public SeraphClient(String apiKey) {
        this(apiKey, RateLimiter.defaultSeraph(), defaultExecutor(), NO_LOG);
    }

    public SeraphClient(String apiKey, Consumer<String> logger) {
        this(apiKey, RateLimiter.defaultSeraph(), defaultExecutor(), logger);
    }

    public SeraphClient(String apiKey, RateLimiter limiter, ExecutorService executor, Consumer<String> logger) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        this.apiKey = apiKey;
        this.limiter = limiter;
        this.executor = executor;
        this.logger = logger != null ? logger : NO_LOG;
    }

    public static final Consumer<String> NO_LOG = msg -> {};

    /**
     * Fetch Seraph data for {@code uuid} on the background executor.
     */
    public CompletableFuture<SeraphData> fetch(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> fetchLogged(uuid), executor);
    }

    public void shutdown() {
        executor.shutdown();
    }

    private SeraphData fetchLogged(UUID uuid) {
        long start = System.nanoTime();
        String tag = shortUuid(uuid);
        try {
            SeraphData data = fetchBlocking(uuid);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            int tagCount = data.tags == null ? 0 : data.tags.size();
            logger.accept("seraph: " + tag + " -> tags=" + tagCount
                    + (data.hasCheaterTag ? " CHEATER" : "")
                    + (data.hasBotTag ? " BOT" : "")
                    + tagNamesSummary(data)
                    + " (" + ms + "ms)");
            return data;
        } catch (RuntimeException re) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Throwable cause = re.getCause() != null ? re.getCause() : re;
            logger.accept("seraph: " + tag + " FAILED after " + ms + "ms: " + cause);
            throw re;
        }
    }

    /**
     * Render the raw {@code tag_name} list as {@code [name1,name2,...]} for
     * the trace log. Empty when no tags. Lets us spot tag-name vocabulary that
     * our cheater/bot classifier sets don't yet recognize (e.g. "blatant").
     */
    private static String tagNamesSummary(SeraphData data) {
        if (data.tags == null || data.tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" [");
        for (int i = 0; i < data.tags.size(); i++) {
            if (i > 0) sb.append(',');
            String n = data.tags.get(i).tagName;
            sb.append(n == null ? "?" : n);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString().replace("-", "");
        return s.substring(0, 8);
    }

    private SeraphData fetchBlocking(UUID uuid) {
        String undashed = uuid.toString().replace("-", "");
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for rate-limit token", e);
        }
        JsonObject cubelifyBody;
        try {
            cubelifyBody = fetchCubelify(undashed);
        } catch (IOException ioe) {
            throw wrap(ioe);
        }
        return SeraphData.fromResponses(uuid, cubelifyBody);
    }

    /** Wrap IOException consistently — supplyAsync expects RuntimeExceptions. */
    private static RuntimeException wrap(IOException ioe) {
        if (ioe instanceof SeraphAuthException) return new RuntimeException(ioe);
        return new RuntimeException(ioe);
    }

    private JsonObject fetchCubelify(String undashedUuid) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CUBELIFY_ENDPOINT + undashedUuid);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            // Seraph uses `Seraph-API-Key` (NOT `API-Key` like Hypixel) — using
            // the wrong header gets a silent 401 because the key never reaches
            // their auth layer. Verified via browser request capture.
            conn.setRequestProperty("Seraph-API-Key", apiKey);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                String body = readErrorBody(conn);
                logger.accept("seraph: auth rejected (HTTP " + code + ")"
                        + (body.isEmpty() ? "" : " body=" + body));
                throw new SeraphAuthException("Seraph rejected API key (HTTP " + code + ")"
                        + (body.isEmpty() ? "" : ": " + body));
            }
            if (code == 404) {
                // No blacklist entry — clean player, empty tags.
                return null;
            }
            if (code != 200) {
                String body = readErrorBody(conn);
                logger.accept("seraph: non-200 (HTTP " + code + ")"
                        + (body.isEmpty() ? "" : " body=" + body));
                throw new IOException("HTTP " + code + " from Seraph cubelify"
                        + (body.isEmpty() ? "" : ": " + body));
            }

            InputStream in = conn.getInputStream();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = gson.fromJson(reader, JsonObject.class);
                if (root == null) throw new IOException("empty cubelify body");
                return root;
            }
        } catch (SeraphAuthException sae) {
            throw sae;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Slurp the error-stream body (truncated) for logging. Best-effort — any
     * failure returns empty string. Truncated to 200 chars so a giant error
     * page can't bloat agent.log.
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
                Thread t = new Thread(r, "Aurex-SeraphClient-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(4, tf);
    }
}
