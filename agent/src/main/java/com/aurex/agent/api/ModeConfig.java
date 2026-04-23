package com.aurex.agent.api;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-mode configuration — one file per game mode under
 * {@code %APPDATA%\Aurex\modes\&lt;mode&gt;.json}. Holds the columns to render
 * and the color ladder for each stat column.
 *
 * <p>Split out from {@link Config} so {@code AX-mode &lt;name&gt;} can swap
 * everything mode-scoped in one hot-reload without touching global settings
 * (apiKey, nickDetection, chatAlerts).
 *
 * <p>File shape:
 * <pre>
 * {
 *   "columns": ["stars", "name", "fkdr", "wl", "wins"],
 *   "headers": {
 *     "stars": "✫",
 *     "fkdr":  "FKDR",
 *     ...
 *   },
 *   "colors": {
 *     "stars": [{"min": 0, "color": "gray"}, {"min": 100, "color": "white"}, ...],
 *     "fkdr":  [{"min": 0, "color": "gray"}, {"min": 1, "color": "white"}, ...],
 *     ...
 *   },
 *   "alerts": {
 *     "fkdrThreshold":  5.0,
 *     "starsThreshold": 500
 *   }
 * }
 * </pre>
 *
 * <p><b>Self-documenting default:</b> the auto-generated {@code bedwars.json}
 * ships with a color ladder for <i>every</i> supported column, even ones not in
 * the default {@code columns} array. Users discover what they can toggle on by
 * reading {@code colors.*} in the file — no separate reference doc.
 *
 * <p>Never throws — parse errors go into an issues list and defaults fill in.
 */
public final class ModeConfig {

    /** Mode names we have defaults for. Drives {@code AX-mode list} + auto-generate. */
    public static final String MODE_BEDWARS = "bedwars";

    /** Default FKDR above which an opponent counts as a Bedwars threat. */
    public static final double BW_DEFAULT_FKDR_THRESHOLD = 5.0;
    /** Default stars above which an opponent counts as a Bedwars threat. */
    public static final int BW_DEFAULT_STARS_THRESHOLD = 500;

    private static final Set<String> KNOWN_MODES;
    static {
        LinkedHashSet<String> m = new LinkedHashSet<String>();
        m.add(MODE_BEDWARS);
        KNOWN_MODES = Collections.unmodifiableSet(m);
    }

    // --- hardcoded defaults (source of truth for auto-generate) ------------
    // Each inner array is {min (Number), color (String)}. Min must be numeric;
    // color is a name from ColorTier.NAMED_COLORS or the literal "rainbow".

    private static final Object[][] BW_COLUMNS = {{"stars"}, {"name"}, {"fkdr"}, {"wl"}, {"wins"}};

    /** Standard Bedwars prestige palette per 100 stars, rainbow at 1000+. */
    private static final Object[][] BW_STARS = {
            {0,    "gray"},
            {100,  "white"},
            {200,  "gold"},
            {300,  "aqua"},
            {400,  "dark_green"},
            {500,  "dark_aqua"},
            {600,  "dark_red"},
            {700,  "light_purple"},
            {800,  "blue"},
            {900,  "dark_purple"},
            {1000, "rainbow"},
    };
    /** FKDR skill ladder — pops red/gold for sweats. */
    private static final Object[][] BW_FKDR = {
            {0,   "gray"},
            {1,   "white"},
            {3,   "green"},
            {5,   "blue"},
            {10,  "light_purple"},
            {20,  "gold"},
            {50,  "red"},
            {100, "dark_red"},
    };
    /** W/L ladder — compressed vs FKDR because win/loss ratios peak lower. */
    private static final Object[][] BW_WL = {
            {0,    "gray"},
            {0.5,  "white"},
            {1,    "green"},
            {2,    "blue"},
            {5,    "light_purple"},
            {10,   "gold"},
            {20,   "red"},
    };
    /** Wins by magnitude — thousands-scale thresholds. */
    private static final Object[][] BW_WINS = {
            {0,     "gray"},
            {100,   "white"},
            {500,   "green"},
            {1000,  "blue"},
            {5000,  "light_purple"},
            {10000, "gold"},
            {25000, "red"},
    };
    /** Health 0-20 (whole hearts ×2). Quarter-ish splits. */
    private static final Object[][] BW_HEALTH = {
            {1,  "red"},
            {6,  "yellow"},
            {11, "dark_green"},
    };
    /** Finals — total final kills. Scales higher than wins since games yield multiple finals. */
    private static final Object[][] BW_FINALS = {
            {0,      "gray"},
            {500,    "white"},
            {2500,   "green"},
            {5000,   "blue"},
            {25000,  "light_purple"},
            {50000,  "gold"},
            {100000, "red"},
    };
    /** Beds broken — magnitude tracks wins (≈1 bed broken per win on average). */
    private static final Object[][] BW_BEDS = {
            {0,     "gray"},
            {100,   "white"},
            {500,   "green"},
            {1000,  "blue"},
            {5000,  "light_purple"},
            {10000, "gold"},
            {25000, "red"},
    };
    /** Current winstreak. Small integers — ladder topping out well below wins. */
    private static final Object[][] BW_WINSTREAK = {
            {0,   "gray"},
            {5,   "white"},
            {15,  "green"},
            {30,  "blue"},
            {50,  "light_purple"},
            {100, "gold"},
            {200, "red"},
    };
    /** Regular KDR — typically lower than FKDR since respawn deaths count. Mirrors W/L shape. */
    private static final Object[][] BW_KDR = {
            {0,    "gray"},
            {0.5,  "white"},
            {1,    "green"},
            {2,    "blue"},
            {5,    "light_purple"},
            {10,   "gold"},
            {20,   "red"},
    };
    /** Beds broken / beds lost. Typical defensive-player peak ~3, sweats 5+. */
    private static final Object[][] BW_BBLR = {
            {0,    "gray"},
            {0.5,  "white"},
            {1,    "green"},
            {2,    "blue"},
            {5,    "light_purple"},
            {10,   "gold"},
            {20,   "red"},
    };

    /**
     * Placeholder ladder for {@code tag} — Seraph supplies the actual §-codes
     * per tag (mapped from Cubelify RGB at parse time), so {@link ColorTier}
     * never runs against this column. The single-tier entry exists solely so
     * the self-documenting default {@code modes/bedwars.json} lists
     * {@code colors.tag} alongside the other columns (M11 discovery pattern).
     */
    private static final Object[][] BW_TAG = {
            {0, "white"},
    };

    /** All supported Bedwars-mode columns. Keys must match {@link Config} COL_* constants. */
    private static final Map<String, Object[][]> BW_COLOR_DEFAULTS;
    static {
        // LinkedHashMap — preserves insertion order so the generated JSON reads
        // stars → fkdr → wl → wins → health → finals → beds → winstreak → kdr → bblr.
        LinkedHashMap<String, Object[][]> m = new LinkedHashMap<String, Object[][]>();
        m.put(Config.COL_STARS,     BW_STARS);
        m.put(Config.COL_FKDR,      BW_FKDR);
        m.put(Config.COL_WL,        BW_WL);
        m.put(Config.COL_WINS,      BW_WINS);
        m.put(Config.COL_HEALTH,    BW_HEALTH);
        m.put(Config.COL_FINALS,    BW_FINALS);
        m.put(Config.COL_BEDS,      BW_BEDS);
        m.put(Config.COL_WINSTREAK, BW_WINSTREAK);
        m.put(Config.COL_KDR,       BW_KDR);
        m.put(Config.COL_BBLR,      BW_BBLR);
        m.put(Config.COL_TAG,       BW_TAG);
        BW_COLOR_DEFAULTS = Collections.unmodifiableMap(m);
    }

    /** Default column header labels. Keys must match {@link Config} COL_* constants. */
    private static final Map<String, String> BW_HEADER_DEFAULTS;
    static {
        // Terse labels — tab columns are narrow. Name column header stays "Name"
        // so the player-name column reads naturally even when it's the widest.
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        m.put(Config.COL_STARS,     "✫");
        m.put(Config.COL_NAME,      "Name");
        m.put(Config.COL_FKDR,      "FKDR");
        m.put(Config.COL_WL,        "W/L");
        m.put(Config.COL_WINS,      "Wins");
        m.put(Config.COL_HEALTH,    "HP");
        m.put(Config.COL_FINALS,    "Finals");
        m.put(Config.COL_BEDS,      "Beds");
        m.put(Config.COL_WINSTREAK, "WS");
        m.put(Config.COL_KDR,       "KDR");
        m.put(Config.COL_BBLR,      "BBLR");
        m.put(Config.COL_TAG,       "Tag");
        BW_HEADER_DEFAULTS = Collections.unmodifiableMap(m);
    }

    // --- instance --------------------------------------------------------

    public final String mode;
    public final List<String> columns;
    /** Keyed by column id. Lists sorted ascending by {@code min}. */
    public final Map<String, List<ColorTier>> colors;
    /** Column id → display label used for tab headers. Backfilled with built-in defaults. */
    public final Map<String, String> headers;
    /** Minimum FKDR to flag an opponent as a threat in the game-start report. */
    public final double fkdrThreshold;
    /** Minimum stars to flag an opponent as a threat in the game-start report. */
    public final int starsThreshold;

    private ModeConfig(String mode, List<String> columns,
                       Map<String, List<ColorTier>> colors,
                       Map<String, String> headers,
                       double fkdrThreshold, int starsThreshold) {
        this.mode = mode;
        this.columns = columns;
        this.colors = colors;
        this.headers = headers;
        this.fkdrThreshold = fkdrThreshold;
        this.starsThreshold = starsThreshold;
    }

    /** Header label for {@code col}, or the column id itself if no mapping exists. */
    public String headerFor(String col) {
        String h = headers.get(col);
        return h != null ? h : col;
    }

    /** Known / auto-generatable modes. Sorted for display. */
    public static String[] knownModes() {
        String[] arr = KNOWN_MODES.toArray(new String[0]);
        Arrays.sort(arr);
        return arr;
    }

    public static boolean isKnown(String mode) {
        return mode != null && KNOWN_MODES.contains(mode.toLowerCase());
    }

    /** {@code %APPDATA%\Aurex\modes\&lt;mode&gt;.json}. {@code null} on non-Windows. */
    public static Path resolveModePath(String mode) {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return Paths.get(appData, "Aurex", "modes", mode + ".json");
    }

    /**
     * Load from disk — auto-generate with defaults if the file is missing.
     * Never throws; any issue (I/O, parse, malformed entry) goes into {@code issues}
     * and the relevant section falls back to the default.
     */
    public static ModeConfig load(String mode, List<String> issues) {
        if (mode == null || mode.isEmpty()) {
            issues.add("mode name is empty — using bedwars");
            mode = MODE_BEDWARS;
        }
        mode = mode.toLowerCase();

        Path path = resolveModePath(mode);
        if (path == null) {
            // Non-Windows — return in-memory default, no disk round-trip.
            return defaultFor(mode);
        }

        if (!Files.exists(path)) {
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                JsonObject defJson = buildDefaultJson(mode);
                String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(defJson);
                Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
                issues.add("created default mode file: " + path);
            } catch (IOException e) {
                issues.add("could not write default mode file " + path + ": " + e.getMessage());
            }
            return defaultFor(mode);
        }

        JsonObject root;
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) {
                issues.add("mode " + mode + ": root must be a JSON object — using defaults");
                return defaultFor(mode);
            }
            root = el.getAsJsonObject();
        } catch (Exception e) {
            issues.add("mode " + mode + ": could not read/parse (" + e.getMessage() + ") — using defaults");
            return defaultFor(mode);
        }

        // Self-healing: if the file is missing any default color ladder (e.g. a
        // newer release added a column), patch them in and rewrite. Existing
        // user-customized entries are left untouched — we only add keys that
        // aren't there. Keeps the file self-documenting across updates.
        if (patchMissingDefaults(mode, root)) {
            try {
                String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(root);
                Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
                issues.add("mode " + mode + ": added new default color entries to " + path);
            } catch (IOException e) {
                issues.add("mode " + mode + ": could not update file " + path + ": " + e.getMessage());
            }
        }

        return parse(mode, root, issues);
    }

    /**
     * Add any missing default entries from {@link #BW_COLOR_DEFAULTS} and
     * {@link #BW_HEADER_DEFAULTS} to {@code root.colors} / {@code root.headers}.
     * Returns {@code true} if anything was added (caller should write the file back).
     * User-customized entries are preserved — we only inject keys that aren't present.
     */
    private static boolean patchMissingDefaults(String mode, JsonObject root) {
        if (!MODE_BEDWARS.equals(mode)) return false;
        boolean patched = false;

        JsonObject colors;
        if (root.has("colors") && root.get("colors").isJsonObject()) {
            colors = root.getAsJsonObject("colors");
        } else {
            colors = new JsonObject();
            root.add("colors", colors);
        }
        for (Map.Entry<String, Object[][]> e : BW_COLOR_DEFAULTS.entrySet()) {
            if (!colors.has(e.getKey())) {
                colors.add(e.getKey(), tiersToJson(e.getValue()));
                patched = true;
            }
        }

        JsonObject headers;
        if (root.has("headers") && root.get("headers").isJsonObject()) {
            headers = root.getAsJsonObject("headers");
        } else {
            headers = new JsonObject();
            root.add("headers", headers);
            patched = true;  // whole section was missing
        }
        for (Map.Entry<String, String> e : BW_HEADER_DEFAULTS.entrySet()) {
            if (!headers.has(e.getKey())) {
                headers.addProperty(e.getKey(), e.getValue());
                patched = true;
            }
        }

        // Alerts block — fkdr/stars threshold for the game-start threat report.
        // Same self-heal shape as headers: if missing, inject with defaults so
        // a pre-M14 user file becomes editable without a wipe.
        JsonObject alerts;
        if (root.has("alerts") && root.get("alerts").isJsonObject()) {
            alerts = root.getAsJsonObject("alerts");
        } else {
            alerts = new JsonObject();
            root.add("alerts", alerts);
            patched = true;
        }
        if (!alerts.has("fkdrThreshold")) {
            alerts.addProperty("fkdrThreshold", BW_DEFAULT_FKDR_THRESHOLD);
            patched = true;
        }
        if (!alerts.has("starsThreshold")) {
            alerts.addProperty("starsThreshold", BW_DEFAULT_STARS_THRESHOLD);
            patched = true;
        }
        return patched;
    }

    /** Build a JSON array of {min, color} tier objects from a default palette table. */
    private static JsonArray tiersToJson(Object[][] rows) {
        JsonArray tiers = new JsonArray();
        for (Object[] tier : rows) {
            JsonObject t = new JsonObject();
            t.addProperty("min", (Number) tier[0]);
            t.addProperty("color", (String) tier[1]);
            tiers.add(t);
        }
        return tiers;
    }

    /**
     * Produce an in-memory default for {@code mode}. Unknown modes get an empty
     * config (no columns, no colors) — the caller surfaces this as an issue.
     */
    public static ModeConfig defaultFor(String mode) {
        if (MODE_BEDWARS.equals(mode)) {
            List<String> cols = new ArrayList<String>();
            for (Object[] c : BW_COLUMNS) cols.add((String) c[0]);
            Map<String, List<ColorTier>> colors = new LinkedHashMap<String, List<ColorTier>>();
            List<String> sink = new ArrayList<String>();  // defaults never produce real issues
            for (Map.Entry<String, Object[][]> e : BW_COLOR_DEFAULTS.entrySet()) {
                colors.put(e.getKey(), buildTiers(e.getKey(), e.getValue(), sink));
            }
            Map<String, String> headers = new LinkedHashMap<String, String>(BW_HEADER_DEFAULTS);
            return new ModeConfig(mode,
                    Collections.unmodifiableList(cols),
                    Collections.unmodifiableMap(colors),
                    Collections.unmodifiableMap(headers),
                    BW_DEFAULT_FKDR_THRESHOLD, BW_DEFAULT_STARS_THRESHOLD);
        }
        return new ModeConfig(mode, Collections.<String>emptyList(),
                Collections.<String, List<ColorTier>>emptyMap(),
                Collections.<String, String>emptyMap(),
                BW_DEFAULT_FKDR_THRESHOLD, BW_DEFAULT_STARS_THRESHOLD);
    }

    /** Build the JSON tree for writing as the initial {@code modes/&lt;mode&gt;.json}. */
    public static JsonObject buildDefaultJson(String mode) {
        JsonObject root = new JsonObject();
        if (MODE_BEDWARS.equals(mode)) {
            JsonArray cols = new JsonArray();
            for (Object[] c : BW_COLUMNS) cols.add((String) c[0]);
            root.add("columns", cols);

            JsonObject headers = new JsonObject();
            for (Map.Entry<String, String> e : BW_HEADER_DEFAULTS.entrySet()) {
                headers.addProperty(e.getKey(), e.getValue());
            }
            root.add("headers", headers);

            JsonObject colors = new JsonObject();
            for (Map.Entry<String, Object[][]> e : BW_COLOR_DEFAULTS.entrySet()) {
                colors.add(e.getKey(), tiersToJson(e.getValue()));
            }
            root.add("colors", colors);

            JsonObject alerts = new JsonObject();
            alerts.addProperty("fkdrThreshold", BW_DEFAULT_FKDR_THRESHOLD);
            alerts.addProperty("starsThreshold", BW_DEFAULT_STARS_THRESHOLD);
            root.add("alerts", alerts);
        } else {
            // Unknown mode — produce an empty skeleton so the file exists and
            // the user can start filling it in. The load path flags it as
            // empty if they don't.
            root.add("columns", new JsonArray());
            root.add("headers", new JsonObject());
            root.add("colors", new JsonObject());
            root.add("alerts", new JsonObject());
        }
        return root;
    }

    // --- parsing ---------------------------------------------------------

    private static ModeConfig parse(String mode, JsonObject root, List<String> issues) {
        List<String> cols = parseColumns(mode, root, issues);
        Map<String, List<ColorTier>> colors = parseColors(mode, root, issues);
        Map<String, String> headers = parseHeaders(mode, root, issues);
        double[] alerts = parseAlerts(mode, root, issues);

        // Backfill any missing column tiers / headers with defaults — keeps old
        // user files forward-compatible when we add new stat columns to defaults.
        if (MODE_BEDWARS.equals(mode)) {
            List<String> sink = new ArrayList<String>();
            for (Map.Entry<String, Object[][]> e : BW_COLOR_DEFAULTS.entrySet()) {
                if (!colors.containsKey(e.getKey())) {
                    colors.put(e.getKey(), buildTiers(e.getKey(), e.getValue(), sink));
                }
            }
            for (Map.Entry<String, String> e : BW_HEADER_DEFAULTS.entrySet()) {
                if (!headers.containsKey(e.getKey())) {
                    headers.put(e.getKey(), e.getValue());
                }
            }
        }
        return new ModeConfig(mode,
                Collections.unmodifiableList(cols),
                Collections.unmodifiableMap(colors),
                Collections.unmodifiableMap(headers),
                alerts[0], (int) alerts[1]);
    }

    /**
     * Parse the per-mode {@code alerts} block — FKDR + stars thresholds for the
     * game-start threat report. Missing block or individual missing fields fall
     * back to {@link #BW_DEFAULT_FKDR_THRESHOLD} / {@link #BW_DEFAULT_STARS_THRESHOLD}.
     *
     * <p>Returns a two-element {@code double[]}: {@code [fkdrThreshold, starsThreshold]}.
     * Using a primitive array instead of a tuple class to keep surface area
     * small — only the parse → constructor path uses it.
     */
    private static double[] parseAlerts(String mode, JsonObject root, List<String> issues) {
        double fkdr = BW_DEFAULT_FKDR_THRESHOLD;
        int stars = BW_DEFAULT_STARS_THRESHOLD;
        if (!root.has("alerts") || root.get("alerts").isJsonNull()) {
            return new double[] { fkdr, stars };
        }
        JsonElement el = root.get("alerts");
        if (!el.isJsonObject()) {
            issues.add("mode " + mode + ": alerts must be an object — using defaults");
            return new double[] { fkdr, stars };
        }
        JsonObject obj = el.getAsJsonObject();
        if (obj.has("fkdrThreshold") && !obj.get("fkdrThreshold").isJsonNull()) {
            JsonElement v = obj.get("fkdrThreshold");
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                fkdr = v.getAsDouble();
            } else {
                issues.add("mode " + mode + ": alerts.fkdrThreshold must be a number — using default " + fkdr);
            }
        }
        if (obj.has("starsThreshold") && !obj.get("starsThreshold").isJsonNull()) {
            JsonElement v = obj.get("starsThreshold");
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                stars = v.getAsInt();
            } else {
                issues.add("mode " + mode + ": alerts.starsThreshold must be an integer — using default " + stars);
            }
        }
        return new double[] { fkdr, stars };
    }

    private static List<String> parseColumns(String mode, JsonObject root, List<String> issues) {
        if (!root.has("columns") || root.get("columns").isJsonNull()) {
            // Fall back to default columns for known modes, empty otherwise.
            return MODE_BEDWARS.equals(mode) ? defaultFor(mode).columns : Collections.<String>emptyList();
        }
        JsonElement el = root.get("columns");
        if (!el.isJsonArray()) {
            issues.add("mode " + mode + ": columns must be an array — using defaults");
            return MODE_BEDWARS.equals(mode) ? defaultFor(mode).columns : Collections.<String>emptyList();
        }
        JsonArray arr = el.getAsJsonArray();
        List<String> result = new ArrayList<String>(arr.size());
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                issues.add("mode " + mode + ": columns[" + i + "] not a string — skipped");
                continue;
            }
            String name = e.getAsString().trim().toLowerCase();
            if (!Config.isValidColumn(name)) {
                issues.add("mode " + mode + ": unknown column \"" + name + "\" — skipped");
                continue;
            }
            if (!seen.add(name)) {
                issues.add("mode " + mode + ": duplicate column \"" + name + "\" — skipped");
                continue;
            }
            result.add(name);
        }
        if (result.isEmpty()) {
            issues.add("mode " + mode + ": columns list empty — using defaults");
            return MODE_BEDWARS.equals(mode) ? defaultFor(mode).columns : Collections.<String>emptyList();
        }
        return result;
    }

    private static Map<String, String> parseHeaders(String mode, JsonObject root, List<String> issues) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!root.has("headers") || root.get("headers").isJsonNull()) return out;
        JsonElement el = root.get("headers");
        if (!el.isJsonObject()) {
            issues.add("mode " + mode + ": headers must be an object — ignored");
            return out;
        }
        JsonObject obj = el.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String colId = entry.getKey().trim().toLowerCase();
            if (!Config.isValidColumn(colId)) {
                issues.add("mode " + mode + ": headers contains unknown column \"" + colId + "\" — ignored");
                continue;
            }
            JsonElement v = entry.getValue();
            if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                issues.add("mode " + mode + ": headers." + colId + " must be a string — ignored");
                continue;
            }
            out.put(colId, v.getAsString());
        }
        return out;
    }

    private static Map<String, List<ColorTier>> parseColors(String mode, JsonObject root, List<String> issues) {
        Map<String, List<ColorTier>> out = new LinkedHashMap<String, List<ColorTier>>();
        if (!root.has("colors") || root.get("colors").isJsonNull()) return out;
        JsonElement el = root.get("colors");
        if (!el.isJsonObject()) {
            issues.add("mode " + mode + ": colors must be an object — ignored");
            return out;
        }
        JsonObject obj = el.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String colId = entry.getKey().trim().toLowerCase();
            if (!Config.isValidColumn(colId)) {
                issues.add("mode " + mode + ": colors contains unknown column \"" + colId + "\" — ignored");
                continue;
            }
            JsonElement tiersEl = entry.getValue();
            if (tiersEl == null || !tiersEl.isJsonArray()) {
                issues.add("mode " + mode + ": colors." + colId + " must be an array — ignored");
                continue;
            }
            List<ColorTier> tiers = parseTierArray(mode, colId, tiersEl.getAsJsonArray(), issues);
            if (!tiers.isEmpty()) {
                out.put(colId, tiers);
            }
        }
        return out;
    }

    private static List<ColorTier> parseTierArray(String mode, String colId, JsonArray arr, List<String> issues) {
        List<ColorTier> tiers = new ArrayList<ColorTier>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement te = arr.get(i);
            String label = "mode " + mode + ": colors." + colId + "[" + i + "]";
            if (te == null || !te.isJsonObject()) {
                issues.add(label + " not an object — skipped");
                continue;
            }
            JsonObject to = te.getAsJsonObject();
            if (!to.has("min") || to.get("min").isJsonNull()
                    || !to.get("min").isJsonPrimitive()
                    || !to.get("min").getAsJsonPrimitive().isNumber()) {
                issues.add(label + ".min must be a number — skipped");
                continue;
            }
            double min = to.get("min").getAsDouble();
            String rawColor = to.has("color") && to.get("color").isJsonPrimitive()
                    ? to.get("color").getAsString() : null;
            ColorTier t = ColorTier.parse(label, min, rawColor, issues);
            if (t != null) tiers.add(t);
        }
        Collections.sort(tiers, TIER_ORDER);
        return tiers;
    }

    private static List<ColorTier> buildTiers(String colId, Object[][] rows, List<String> issues) {
        List<ColorTier> tiers = new ArrayList<ColorTier>(rows.length);
        for (int i = 0; i < rows.length; i++) {
            Object[] row = rows[i];
            double min = ((Number) row[0]).doubleValue();
            String color = (String) row[1];
            ColorTier t = ColorTier.parse("default." + colId + "[" + i + "]", min, color, issues);
            if (t != null) tiers.add(t);
        }
        Collections.sort(tiers, TIER_ORDER);
        return Collections.unmodifiableList(tiers);
    }

    private static final Comparator<ColorTier> TIER_ORDER = new Comparator<ColorTier>() {
        @Override public int compare(ColorTier a, ColorTier b) {
            return Double.compare(a.min, b.min);
        }
    };
}
