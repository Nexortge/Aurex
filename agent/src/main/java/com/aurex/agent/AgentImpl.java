package com.aurex.agent;

import com.aurex.agent.api.BedwarsStats;
import com.aurex.agent.api.Config;
import com.aurex.agent.api.HypixelClient;
import com.aurex.agent.api.StatsCache;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
     * Current config snapshot. Replaced atomically by {@link #onServerJoin()}.
     * Render-thread code reads this into a local and treats it as immutable for
     * the duration of one row / frame — never holds a reference across blocking
     * boundaries.
     */
    private static volatile Config config = Config.load();

    /**
     * Initial API key at startup. Used by {@link #onServerJoin()} to detect
     * when the user has changed {@code apiKey} mid-session — we can't swap
     * out the running {@link HypixelClient}, so we warn and tell them to restart.
     */
    private static final String initialApiKey = config.apiKey;

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

    // Reflection cache for NetworkPlayerInfo#getGameProfile()#getId() / getName().
    // Can't compile-time reference net.minecraft.* from bootstrap — MC classes
    // live on Lunar's MC loader, not bootstrap — so reflection is the only
    // loader-agnostic way in.
    private static volatile Method npiGetGameProfile;
    private static volatile Method gameProfileGetId;
    private static volatile Method gameProfileGetName;

    // Vanilla-name-formatting reflection cache. Used to reproduce
    // GuiPlayerTabOverlay.getPlayerName without going through our own M4
    // decorator — so ranks + team colors survive the M8 table render.
    private static volatile Method npiGetDisplayName;
    private static volatile Method npiGetPlayerTeam;
    private static volatile Method componentGetFormattedText;
    private static volatile Method teamGetRegisteredName;
    private static volatile Method scorePlayerTeamFormatPlayerName;
    private static volatile Class<?> spTeamClass;
    private static volatile Class<?> teamInterfaceClass;

    // Scoreboard-objective reflection cache (health column). Vanilla's tab
    // renders hearts from the objective in display slot 0; we pull the same
    // value and show it as a number.
    private static volatile Method scoreboardGetValueFromObjective;
    private static volatile Method scoreGetScorePoints;

    // --- M9 column schema --------------------------------------------------
    // Column identifiers match Config.COL_* constants. Header + cell formatters
    // live together in column() below — adding a new column is one case branch.

    /** Cell shown when stats are not yet known (armed + fetching, or fetch idle). */
    private static final String CELL_PLACEHOLDER = C_GRAY + "[...]" + RESET;
    /** Cell shown when lookup finished but player has no Bedwars data (nicked / never played). */
    private static final String CELL_UNKNOWN     = C_GRAY + "—" + RESET;

    private AgentImpl() {}

    private static StatsCache initStatsCache() {
        try {
            for (String issue : config.issues) {
                Agent.log("config: " + issue);
            }
            String key = config.apiKey;
            if (key == null) {
                Agent.log("stats: no API key (HYPIXEL_API_KEY env or %APPDATA%\\Aurex\\config.json); running without stats");
                return null;
            }
            Agent.log("stats: API key loaded; pipeline ready (columns=" + config.columns
                    + " nicks=" + config.nickDetection + " alerts=" + config.chatAlerts + ")");
            return new StatsCache(new HypixelClient(key, Agent::log));
        } catch (Throwable t) {
            Agent.log("stats: init failed, running without stats: " + t);
            return null;
        }
    }

    /**
     * Fires on every server/world join (hooked via {@link com.aurex.agent.JoinGameTransformer}).
     * Reloads {@link #config} from disk. This is the only reload path — no
     * polling, no file watcher.
     *
     * <p><b>Chat policy:</b> silent on success. Only posts in-game when
     * something needs user attention — parse issues (unknown columns etc.) or
     * an apiKey change that needs a restart. Successful reloads go to the log
     * only. Rationale: Hypixel Bungee fires handleJoinGame on every
     * lobby/sub-server hop; a success line per hop would be spam, but a warning
     * per hop while the user fixes their config is actually useful.
     *
     * <p>API key changes can't take effect mid-session (the {@link HypixelClient}
     * + {@link StatsCache} are bound to the startup key), so we detect and
     * warn if {@code apiKey} differs from startup.
     *
     * <p>Never throws — runs off the render thread but still safety-wrapped so
     * a malformed config can't break world-load.
     */
    public static void onServerJoin() {
        try {
            Config fresh = Config.load();
            config = fresh;

            Agent.log("config reloaded (" + fresh.columns.size() + " cols"
                    + ", nicks=" + fresh.nickDetection
                    + ", alerts=" + fresh.chatAlerts
                    + ", issues=" + fresh.issues.size() + ")");

            for (String issue : fresh.issues) {
                Agent.log("config: " + issue);
                Agent.sendClientChat("§e[AX] config: " + issue, capturedMcLoader);
            }

            boolean keyChanged = initialApiKey == null
                    ? fresh.apiKey != null
                    : !initialApiKey.equals(fresh.apiKey);
            if (keyChanged) {
                Agent.sendClientChat("§c[AX] apiKey changed — restart Lunar to apply",
                        capturedMcLoader);
                Agent.log("config: apiKey changed since startup — restart required");
            }
        } catch (Throwable t) {
            Agent.log("onServerJoin failed: " + t);
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
        Config cfg = config;
        if (!cfg.nickDetection) return originalName;

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
            if (cfg.chatAlerts) {
                Agent.sendClientChat(C_DR + "[AX] -> " + alertName + " is nicked!" + RESET,
                        capturedMcLoader);
            }
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
     * Build header/column metadata + one row per viable {@code NetworkPlayerInfo}.
     * Called from {@link Agent#getTabData(Object[], Object, Object)} via the
     * standard bootstrap hop.
     *
     * <p><b>Return shape:</b> {@code Object[3] = {
     *   String[] colIds,
     *   String[] headers,
     *   List<Object[]> rowEntries    // each entry = {Object npi, String[] cells}
     * }}. All types are {@code java.*} so the MC loader can read them without
     * resolving api.* or gson.*. {@code colIds} lets the renderer identify the
     * Name column (to draw the head icon next to it) without string-matching
     * the human header.
     *
     * <p>Column count + order is driven entirely by {@link Config#columns} —
     * each row's {@code cells} array has one entry per column in matching order,
     * §-codes already baked in.
     *
     * <p>{@code scoreboard} + {@code objective} are the MC objects from display
     * slot 0 (tab list slot) — may be {@code null} if the server hasn't set
     * either. Used for the {@code health} column.
     *
     * <p>Filters: non-v4 UUIDs (NPCs per
     * {@code memory/project_hypixel_tab_hazards.md}) and null names.
     *
     * <p>Sort: by scoreboard team first (groups team colors / rank tiers),
     * then NICK → REAL by FKDR desc → PLACEHOLDER → UNKNOWN. Mirrors Seraph.
     *
     * <p>Side effect: for cache-miss + {@code fetchArmed}, kicks off the same
     * async fetch {@link #decorateInternal} did. Since M8 bypasses the vanilla
     * {@code getPlayerName} path, this is now the only place new fetches
     * originate from the render thread.
     */
    /**
     * Eager fetch pre-warm, decoupled from the render path. Called from
     * {@link Agent#arm()} and {@link Agent#autoArm()} so stats start loading
     * the moment the arm fires — no TAB hold required.
     *
     * <p>Same filters + side effects as the per-NPI branch of
     * {@link #getTabData}: v4-UUID check (skip NPCs), {@link StatsCache#get}
     * kickoff. Dedup is handled inside {@code StatsCache} — repeat calls for
     * the same UUID while a fetch is in flight return the existing future, so
     * calling this on every tick of a 5-second countdown is safe.
     *
     * <p>Returns silently on any failure — arm must never be able to crash the
     * client. Only logs a single summary line per call so a full 100-player
     * lobby doesn't produce 100 log entries.
     */
    public static void preWarmFetches(Object[] npis) {
        if (npis == null || statsCache == null) return;
        try {
            int kicked = 0;
            for (Object npi : npis) {
                if (npi == null) continue;
                if (capturedMcLoader == null) capturedMcLoader = npi.getClass().getClassLoader();
                UUID uuid = extractUuid(npi);
                if (uuid == null) continue;
                if (uuid.version() != 4) continue;  // NPC filter (same as getTabData)
                // StatsCache.get dedups in-flight requests AND serves cached
                // results instantly — so this is a no-op for UUIDs we already
                // have fresh data for, and a no-double-fire for UUIDs whose
                // request started on a previous arm tick.
                statsCache.get(uuid);
                kicked++;
            }
            if (kicked > 0) Agent.log("preWarmFetches: kicked " + kicked + " fetch(es)");
        } catch (Throwable t) {
            Agent.log("preWarmFetches failed: " + t);
        }
    }

    public static Object[] getTabData(Object[] npis, Object scoreboard, Object objective, boolean fetchArmed) {
        Config cfg = config;
        String[] colIds = cfg.columns.toArray(new String[0]);
        String[] headers = buildHeaders(cfg.columns);
        if (npis == null || statsCache == null) {
            return new Object[] { colIds, headers, Collections.emptyList() };
        }
        try {
            List<RawRow> raws = new ArrayList<RawRow>(npis.length);
            for (Object npi : npis) {
                if (npi == null) continue;
                if (capturedMcLoader == null) capturedMcLoader = npi.getClass().getClassLoader();

                UUID uuid = extractUuid(npi);
                if (uuid == null) continue;
                if (uuid.version() != 4) continue;  // NPC filter (M7 learning)

                String name = extractName(npi);
                if (name == null) continue;

                int hp = extractHealth(name, scoreboard, objective);
                raws.add(buildRawRow(npi, uuid, name, hp, fetchArmed, cfg));
            }

            raws.sort(ROW_ORDER);

            List<Object[]> rowEntries = new ArrayList<Object[]>(raws.size());
            for (RawRow r : raws) {
                rowEntries.add(new Object[] { r.npi, formatRow(r, cfg.columns) });
            }
            return new Object[] { colIds, headers, rowEntries };
        } catch (Throwable t) {
            Agent.log("getTabData failed: " + t);
            return new Object[] { colIds, headers, Collections.emptyList() };
        }
    }

    /**
     * Pull the player's tab-slot scoreboard value (hearts on Hypixel Bedwars).
     * Returns {@code -1} if the server hasn't set a tab objective, or if this
     * player isn't tracked by it.
     *
     * <p>Mirrors what {@code GuiPlayerTabOverlay.drawScoreboardValues} reads
     * internally — we just interpret the score as a number instead of drawing
     * hearts. Hypixel Bedwars sets the score to the player's current HP
     * (0-20); pre-game lobbies leave the objective null, which we show as "—".
     *
     * <p>Reflection is mandatory — {@code Scoreboard} / {@code ScoreObjective}
     * / {@code Score} all live on Lunar's MC loader, we're on bootstrap.
     */
    private static int extractHealth(String rawName, Object scoreboard, Object objective) {
        if (scoreboard == null || objective == null || rawName == null) return -1;
        try {
            Method mGet = scoreboardGetValueFromObjective;
            if (mGet == null) {
                mGet = scoreboard.getClass().getMethod("getValueFromObjective",
                        String.class, objective.getClass());
                scoreboardGetValueFromObjective = mGet;
            }
            Object score = mGet.invoke(scoreboard, rawName, objective);
            if (score == null) return -1;

            Method mPts = scoreGetScorePoints;
            if (mPts == null) {
                mPts = score.getClass().getMethod("getScorePoints");
                scoreGetScorePoints = mPts;
            }
            return (Integer) mPts.invoke(score);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String[] buildHeaders(List<String> cols) {
        String[] h = new String[cols.size()];
        for (int i = 0; i < cols.size(); i++) h[i] = headerFor(cols.get(i));
        return h;
    }

    private static String headerFor(String col) {
        switch (col) {
            case Config.COL_STARS:  return "✫";
            case Config.COL_NAME:   return "Name";
            case Config.COL_FKDR:   return "FKDR";
            case Config.COL_WL:     return "W/L";
            case Config.COL_WINS:   return "Wins";
            case Config.COL_HEALTH: return "HP";
            default: return col;  // unreachable — Config filtered already
        }
    }

    /** Row status — drives sort + formatting branches. */
    private enum RowStatus { REAL, NICK, PLACEHOLDER, UNKNOWN }

    /**
     * Intermediate per-row struct pre-formatting. Never escapes this class.
     *
     * <p>{@code rawName} = plain 16-char MC username (for nick alerts + logs).
     * {@code displayName} = formatted string with rank prefix + team color baked
     * in, via vanilla's {@code getPlayerName} logic. {@code teamKey} is the
     * scoreboard team's registered name, used as the primary sort axis so we
     * match vanilla's team-grouped ordering (which is what gives Hypixel its
     * rank-stratified tab list).
     */
    private static final class RawRow {
        final Object npi;
        final UUID uuid;
        final String rawName;
        final String displayName;
        final String teamKey;
        final BedwarsStats stats;   // null unless REAL
        final RowStatus status;
        final int health;           // 0-20 from scoreboard; -1 if untracked
        RawRow(Object npi, UUID uuid, String rawName, String displayName, String teamKey,
               BedwarsStats stats, RowStatus status, int health) {
            this.npi = npi;
            this.uuid = uuid; this.rawName = rawName; this.displayName = displayName;
            this.teamKey = teamKey; this.stats = stats; this.status = status;
            this.health = health;
        }
    }

    private static RawRow buildRawRow(Object npi, UUID uuid, String rawName, int health,
                                      boolean fetchArmed, Config cfg) {
        String display = extractDisplayName(npi, rawName);
        String teamKey = extractTeamKey(npi);

        CompletableFuture<BedwarsStats> fut = statsCache.peekFuture(uuid);
        if (fut == null) {
            if (fetchArmed) {
                statsCache.get(uuid);   // kick off async fetch
                return new RawRow(npi, uuid, rawName, display, teamKey, null, RowStatus.PLACEHOLDER, health);
            }
            return new RawRow(npi, uuid, rawName, display, teamKey, null, RowStatus.UNKNOWN, health);
        }
        if (!fut.isDone()) return new RawRow(npi, uuid, rawName, display, teamKey, null, RowStatus.PLACEHOLDER, health);
        if (fut.isCompletedExceptionally()) return new RawRow(npi, uuid, rawName, display, teamKey, null, RowStatus.UNKNOWN, health);

        BedwarsStats stats = fut.getNow(null);
        if (stats == null) {
            // Nick / never-played. If nick detection is off, present as UNKNOWN
            // (no [NICK] tag, no alert). If on but chat alerts are off, tag the
            // row but skip the chat line. Alert dedup is shared with the
            // decorateInternal path via alertedNicks so we never double-fire.
            if (!cfg.nickDetection) {
                return new RawRow(npi, uuid, rawName, display, teamKey, null, RowStatus.UNKNOWN, health);
            }
            if (alertedNicks.add(uuid)) {
                Agent.log("nick detected (table): name='" + rawName + "' uuid=" + uuid);
                if (cfg.chatAlerts) {
                    Agent.sendClientChat(C_DR + "[AX] -> " + rawName + " is nicked!" + RESET,
                            capturedMcLoader);
                }
            }
            return new RawRow(npi, uuid, rawName, display, teamKey, null, RowStatus.NICK, health);
        }
        return new RawRow(npi, uuid, rawName, display, teamKey, stats, RowStatus.REAL, health);
    }

    /**
     * Sort: by scoreboard team name first (groups team colors together, matches
     * vanilla's rank-stratified lobby order), then within a team NICK on top,
     * REAL by FKDR desc, then loading/unknown.
     *
     * <p>In Bedwars matches this groups red/blue/green/yellow teams. In lobbies
     * where there are no game teams, Hypixel assigns scoreboard teams by rank
     * ([MVP++] before [MVP+] before default) so team-key sort reproduces the
     * vanilla rank stratification users expect.
     */
    private static final Comparator<RawRow> ROW_ORDER = new Comparator<RawRow>() {
        @Override public int compare(RawRow a, RawRow b) {
            int t = a.teamKey.compareTo(b.teamKey);
            if (t != 0) return t;
            int ra = rank(a.status), rb = rank(b.status);
            if (ra != rb) return ra - rb;
            if (a.status == RowStatus.REAL) {
                return Double.compare(b.stats.fkdr, a.stats.fkdr);  // desc within team
            }
            return a.rawName.compareToIgnoreCase(b.rawName);
        }
        private int rank(RowStatus s) {
            switch (s) {
                case NICK: return 0;
                case REAL: return 1;
                case PLACEHOLDER: return 2;
                default: return 3; // UNKNOWN
            }
        }
    };

    /**
     * Produce cells in config column order, §-codes baked in.
     *
     * <p>Name column uses the vanilla-formatted display name (rank prefix +
     * team color already there); we append RESET so the next cell starts clean.
     * All other cells branch on {@link RowStatus} — REAL gets live stat
     * formatting, NICK/PLACEHOLDER/UNKNOWN get a single fallback glyph.
     */
    private static String[] formatRow(RawRow r, List<String> cols) {
        String[] cells = new String[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            cells[i] = formatCell(cols.get(i), r);
        }
        return cells;
    }

    private static String formatCell(String col, RawRow r) {
        String nameCell = r.displayName + RESET;

        // Health is independent of API status — a PLACEHOLDER row (stats still
        // fetching) can still render HP as long as the scoreboard objective
        // exists. Handle it before the status-switch.
        if (col.equals(Config.COL_HEALTH)) return formatHealth(r.health);

        switch (r.status) {
            case REAL: {
                BedwarsStats s = r.stats;
                switch (col) {
                    case Config.COL_STARS: return starColor(s.stars) + "[" + s.stars + "✫]" + RESET;
                    case Config.COL_NAME:  return nameCell;
                    case Config.COL_FKDR:  return fkdrColor(s.fkdr) + "[" + String.format("%.2f", s.fkdr) + "]" + RESET;
                    case Config.COL_WL:    return C_WHITE + String.format("%.2f", s.wlr) + RESET;
                    case Config.COL_WINS:  return C_WHITE + formatInt(s.wins) + RESET;
                    default: return CELL_UNKNOWN;
                }
            }
            case NICK:
                // [NICK] occupies the stars column by convention (leftmost
                // stat slot); other stat cells show as dash-unknown. Name is
                // always the rendered name regardless.
                if (col.equals(Config.COL_NAME)) return nameCell;
                if (col.equals(Config.COL_STARS)) return C_DR + "[NICK]" + RESET;
                return CELL_UNKNOWN;
            case PLACEHOLDER:
                if (col.equals(Config.COL_NAME)) return nameCell;
                return CELL_PLACEHOLDER;
            case UNKNOWN:
            default:
                if (col.equals(Config.COL_NAME)) return nameCell;
                return CELL_UNKNOWN;
        }
    }

    /**
     * HP cell: coloured 0-20 integer. The raw scoreboard score is the HP
     * directly — Hypixel mirrors MC's vanilla health criterion, whole
     * numbers only. Tiers: {@code <= 5} red, {@code <= 10} yellow,
     * {@code > 10} green. Raw {@code -1} → unknown dash (objective not set,
     * or player not tracked — typical in Hypixel lobbies).
     */
    private static String formatHealth(int hp) {
        if (hp < 0) return CELL_UNKNOWN;
        String color;
        if (hp <= 5)      color = C_RED;
        else if (hp <= 10) color = C_YELLOW;
        else              color = C_DG;  // dark green — matches Bedwars palette
        return color + hp + RESET;
    }

    /** 1234 → "1,234". Locale-agnostic, thousands separator only. */
    private static String formatInt(int n) {
        return String.format("%,d", n);
    }

    /**
     * Vanilla-equivalent formatted display name for the Name column. Mirrors
     * {@code GuiPlayerTabOverlay.getPlayerName} decision tree:
     * <ol>
     *   <li>If {@code npi.getDisplayName()} is non-null (Hypixel sets this with
     *       the full {@code §9[MVP§d++§9] §cNexort} string) — return its
     *       formatted text.</li>
     *   <li>Else if a scoreboard team is assigned — run the raw name through
     *       {@code ScorePlayerTeam.formatPlayerName(team, raw)} to get
     *       {@code prefix + name + suffix} with team color.</li>
     *   <li>Else — return the raw username.</li>
     * </ol>
     *
     * <p>We bypass vanilla's {@code getPlayerName} directly because that method
     * is patched by our M4 transformer to call {@code decorateName}, which would
     * append a stats prefix — exactly what we're already rendering in separate
     * columns. Replicating the upstream logic skips the decorator cleanly.
     *
     * <p>Never throws — falls back to {@code rawName} on any reflection failure.
     */
    private static String extractDisplayName(Object npi, String rawName) {
        try {
            Method mDisp = npiGetDisplayName;
            if (mDisp == null) {
                mDisp = npi.getClass().getMethod("getDisplayName");
                npiGetDisplayName = mDisp;
            }
            Object comp = mDisp.invoke(npi);
            if (comp != null) {
                Method mFmt = componentGetFormattedText;
                if (mFmt == null) {
                    mFmt = comp.getClass().getMethod("getFormattedText");
                    componentGetFormattedText = mFmt;
                }
                Object txt = mFmt.invoke(comp);
                if (txt != null) return (String) txt;
            }
            Object team = extractTeam(npi);
            ClassLoader loader = capturedMcLoader;
            if (team != null && loader != null) {
                Class<?> spt = spTeamClass;
                if (spt == null) {
                    spt = Class.forName("net.minecraft.scoreboard.ScorePlayerTeam", true, loader);
                    spTeamClass = spt;
                }
                Class<?> tif = teamInterfaceClass;
                if (tif == null) {
                    tif = Class.forName("net.minecraft.scoreboard.Team", true, loader);
                    teamInterfaceClass = tif;
                }
                Method mFmtName = scorePlayerTeamFormatPlayerName;
                if (mFmtName == null) {
                    mFmtName = spt.getMethod("formatPlayerName", tif, String.class);
                    scorePlayerTeamFormatPlayerName = mFmtName;
                }
                Object res = mFmtName.invoke(null, team, rawName);
                if (res != null) return (String) res;
            }
        } catch (Throwable t) {
            // swallow; fall back to rawName
        }
        return rawName;
    }

    /** {@code npi.getPlayerTeam()}. Null if the player isn't in a scoreboard team. */
    private static Object extractTeam(Object npi) {
        try {
            Method m = npiGetPlayerTeam;
            if (m == null) {
                m = npi.getClass().getMethod("getPlayerTeam");
                npiGetPlayerTeam = m;
            }
            return m.invoke(npi);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Scoreboard team's registered name for sort ordering. Empty string if the
     * player has no team — keeps untagged players together. Team names on
     * Hypixel are typically prefixed with priority markers (e.g. {@code "0000_MVPPP"})
     * so lexicographic sort reproduces the rank-stratified vanilla order.
     */
    private static String extractTeamKey(Object npi) {
        Object team = extractTeam(npi);
        if (team == null) return "";
        try {
            Method m = teamGetRegisteredName;
            if (m == null) {
                m = team.getClass().getMethod("getRegisteredName");
                teamGetRegisteredName = m;
            }
            Object name = m.invoke(team);
            return name != null ? (String) name : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Read the player's raw username from {@code npi.getGameProfile().getName()}.
     * Distinct from {@code getPlayerName(npi)} — that returns the team-formatted
     * display name (with rank prefix baked in). For the table's Name column we
     * want the plain 16-char username.
     */
    private static String extractName(Object npi) {
        if (npi == null) return null;
        try {
            Method mA = npiGetGameProfile;
            if (mA == null) {
                mA = npi.getClass().getMethod("getGameProfile");
                npiGetGameProfile = mA;
            }
            Object profile = mA.invoke(npi);
            if (profile == null) return null;
            Method mB = gameProfileGetName;
            if (mB == null) {
                mB = profile.getClass().getMethod("getName");
                gameProfileGetName = mB;
            }
            return (String) mB.invoke(profile);
        } catch (Throwable t) {
            return null;
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
        return starColor(s.stars) + "[" + s.stars + "✫]" + RESET
                + " " + fkdrColor(s.fkdr) + "[" + String.format("%.2f", s.fkdr) + "]" + RESET;
    }

    /**
     * Standard Bedwars prestige palette. 0-99 stone, 100 iron, 200 gold,
     * 300 diamond, 400 emerald, 500 sapphire, 600 ruby, 700 crystal, 800 opal,
     * 900 amethyst, 1000+ rainbow (placeholder: solid gold — proper per-char
     * rainbow is M11 work, tracked alongside FKDR/W/L/Wins coloring).
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
