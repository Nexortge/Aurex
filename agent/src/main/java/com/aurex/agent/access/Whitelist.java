package com.aurex.agent.access;

import com.aurex.agent.Agent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M18 — friend-group UUID whitelist gate.
 *
 * <p>Runs on every world-join (not {@code premain}) so mid-session Minecraft
 * account swaps flip dormant on/off without a Lunar relaunch. Default-deny:
 * a UUID must be in {@code allowed} (and not in {@code revoked}) on the
 * whitelist JSON to pass; anything else (including network failure with no
 * usable cache) turns the agent dormant for this account.
 *
 * <p>Lives on the bootstrap classloader, same as {@link com.aurex.agent.AgentImpl}.
 * The three Agent-side hook entrypoints ({@code renderAurexTab},
 * {@code onOutgoingChat}, {@code onIncomingChat}) read {@link #isDormant()}
 * via a reflected-then-cached Method handle (same pattern as every other
 * bootstrap bridge in {@link Agent}) — the MC-loader copy of {@code Agent}
 * can't directly import this class because IchorPipeline does not delegate
 * for our packages.
 *
 * <p>Threat model (from PLAN.md §425): stop casual resharing, not a
 * determined reverser. Rebuilding from source bypasses this completely —
 * that's intentional. The URL is the trust root; change it = rebuild.
 *
 * <p>Caveat worth knowing: GitHub caches gist raw URLs at their CDN for
 * roughly five minutes. A push to the gist isn't visible immediately; the
 * effective revocation latency is (CDN TTL) + (our cache age).
 * {@code AX-whitelist-refresh} still hits the cached CDN copy — there is no
 * query-string cache-buster that reliably defeats this.
 */
public final class Whitelist {

    /**
     * Trust root. Rebuilding the jar is the revocation vehicle; do not move
     * this to {@link com.aurex.agent.api.Config} — runtime-configurable trust
     * root is no trust root at all.
     */
    private static final String WHITELIST_URL =
            "https://gist.githubusercontent.com/Nexortge/810707fe769399e32259fbe230db535d/raw";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long ONE_DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long SEVEN_DAYS_MS = 7L * ONE_DAY_MS;
    private static final String USER_AGENT = "Aurex/0.0.1 (+github:aurex)";
    private static final String CACHE_FILE = "whitelist-cache.json";

    enum Verdict { ALLOW, DENY }

    /** Verdict cache keyed by UUID — avoids refetching on every Bungee sub-server hop within one session. */
    private static final ConcurrentHashMap<UUID, Verdict> sessionVerdicts = new ConcurrentHashMap<UUID, Verdict>();
    /** One deny chat line per UUID per session. Cleared on {@link #forceRefresh}. */
    private static final Set<UUID> alreadyAnnouncedDeny = ConcurrentHashMap.newKeySet();

    /** Hot-path read; volatile so hook entrypoints on the render thread see the latest write. */
    private static volatile boolean dormant = false;
    private static volatile UUID dormantUuid = null;
    /** Guard so the 1–7-day background refresh fires at most once per JVM run. */
    private static volatile boolean backgroundRefreshFired = false;
    /**
     * Escape hatch for {@code AX-whitelist-refresh}: when true, the next fetch
     * uses a unique per-call cache-buster instead of the shared per-minute
     * bucket. Reset immediately after the fetch.
     */
    private static volatile boolean forceBypassCdnCache = false;

    private Whitelist() {}

    /** Hot path — called from Agent's hook entrypoints. */
    public static boolean isDormant() { return dormant; }

    /**
     * Called from {@link com.aurex.agent.AgentImpl#onServerJoin()} (off render thread).
     * Never throws — a failure inside flips dormant closed so a broken gate
     * doesn't silently let anyone through.
     *
     * <p>Handles the world-load race spelled out in PLAN.md §439: if {@code
     * thePlayer} is null or its UUID is zero, go dormant silently (no chat
     * line, no log spam) and wait for the next world-join tick. Should
     * self-correct within one lobby hop.
     */
    public static void check(UUID uuid, ClassLoader mcLoader) {
        try {
            if (uuid == null
                    || (uuid.getLeastSignificantBits() == 0L && uuid.getMostSignificantBits() == 0L)) {
                // thePlayer not ready — silent dormant + retry on next join.
                // Don't cache this in sessionVerdicts so the retry can resolve.
                dormant = true;
                dormantUuid = null;
                return;
            }

            Verdict cached = sessionVerdicts.get(uuid);
            if (cached != null) {
                // Repeat join for the same UUID — already decided, no network, no log spam.
                applyVerdict(uuid, cached, mcLoader, "session-cache");
                return;
            }

            Snapshot snap = loadSnapshot();
            Verdict v = (snap == null) ? Verdict.DENY : decide(uuid, snap);
            sessionVerdicts.put(uuid, v);
            applyVerdict(uuid, v, mcLoader, snap == null ? "no-verdict" : snap.source);
        } catch (Throwable t) {
            Agent.log("whitelist: check failed: " + t);
            // Fail-closed: an error here must not silently allow.
            dormant = true;
            dormantUuid = uuid;
        }
    }

    /**
     * Called from the {@code AX-whitelist-refresh} command. Clears session
     * verdict + dedup caches and re-evaluates the current UUID against a
     * freshly-fetched snapshot. Lets a revoked account be denied mid-session
     * without a relaunch (PLAN.md §442).
     */
    public static void forceRefresh(UUID uuid, ClassLoader mcLoader) {
        try {
            sessionVerdicts.clear();
            alreadyAnnouncedDeny.clear();
            forceBypassCdnCache = true;
            if (uuid == null) {
                Agent.sendClientChat(Agent.PREFIX + "§ewhitelist refresh: no active player", mcLoader);
                Agent.log("whitelist: forceRefresh with null UUID");
                return;
            }
            // check() prefers network inside loadSnapshot(), so this path hits
            // the gist. If the network fetch fails, check() falls back to the
            // on-disk cache per policy.
            check(uuid, mcLoader);
            // On deny, applyVerdict already fired the red deny line (we cleared
            // alreadyAnnouncedDeny above). On allow, give an explicit
            // confirmation so the user knows the refresh landed.
            if (!dormant) {
                Agent.sendClientChat(Agent.PREFIX + "§awhitelist refreshed — allowed", mcLoader);
            }
        } catch (Throwable t) {
            Agent.log("whitelist: forceRefresh failed: " + t);
            Agent.sendClientChat(Agent.PREFIX + "§cwhitelist refresh failed: "
                    + t.getClass().getSimpleName(), mcLoader);
        }
    }

    private static void applyVerdict(UUID uuid, Verdict v, ClassLoader mcLoader, String source) {
        String shortUuid = shortUuid(uuid);
        if (v == Verdict.ALLOW) {
            dormant = false;
            dormantUuid = null;
            Agent.log("access: uuid=" + shortUuid + " verdict=allow source=" + source);
        } else {
            dormant = true;
            dormantUuid = uuid;
            Agent.log("access: uuid=" + shortUuid + " verdict=deny source=" + source);
            if (alreadyAnnouncedDeny.add(uuid)) {
                // Re-apply §c after the em-dash: MC 1.8.9 chat resets formatting
                // when a long line visually wraps to a second row, and the wrap
                // typically falls around here. The repeated code is a no-op
                // before the wrap and restores red after.
                Agent.sendClientChat(Agent.PREFIX
                        + "§cThis build is not authorized for your account §c— §ccontact the owner",
                        mcLoader);
            }
        }
    }

    private static Verdict decide(UUID uuid, Snapshot snap) {
        if (snap.revoked != null && snap.revoked.contains(uuid)) return Verdict.DENY;
        if (snap.allowed != null && snap.allowed.contains(uuid)) return Verdict.ALLOW;
        // Not listed at all → deny by default (spec §436).
        return Verdict.DENY;
    }

    /**
     * Network-first with cache fallback.
     * <ul>
     *   <li>Network success → write cache, return snapshot tagged {@code network}.</li>
     *   <li>Network fail + cache age &le; 1 day → silent cache use.</li>
     *   <li>Network fail + cache age 1–7 days → use cache, fire one
     *       background refresh attempt per JVM run.</li>
     *   <li>Network fail + cache age &gt; 7 days (or no cache) → null
     *       (caller applies default-deny).</li>
     * </ul>
     */
    private static Snapshot loadSnapshot() {
        Snapshot network = fetchFromNetworkOrLog();
        if (network != null) {
            writeCachedFile(network);
            return network;
        }
        Snapshot cached = readCachedFile();
        if (cached == null) return null;
        long age = System.currentTimeMillis() - cached.fetchedAtMs;
        if (age <= ONE_DAY_MS) {
            cached.source = "cache";
            return cached;
        }
        if (age <= SEVEN_DAYS_MS) {
            cached.source = "cache";
            maybeScheduleBackgroundRefresh();
            return cached;
        }
        Agent.log("whitelist: cache age " + age + "ms > 7 days — treating as no-verdict");
        return null;
    }

    private static void maybeScheduleBackgroundRefresh() {
        if (backgroundRefreshFired) return;
        backgroundRefreshFired = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                Snapshot fresh = fetchFromNetworkOrLog();
                if (fresh != null) {
                    writeCachedFile(fresh);
                    Agent.log("whitelist: background refresh succeeded");
                }
            }
        }, "Aurex-Whitelist-BG");
        t.setDaemon(true);
        t.start();
    }

    private static Snapshot fetchFromNetworkOrLog() {
        try {
            return fetchFromNetwork();
        } catch (Throwable t) {
            Agent.log("whitelist: network fetch failed: " + t);
            return null;
        }
    }

    private static Snapshot fetchFromNetwork() throws IOException {
        HttpURLConnection conn = null;
        try {
            // Cache-buster: Fastly (GitHub's CDN) keys on the full URL including
            // query string by default, so an epoch-derived param defeats the
            // ~5-min edge cache. AX-whitelist-refresh uses nanoTime for
            // guaranteed uniqueness (manual refresh must always be fresh);
            // the auto per-launch path uses a per-minute bucket so repeated
            // world-joins within one minute still coalesce to one CDN hit.
            String cacheBust;
            if (forceBypassCdnCache) {
                cacheBust = Long.toString(System.nanoTime());
                forceBypassCdnCache = false;
            } else {
                cacheBust = Long.toString(System.currentTimeMillis() / 60_000L);
            }
            String url = WHITELIST_URL + "?t=" + cacheBust;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code != 200) {
                Agent.log("whitelist: non-200 HTTP " + code);
                return null;
            }
            try (Reader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el == null || !el.isJsonObject()) {
                    Agent.log("whitelist: response is not a JSON object");
                    return null;
                }
                Snapshot snap = parseSnapshot(el.getAsJsonObject());
                snap.fetchedAtMs = System.currentTimeMillis();
                snap.source = "network";
                Agent.log("whitelist: fetched " + snap.allowed.size()
                        + " allowed, " + snap.revoked.size() + " revoked");
                return snap;
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Snapshot readCachedFile() {
        Path path = cachePath();
        if (path == null || !Files.exists(path)) return null;
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) return null;
            JsonObject root = el.getAsJsonObject();
            Snapshot snap = parseSnapshot(root);
            snap.fetchedAtMs = root.has("fetchedAtMs") && root.get("fetchedAtMs").isJsonPrimitive()
                    ? root.get("fetchedAtMs").getAsLong() : 0L;
            return snap;
        } catch (Throwable t) {
            Agent.log("whitelist: cache read failed: " + t);
            return null;
        }
    }

    private static void writeCachedFile(Snapshot snap) {
        Path path = cachePath();
        if (path == null) return;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            JsonArray allowed = new JsonArray();
            for (UUID u : snap.allowed) allowed.add(u.toString().replace("-", ""));
            JsonArray revoked = new JsonArray();
            for (UUID u : snap.revoked) revoked.add(u.toString().replace("-", ""));
            root.add("allowed", allowed);
            root.add("revoked", revoked);
            root.addProperty("fetchedAtMs", snap.fetchedAtMs);
            Files.write(path, new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Agent.log("whitelist: cache write failed: " + t);
        }
    }

    private static Snapshot parseSnapshot(JsonObject root) {
        Snapshot snap = new Snapshot();
        snap.allowed = parseUuidArray(root, "allowed");
        snap.revoked = parseUuidArray(root, "revoked");
        return snap;
    }

    /**
     * Normalize to lowercase undashed hex, require 32 chars, parse as UUID.
     * Malformed entries are skipped with a log line naming the bad value —
     * easier to diagnose than a silent drop.
     */
    private static Set<UUID> parseUuidArray(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) return Collections.emptySet();
        JsonElement el = root.get(key);
        if (!el.isJsonArray()) {
            Agent.log("whitelist: " + key + " must be an array");
            return Collections.emptySet();
        }
        JsonArray arr = el.getAsJsonArray();
        Set<UUID> out = new HashSet<UUID>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (item == null || !item.isJsonPrimitive()) continue;
            String raw = item.getAsString();
            String s = raw.trim().toLowerCase().replace("-", "");
            if (s.length() != 32) {
                Agent.log("whitelist: skipped invalid " + key + "[" + i + "]=\"" + raw + "\"");
                continue;
            }
            try {
                UUID u = UUID.fromString(
                        s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                                + s.substring(12, 16) + "-" + s.substring(16, 20)
                                + "-" + s.substring(20));
                out.add(u);
            } catch (IllegalArgumentException e) {
                Agent.log("whitelist: skipped unparseable " + key + "[" + i + "]=\"" + raw + "\"");
            }
        }
        return out;
    }

    private static Path cachePath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return Paths.get(appData, "Aurex", CACHE_FILE);
    }

    private static String shortUuid(UUID uuid) {
        if (uuid == null) return "null";
        String s = uuid.toString().replace("-", "");
        return s.substring(0, 8);
    }

    /**
     * Plain data carrier for a single parsed whitelist snapshot. Fields are
     * package-private because only {@link Whitelist} reads them.
     */
    static final class Snapshot {
        Set<UUID> allowed = Collections.emptySet();
        Set<UUID> revoked = Collections.emptySet();
        long fetchedAtMs;
        String source = "network";
    }
}
