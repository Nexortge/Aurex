package com.aurex.agent;

import com.aurex.agent.api.ApiKeyConfig;
import com.aurex.agent.api.BedwarsStats;
import com.aurex.agent.api.HypixelClient;
import com.aurex.agent.api.StatsCache;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    private static final String C_GRAY  = "§7";
    private static final String C_WHITE = "§f";
    private static final String C_GOLD  = "§6";
    private static final String C_AQUA  = "§b";
    private static final String C_DG    = "§2"; // dark green — emerald
    private static final String C_DA    = "§3"; // dark aqua — sapphire
    private static final String C_DR    = "§4"; // dark red — ruby
    private static final String C_LP    = "§d"; // light purple — crystal
    private static final String C_BLUE  = "§9"; // blue — opal
    private static final String C_DP    = "§5"; // dark purple — amethyst
    private static final String RESET   = "§r";

    /** Shown while a fetch is in flight. Trailing space so it butts up against the original name. */
    private static final String PLACEHOLDER = C_GRAY + "[...]" + RESET + " ";

    /**
     * The singleton stats pipeline. {@code null} if no API key is configured —
     * in that case decoration is a no-op and Aurex runs silently.
     */
    private static final StatsCache statsCache = initStatsCache();

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
     *   <li>fetched but no Bedwars data (nicked / never played) → original
     *       name (M7 will tag with {@code [NICK]})</li>
     * </ul>
     *
     * Must NEVER throw — on error we return the original name so tab still renders.
     */
    public static String decorateInternal(String originalName, Object networkPlayerInfo, boolean fetchArmed) {
        try {
            if (statsCache == null) return originalName;
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
            if (stats == null) return originalName;    // nicked / never played — M7 handles
            return formatStatsPrefix(stats) + " " + originalName;
        } catch (Throwable t) {
            return originalName;
        }
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
        return starColor(s.stars) + "[" + s.stars + "✫ "
                + String.format("%.2f", s.fkdr) + "]" + RESET;
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
}
