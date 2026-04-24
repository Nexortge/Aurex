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
import java.net.URLEncoder;
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
 * Urchin ({@code urchin.ws}) client — second-opinion community blacklist.
 *
 * <p>Shape mirrors {@link SeraphClient}: same async contract, same background
 * executor, same rate-limited single-token-per-request policy. Differences:
 * <ul>
 *   <li><b>Auth is in the query string</b> ({@code key=<apikey>}), not a header.
 *       Means every log line that includes the URL MUST scrub the key first —
 *       see {@link #maskKeyForLog(String)}.</li>
 *   <li>Endpoint takes an IGN alongside the UUID
 *       ({@code /cubelify?id=&lt;uuid&gt;&key=&lt;apikey&gt;&name=&lt;ign&gt;&sources=GAME,MANUAL}).
 *       We supply the name when we have it (tab-row path) and omit it when we
 *       don't (probe / AX-check path). Urchin accepts UUID-only lookups.</li>
 * </ul>
 *
 * <p>Error policy (parallel to {@link SeraphClient}):
 * <ul>
 *   <li>{@code 401} or {@code 403} → future completes exceptionally with
 *       {@link UrchinAuthException}. {@link UrchinCache} flips its session-wide
 *       {@code authFailed} flag on first sight.</li>
 *   <li>{@code 404} → empty {@link UrchinData} (player has no blacklist entry).
 *       Not an error, common case.</li>
 *   <li>Any other transport or parse failure → future completes exceptionally.</li>
 * </ul>
 */
public final class UrchinClient {

    private static final String ENDPOINT = "https://urchin.ws/cubelify";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final String USER_AGENT = "Aurex/0.0.1 (+github:aurex)";
    private static final String SOURCES = "GAME,MANUAL";

    private final String apiKey;
    private final RateLimiter limiter;
    private final ExecutorService executor;
    private final Consumer<String> logger;
    private final Gson gson = new Gson();

    public UrchinClient(String apiKey) {
        this(apiKey, RateLimiter.defaultUrchin(), defaultExecutor(), NO_LOG);
    }

    public UrchinClient(String apiKey, Consumer<String> logger) {
        this(apiKey, RateLimiter.defaultUrchin(), defaultExecutor(), logger);
    }

    public UrchinClient(String apiKey, RateLimiter limiter, ExecutorService executor, Consumer<String> logger) {
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
     * Fetch Urchin data for {@code uuid} with the player's current IGN.
     * {@code name} is a REQUIRED Urchin query param (per
     * {@code docs.urchin.ws}) — callers should supply the raw 16-char MC
     * username. Null / empty falls back to a placeholder so the request still
     * validates server-side; Urchin matches primarily on UUID anyway.
     */
    public CompletableFuture<UrchinData> fetch(UUID uuid, String ign) {
        return CompletableFuture.supplyAsync(() -> fetchLogged(uuid, ign), executor);
    }

    public void shutdown() {
        executor.shutdown();
    }

    private UrchinData fetchLogged(UUID uuid, String ign) {
        long start = System.nanoTime();
        String tag = shortUuid(uuid);
        try {
            UrchinData data = fetchBlocking(uuid, ign);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            int tagCount = data.tags == null ? 0 : data.tags.size();
            logger.accept("urchin: " + tag + " -> tags=" + tagCount
                    + (data.hasCheaterTag ? " CHEATER" : "")
                    + (data.hasBotTag ? " BOT" : "")
                    + tagNamesSummary(data)
                    + " (" + ms + "ms)");
            return data;
        } catch (RuntimeException re) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Throwable cause = re.getCause() != null ? re.getCause() : re;
            logger.accept("urchin: " + tag + " FAILED after " + ms + "ms: " + cause);
            throw re;
        }
    }

    /**
     * Render the raw tag list as {@code [tooltip1,tooltip2,...]} for the
     * trace log. Urchin tags don't carry a short {@code tag_name} like Seraph,
     * so we log the (truncated) tooltip instead — that's where the English
     * classification keywords live, so grep-friendly for tuning the
     * cheater/bot substring sets in {@link UrchinData}.
     */
    private static String tagNamesSummary(UrchinData data) {
        if (data.tags == null || data.tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" [");
        for (int i = 0; i < data.tags.size(); i++) {
            if (i > 0) sb.append(',');
            UrchinData.UrchinTag t = data.tags.get(i);
            String label = (t.icon != null && !t.icon.isEmpty()) ? t.icon
                    : (t.tooltip != null && !t.tooltip.isEmpty()) ? t.tooltip : "?";
            if (label.length() > 24) label = label.substring(0, 24);
            sb.append(label);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString().replace("-", "");
        return s.substring(0, 8);
    }

    private UrchinData fetchBlocking(UUID uuid, String ign) {
        String undashed = uuid.toString().replace("-", "");
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for rate-limit token", e);
        }
        JsonObject body;
        try {
            body = fetchCubelify(undashed, ign);
        } catch (IOException ioe) {
            throw wrap(ioe);
        }
        return UrchinData.fromResponse(uuid, body);
    }

    /** Wrap IOException consistently — supplyAsync expects RuntimeExceptions. */
    private static RuntimeException wrap(IOException ioe) {
        return new RuntimeException(ioe);
    }

    private JsonObject fetchCubelify(String undashedUuid, String ign) throws IOException {
        String url = buildUrl(undashedUuid, ign);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                String errBody = readErrorBody(conn);
                logger.accept("urchin: auth rejected (HTTP " + code + ") url="
                        + maskKeyForLog(url)
                        + (errBody.isEmpty() ? "" : " body=" + errBody));
                throw new UrchinAuthException("Urchin rejected API key (HTTP " + code + ")"
                        + (errBody.isEmpty() ? "" : ": " + errBody));
            }
            if (code == 404) {
                // No blacklist entry — clean player, empty tags.
                return null;
            }
            if (code != 200) {
                String errBody = readErrorBody(conn);
                logger.accept("urchin: non-200 (HTTP " + code + ") url="
                        + maskKeyForLog(url)
                        + (errBody.isEmpty() ? "" : " body=" + errBody));
                throw new IOException("HTTP " + code + " from Urchin"
                        + (errBody.isEmpty() ? "" : ": " + errBody));
            }

            InputStream in = conn.getInputStream();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = gson.fromJson(reader, JsonObject.class);
                if (root == null) throw new IOException("empty urchin body");
                return root;
            }
        } catch (UrchinAuthException uae) {
            throw uae;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Assemble {@code /cubelify?id=...&key=...&name=...&sources=GAME,MANUAL}.
     * URL-encodes all dynamic components so a name with unexpected characters
     * (we shouldn't see any beyond {@code [A-Za-z0-9_]}, but defensive) can't
     * break the query. {@code ign} may be null/empty — the {@code name} param
     * is simply omitted in that case.
     */
    private String buildUrl(String undashedUuid, String ign) {
        // name is REQUIRED by Urchin — always emit it. Empty / null names get
        // a "_" placeholder so we don't send an empty value (some gateways
        // reject those with 400 before even hitting the auth layer).
        String safeIgn = (ign == null || ign.isEmpty()) ? "_" : ign;
        return ENDPOINT
                + "?id=" + urlEncode(undashedUuid)
                + "&key=" + urlEncode(apiKey)
                + "&name=" + urlEncode(safeIgn)
                + "&sources=" + urlEncode(SOURCES);
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            // UTF-8 is guaranteed supported; this branch is unreachable on any
            // sane JVM but we fall through to the raw string rather than throw.
            return s;
        }
    }

    /**
     * Mask the {@code key=} parameter in a URL before logging. Replaces the
     * value with {@code ***}. Used for every log line that would otherwise
     * expose the raw key via the URL — anything else writes {@code apiKey}
     * through {@code readErrorBody} / response paths that don't touch the URL.
     */
    static String maskKeyForLog(String url) {
        if (url == null) return "";
        int i = url.indexOf("key=");
        if (i < 0) return url;
        int end = url.indexOf('&', i);
        if (end < 0) end = url.length();
        return url.substring(0, i) + "key=***" + url.substring(end);
    }

    /**
     * Slurp the error-stream body (truncated) for logging. Mirrors
     * {@link SeraphClient}'s helper — best-effort, truncated to 200 chars,
     * newline-stripped.
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
                Thread t = new Thread(r, "Aurex-UrchinClient-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(4, tf);
    }
}
