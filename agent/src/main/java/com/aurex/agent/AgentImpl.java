package com.aurex.agent;

import com.aurex.agent.api.ApiKeyConfig;
import com.aurex.agent.api.BedwarsStats;
import com.aurex.agent.api.HypixelClient;
import com.aurex.agent.api.StatsCache;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bootstrap-only implementation of tab-name stats decoration.
 *
 * <p><b>Why this lives separately from {@link Agent}:</b> Agent is
 * {@code defineClass}'d into Lunar's MC classloader so patched MC methods can
 * {@code INVOKESTATIC} it (see {@code memory/project_lunar_internals.md}).
 * Lunar's MC loader refuses to delegate to bootstrap for {@code com.aurex.agent.api.*}
 * or {@code com.google.gson.*} — the MC copy of Agent must not compile-time
 * reference those, or we'd fail at verify/link when MC loads it.
 *
 * <p>Solution: keep Agent MC-safe (java.* only); pay one
 * {@link Class#forName(String, boolean, ClassLoader) Class.forName(..., null)}
 * + cached {@link Method#invoke} hop from Agent's render-thread path over to
 * this class, which lives on bootstrap where api.* and Gson resolve fine.
 *
 * <p>All stats-pipeline state ({@link StatsCache}, {@link HypixelClient}, the
 * API key) lives in this class's static initializer. {@link Agent#start} calls
 * {@code Class.forName} on us at premain time to force init before the first
 * render — we don't want to do disk I/O (reading config.json) on the render
 * thread the first time someone opens tab.
 *
 * <p>All methods must be safe to call repeatedly from the render thread and
 * must never throw.
 */
public final class AgentImpl {

    // Standard Bedwars prestige colors. Each 100 stars = next tier.
    private static final String C_GRAY   = "§7";
    private static final String C_WHITE  = "§f";
    private static final String C_YELLOW = "§e";
    private static final String C_GOLD   = "§6";
    private static final String C_RED    = "§c";
    private static final String C_AQUA   = "§b";
    private static final String C_DG     = "§2"; // dark green — emerald
    private static final String C_DA     = "§3"; // dark aqua — sapphire
    private static final String C_DR     = "§4"; // dark red — ruby
    private static final String C_LP     = "§d"; // light purple — crystal
    private static final String C_BLUE   = "§9"; // blue — opal
    private static final String C_DP     = "§5"; // dark purple — amethyst
    private static final String RESET    = "§r";

    /** Shown while a fetch is in flight. Trailing space so it butts up against the original name. */
    private static final String PLACEHOLDER = C_GRAY + "[...]" + RESET + " ";

    /** Tag prepended to names that the API couldn't match (nicked / never played). */
    private static final String NICK_PREFIX = C_DR + "[NICK]" + RESET + " ";

    /**
     * The singleton stats pipeline. {@code null} if no API key is configured —
     * in that case decoration is a no-op and Aurex runs silently.
     */
    private static final StatsCache statsCache = initStatsCache();

    /**
     * UUIDs we've already fired a "[AX] -> Name is nicked!" chat alert for.
     * Cleared on {@code AX-off} so a re-arm in the same lobby re-announces.
     *
     * <p>Kept here (bootstrap) rather than on Agent because Agent has three
     * loader copies and static state doesn't cross between them.
     */
    private static final Set<UUID> alertedNicks = ConcurrentHashMap.newKeySet();

    /**
     * UUIDs we've already logged as "skipped (NPC/info row)". Dedup'd so the
     * log doesn't fill up every frame. Same lifecycle as {@link #alertedNicks}.
     */
    private static final Set<UUID> skippedNicks = ConcurrentHashMap.newKeySet();

    /**
     * First MC-loader we see an NPI through, cached for {@link Agent#sendClientChat}.
     * Bootstrap classloader can't resolve {@code net.minecraft.*} on its own,
     * so we snatch MC's loader off the first NetworkPlayerInfo that walks
     * through {@code decorateInternal} and hand it to sendClientChat when we
     * need to post an in-game line.
     */
    private static volatile ClassLoader capturedMcLoader;

    // Reflection cache for NetworkPlayerInfo#getGameProfile()#getId().
    // Can't compile-time reference net.minecraft.* from bootstrap — MC classes
    // live on Lunar's MC loader, not bootstrap — so reflection is the only
    // loader-agnostic way in.
    private static volatile Method npiGetGameProfile;
    private static volatile Method gameProfileGetId;

    private AgentImpl() {}

    private static StatsCache initStatsCache() {
        try {
            String key = ApiKeyConfig.load();
            if (key == null) {
                Agent.log("stats: no API key (HYPIXEL_API_KEY env or %APPDATA%\\Aurex\\config.json); running without stats");
                return null;
            }
            Agent.log("stats: API key loaded; pipeline ready");
            return new StatsCache(new HypixelClient(key, Agent::log));
        } catch (Throwable t) {
            Agent.log("stats: init failed, running without stats: " + t);
            return null;
        }
    }

    /**
     * Compute the tab-row string for one player. Called from {@link Agent#decorateName}
     * via reflection, once per tab entry per frame.
     *
     * <p>Decision tree:
     * <ul>
     *   <li>pipeline not configured → original name, unchanged</li>
     *   <li>cache HIT → prepend colored {@code [<stars>✫ <fkdr>]}</li>
     *   <li>cache miss + fetch armed → kick off fetch, show {@code [...]}</li>
     *   <li>cache miss + fetch idle → original name (avoid phantom placeholders
     *       after the 3s arm window closes)</li>
     *   <li>fetched but no Bedwars data (nicked / never played) → prepend
     *       {@code [NICK]} and fire a one-time {@code [AX] -> Name is nicked!}
     *       chat alert (see {@link #handleNick})</li>
     * </ul>
     *
     * Must NEVER throw — on error we return the original name so tab still renders.
     */
    public static String decorateInternal(String originalName, Object networkPlayerInfo, boolean fetchArmed) {
        try {
            if (statsCache == null) return originalName;
            if (capturedMcLoader == null && networkPlayerInfo != null) {
                capturedMcLoader = networkPlayerInfo.getClass().getClassLoader();
            }
            UUID uuid = extractUuid(networkPlayerInfo);
            if (uuid == null) return originalName;

            CompletableFuture<BedwarsStats> fut = statsCache.peekFuture(uuid);
            if (fut == null) {
                if (!fetchArmed) return originalName;  // not armed — don't start a fetch just from looking
                statsCache.get(uuid);                  // kick off async fetch; don't wait
                return PLACEHOLDER + originalName;
            }
            if (!fut.isDone()) return PLACEHOLDER + originalName;
            if (fut.isCompletedExceptionally()) return originalName;

            BedwarsStats stats = fut.getNow(null);
            if (stats == null) return handleNick(uuid, originalName);
            return formatStatsPrefix(stats) + " " + originalName;
        } catch (Throwable t) {
            return originalName;
        }
    }

    /**
     * Nicked / never-played branch. Called only when the fetch has completed
     * and returned {@code null} (404 UUID, missing {@code player} object, or
     * Bedwars stats absent).
     *
     * <p>Only decorates + alerts if {@code originalName} looks like it belongs
     * to a real player row — Hypixel lobbies stuff NPCs and info rows into the
     * tab, and tagging those as NICK would be visual noise and chat spam (see
     * {@code memory/project_hypixel_tab_hazards.md}).
     *
     * <p>Chat alert fires once per UUID per arm/disarm cycle (see
     * {@link #clearNickAlerts}). The red "[NICK]" tag in tab is stateless and
     * persists as long as the cache entry does.
     */
    private static String handleNick(UUID uuid, String originalName) {
        // Real Mojang-minted player UUIDs are always v4 (random). Hypixel
        // assigns v2/v3 UUIDs to server-generated entities (lobby NPCs,
        // shopkeepers, Firework Frank, etc.) — filter those by version nibble.
        // Cheaper and more accurate than any name-shape heuristic.
        if (uuid.version() != 4) {
            if (skippedNicks.add(uuid)) {
                Agent.log("nick skipped (non-v4 UUID, likely NPC): original='" + originalName + "' uuid=" + uuid);
            }
            return originalName;
        }

        String alertName = extractAlertName(originalName);
        if (alertName == null) {
            if (skippedNicks.add(uuid)) {
                Agent.log("nick skipped (name-shape fail): original='" + originalName + "' uuid=" + uuid);
            }
            return originalName;
        }

        if (alertedNicks.add(uuid)) {
            Agent.log("nick detected: name='" + alertName + "' uuid=" + uuid);
            Agent.sendClientChat(C_DR + "[AX] -> " + alertName + " is nicked!" + RESET,
                    capturedMcLoader);
        }
        return NICK_PREFIX + originalName;
    }

    /**
     * Pull a plausible MC username out of {@code originalName}.
     *
     * <p>{@code getPlayerName} returns strings like {@code "§7[VIP] §fNotch"} on
     * Hypixel — scoreboard team prefixes baked in. We strip section codes, take
     * the last whitespace-separated token, and only return it if it matches the
     * MC username shape ({@code [A-Za-z0-9_]{1,16}}). Anything else — rank-only
     * rows, NPC display names with punctuation, "Waiting for players..." info
     * rows — yields {@code null} and the caller skips decoration + alert.
     */
    private static String extractAlertName(String originalName) {
        if (originalName == null) return null;
        String stripped = originalName.replaceAll("§.", "").trim();
        if (stripped.isEmpty()) return null;
        int lastSpace = stripped.lastIndexOf(' ');
        String candidate = lastSpace < 0 ? stripped : stripped.substring(lastSpace + 1);
        if (candidate.isEmpty() || candidate.length() > 16) return null;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean valid = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '_';
            if (!valid) return null;
        }
        return candidate;
    }

    /**
     * Drop the "we've already alerted for these UUIDs" set. Called from
     * {@link Agent#disarm(boolean)} via reflection so the MC-loader copy of
     * Agent can reach this bootstrap-resident state without a compile-time
     * reference.
     */
    public static void clearNickAlerts() {
        alertedNicks.clear();
        skippedNicks.clear();
    }

    /**
     * Pull the UUID out of a {@code net.minecraft.client.network.NetworkPlayerInfo}
     * via reflection: {@code npi.getGameProfile().getId()}. Cached {@link Method}
     * handles so we don't pay lookup cost per frame.
     */
    private static UUID extractUuid(Object npi) {
        if (npi == null) return null;
        try {
            Method mA = npiGetGameProfile;
            if (mA == null) {
                mA = npi.getClass().getMethod("getGameProfile");
                npiGetGameProfile = mA;
            }
            Object profile = mA.invoke(npi);
            if (profile == null) return null;
            Method mB = gameProfileGetId;
            if (mB == null) {
                mB = profile.getClass().getMethod("getId");
                gameProfileGetId = mB;
            }
            return (UUID) mB.invoke(profile);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String formatStatsPrefix(BedwarsStats s) {
        return starColor(s.stars) + "[" + s.stars + "✫]" + RESET
                + " " + fkdrColor(s.fkdr) + "[" + String.format("%.2f", s.fkdr) + "]" + RESET;
    }

    /**
     * Standard Bedwars prestige palette. 0-99 stone, 100 iron, 200 gold,
     * 300 diamond, 400 emerald, 500 sapphire, 600 ruby, 700 crystal, 800 opal,
     * 900 amethyst, 1000+ rainbow (placeholder: solid gold — proper per-char
     * rainbow is an M8 concern).
     */
    private static String starColor(int stars) {
        if (stars < 100)  return C_GRAY;
        if (stars < 200)  return C_WHITE;
        if (stars < 300)  return C_GOLD;
        if (stars < 400)  return C_AQUA;
        if (stars < 500)  return C_DG;
        if (stars < 600)  return C_DA;
        if (stars < 700)  return C_DR;
        if (stars < 800)  return C_LP;
        if (stars < 900)  return C_BLUE;
        if (stars < 1000) return C_DP;
        return C_GOLD;
    }

    /** Skill tiers for FKDR: <1 gray, 1-3 white, 3-5 yellow, 5-10 red, 10+ dark red. */
    private static String fkdrColor(double fkdr) {
        if (fkdr < 1)  return C_GRAY;
        if (fkdr < 3)  return C_WHITE;
        if (fkdr < 5)  return C_YELLOW;
        if (fkdr < 10) return C_RED;
        return C_DR;
    }
}
