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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of the Aurex config tree.
 *
 * <p>As of M11 the config is split across two files:
 * <ul>
 *   <li>{@code %APPDATA%\Aurex\config.json} — global settings (apiKey,
 *       activeMode, nickDetection, chatAlerts). Identity-scoped settings that
 *       don't change when the user swaps game mode.</li>
 *   <li>{@code %APPDATA%\Aurex\modes\&lt;mode&gt;.json} — per-mode settings
 *       (columns, colors). Swapped wholesale via {@code AX-mode &lt;name&gt;}.</li>
 * </ul>
 *
 * <p>{@link #load()} reads both files, auto-generates defaults for either if
 * missing, and returns one merged snapshot. AgentImpl treats this object as
 * immutable — a mode switch produces a fresh instance via {@link #load()}.
 *
 * <p><b>API key precedence:</b> {@code HYPIXEL_API_KEY} env var wins over the
 * file. File's {@code apiKey} is only read when the env var is unset.
 *
 * <p><b>Parse errors are non-fatal</b> — anything malformed goes into
 * {@link #issues} and defaults apply. The Agent flushes these to chat on
 * server-join so the user sees what's wrong without having to check a log.
 *
 * <p><b>Thread-safe:</b> instances are immutable; {@link #load()} is idempotent
 * from any thread.
 */
public final class Config {

    public static final String COL_STARS     = "stars";
    public static final String COL_NAME      = "name";
    public static final String COL_FKDR      = "fkdr";
    public static final String COL_WL        = "wl";
    public static final String COL_WINS      = "wins";
    public static final String COL_HEALTH    = "health";
    public static final String COL_FINALS    = "finals";
    public static final String COL_BEDS      = "beds";
    public static final String COL_WINSTREAK = "winstreak";
    public static final String COL_KDR       = "kdr";
    public static final String COL_BBLR      = "bblr";
    /** M15 — Seraph Cubelify blacklist/bot/member tag, first-wins. Empty when Seraph has no flags. */
    public static final String COL_TAG       = "tag";

    private static final Set<String> VALID_COLUMNS;
    private static final Set<String> RECOGNIZED_KEYS;
    static {
        LinkedHashSet<String> valid = new LinkedHashSet<String>();
        valid.add(COL_STARS);  valid.add(COL_NAME);      valid.add(COL_FKDR);
        valid.add(COL_WL);     valid.add(COL_WINS);      valid.add(COL_HEALTH);
        valid.add(COL_FINALS); valid.add(COL_BEDS);      valid.add(COL_WINSTREAK);
        valid.add(COL_KDR);    valid.add(COL_BBLR);
        valid.add(COL_TAG);
        VALID_COLUMNS = Collections.unmodifiableSet(valid);

        LinkedHashSet<String> keys = new LinkedHashSet<String>(Arrays.asList(
                "apiKey", "seraphApiKey", "activeMode", "nickDetection", "chatAlerts", "ignoreList"));
        RECOGNIZED_KEYS = Collections.unmodifiableSet(keys);
    }

    /** Used by ModeConfig to validate user-specified column ids. */
    public static boolean isValidColumn(String name) {
        return name != null && VALID_COLUMNS.contains(name);
    }

    private static final String ENV_VAR = "HYPIXEL_API_KEY";
    private static final String APP_DIR = "Aurex";
    private static final String FILE_NAME = "config.json";
    private static final String DEFAULT_MODE = ModeConfig.MODE_BEDWARS;

    /** {@code %APPDATA%\Aurex\config.json}. {@code null} on non-Windows. */
    public static Path resolveConfigPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return Paths.get(appData, APP_DIR, FILE_NAME);
    }

    // --- instance --------------------------------------------------------

    public final String apiKey;
    /**
     * Seraph API key for the M15 cheater-flag / client-tag integration. {@code
     * null} when unset — in that case {@link com.aurex.agent.AgentImpl} runs
     * without Seraph entirely (no network traffic, empty tag/client columns).
     *
     * <p>Lives in the global config because the key is identity-scoped, not
     * per-mode. Rotated via the {@code AX-seraph <key>} chat command
     * ({@link #writeSeraphApiKey}).
     */
    public final String seraphApiKey;
    public final String activeMode;
    public final boolean nickDetection;
    public final boolean chatAlerts;
    /**
     * Lowercased usernames excluded from the M14 threat report. Typically the
     * user's own alts. Populated via {@code AX-ignore}/{@code AX-removeignore}.
     * Empty set when unset — never null. Comparison is case-insensitive via the
     * stored strings being pre-lowered.
     *
     * <p>Lives in the global config (not per-mode) because an alt list is
     * identity-scoped — you're the same person in Bedwars and SkyWars.
     */
    public final Set<String> ignoreList;
    /** The loaded mode config — source of columns + colors + alert thresholds. Never null. */
    public final ModeConfig modeConfig;
    /** Non-fatal parse issues from BOTH files. Flushed to chat on server-join. */
    public final List<String> issues;

    // Convenience passthroughs so AgentImpl call sites stay flat.
    public final List<String> columns;
    public final Map<String, List<ColorTier>> colors;
    public final Map<String, String> headers;
    /** Passthrough from {@link #modeConfig} — FKDR threat threshold for the active mode. */
    public final double fkdrThreshold;
    /** Passthrough from {@link #modeConfig} — stars threat threshold for the active mode. */
    public final int starsThreshold;

    private Config(String apiKey, String seraphApiKey, String activeMode,
                   boolean nickDetection, boolean chatAlerts,
                   Set<String> ignoreList,
                   ModeConfig modeConfig, List<String> issues) {
        this.apiKey = apiKey;
        this.seraphApiKey = seraphApiKey;
        this.activeMode = activeMode;
        this.nickDetection = nickDetection;
        this.chatAlerts = chatAlerts;
        this.ignoreList = ignoreList;
        this.modeConfig = modeConfig;
        this.issues = issues;
        this.columns = modeConfig.columns;
        this.colors = modeConfig.colors;
        this.headers = modeConfig.headers;
        this.fkdrThreshold = modeConfig.fkdrThreshold;
        this.starsThreshold = modeConfig.starsThreshold;
    }

    /**
     * Read + parse both config files. Never throws — any failure yields a
     * defaults-only Config with issue notes.
     */
    public static Config load() {
        List<String> issues = new ArrayList<String>();

        String envKey = System.getenv(ENV_VAR);
        String apiKey = (envKey != null && !envKey.trim().isEmpty()) ? envKey.trim() : null;

        JsonObject root = readOrCreateGlobal(issues);

        String activeMode = DEFAULT_MODE;
        boolean nickDetection = true;
        boolean chatAlerts = true;
        Set<String> ignoreList = Collections.emptySet();
        String seraphApiKey = null;

        if (root != null) {
            if (apiKey == null) apiKey = readString(root, "apiKey", issues);
            seraphApiKey = readString(root, "seraphApiKey", issues);
            String modeStr = readString(root, "activeMode", issues);
            if (modeStr != null) {
                String norm = modeStr.toLowerCase();
                if (!ModeConfig.isKnown(norm)) {
                    issues.add("unknown activeMode \"" + modeStr + "\" — using " + DEFAULT_MODE);
                } else {
                    activeMode = norm;
                }
            }
            nickDetection = readBool(root, "nickDetection", true, issues);
            chatAlerts = readBool(root, "chatAlerts", true, issues);
            ignoreList = readIgnoreList(root, issues);

            // Pre-refactor `alerts` block lived at the global level. We now store
            // thresholds per-mode — if we see a legacy global block, copy its
            // values into the active-mode file and strip from global so it isn't
            // reported as "unknown key" on every join. In-memory remove first so
            // the loop below doesn't flag it either.
            if (root.has("alerts")) {
                JsonElement ae = root.get("alerts");
                if (ae.isJsonObject()) {
                    migrateLegacyAlerts(activeMode, ae.getAsJsonObject(), issues);
                }
                root.remove("alerts");
                stripLegacyAlertsFromFile();
            }

            for (String key : root.keySet()) {
                if (!RECOGNIZED_KEYS.contains(key)) {
                    issues.add("unknown key \"" + key + "\" in config.json — ignored");
                }
            }
        }

        ModeConfig modeConfig = ModeConfig.load(activeMode, issues);

        return new Config(apiKey, seraphApiKey, activeMode, nickDetection, chatAlerts,
                ignoreList, modeConfig, Collections.unmodifiableList(issues));
    }

    /**
     * Update {@code activeMode} in {@code config.json} without clobbering other
     * fields. Called from the {@code AX-mode} chat command. Creates the file
     * if missing.
     *
     * <p>Returns {@code true} on success; {@code false} on any I/O or parse
     * failure (caller surfaces the error in chat).
     */
    public static boolean writeActiveMode(String mode) {
        if (mode == null || mode.isEmpty()) return false;
        Path path = resolveConfigPath();
        if (path == null) return false;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            JsonObject root = null;
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (el != null && el.isJsonObject()) root = el.getAsJsonObject();
                }
            }
            if (root == null) root = buildDefaultGlobalJson();
            root.addProperty("activeMode", mode);
            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Update {@code seraphApiKey} in {@code config.json} without clobbering
     * other fields. Called from the {@code AX-seraph <key>} chat command.
     * Accepts empty string to clear.
     *
     * <p>Returns {@code true} on success; {@code false} on any I/O or parse
     * failure (caller surfaces the error in chat).
     */
    public static boolean writeSeraphApiKey(String key) {
        String normalized = key == null ? "" : key.trim();
        Path path = resolveConfigPath();
        if (path == null) return false;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            JsonObject root = null;
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (el != null && el.isJsonObject()) root = el.getAsJsonObject();
                }
            }
            if (root == null) root = buildDefaultGlobalJson();
            root.addProperty("seraphApiKey", normalized);
            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- helpers ---------------------------------------------------------

    private static JsonObject readOrCreateGlobal(List<String> issues) {
        Path path = resolveConfigPath();
        if (path == null) return null;
        if (!Files.exists(path)) {
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                JsonObject def = buildDefaultGlobalJson();
                String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(def);
                Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
                issues.add("created default config at " + path);
                return def;
            } catch (IOException e) {
                issues.add("could not write default config: " + e.getMessage());
                return null;
            }
        }
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) {
                issues.add("config root must be a JSON object — using defaults");
                return null;
            }
            return el.getAsJsonObject();
        } catch (IOException e) {
            issues.add("could not read config file: " + e.getMessage());
            return null;
        } catch (Exception e) {
            issues.add("could not parse config file: " + e.getMessage());
            return null;
        }
    }

    private static JsonObject buildDefaultGlobalJson() {
        JsonObject o = new JsonObject();
        o.addProperty("apiKey", "");
        o.addProperty("seraphApiKey", "");
        o.addProperty("activeMode", DEFAULT_MODE);
        o.addProperty("nickDetection", true);
        o.addProperty("chatAlerts", true);
        o.add("ignoreList", new JsonArray());
        return o;
    }

    private static String readString(JsonObject o, String key, List<String> issues) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            issues.add(key + " must be a string — ignored");
            return null;
        }
        String s = el.getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean readBool(JsonObject o, String key, boolean defaultValue, List<String> issues) {
        if (!o.has(key) || o.get(key).isJsonNull()) return defaultValue;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            issues.add(key + " must be true/false — using default " + defaultValue);
            return defaultValue;
        }
        return el.getAsBoolean();
    }

    /**
     * Parse {@code ignoreList} array. Entries are normalized to lowercase so
     * comparisons against raw MC usernames are case-insensitive. Non-string or
     * empty entries are dropped with a parse issue.
     */
    private static Set<String> readIgnoreList(JsonObject root, List<String> issues) {
        if (!root.has("ignoreList") || root.get("ignoreList").isJsonNull()) {
            return Collections.emptySet();
        }
        JsonElement el = root.get("ignoreList");
        if (!el.isJsonArray()) {
            issues.add("ignoreList must be an array — ignored");
            return Collections.emptySet();
        }
        JsonArray arr = el.getAsJsonArray();
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (item == null || item.isJsonNull()) continue;
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                issues.add("ignoreList[" + i + "] not a string — skipped");
                continue;
            }
            String s = item.getAsString().trim().toLowerCase();
            if (!s.isEmpty()) out.add(s);
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Add {@code name} to the {@code ignoreList} array in {@code config.json}
     * (lowercased; duplicates are no-ops). Called from the {@code AX-ignore}
     * chat command. Creates the file if missing, preserves all other fields.
     *
     * <p>Returns {@code true} on success; {@code false} on I/O or parse failure
     * (caller surfaces the error in chat).
     */
    public static boolean writeIgnoreListAdd(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase();
        if (normalized.isEmpty()) return false;
        return mutateIgnoreList(normalized, true);
    }

    /**
     * Remove {@code name} from the {@code ignoreList} array in {@code config.json}
     * (case-insensitive). Called from the {@code AX-removeignore} chat command.
     *
     * <p>Returns {@code true} on success; {@code false} on I/O or parse failure.
     */
    public static boolean writeIgnoreListRemove(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase();
        if (normalized.isEmpty()) return false;
        return mutateIgnoreList(normalized, false);
    }

    /**
     * Copy a legacy global {@code alerts} block into the per-mode file for
     * {@code activeMode}. Only injects keys the mode file doesn't already have —
     * user's own edits in the mode file win. Runs before {@link ModeConfig#load}
     * so the mode file is in its final shape when ModeConfig parses it.
     *
     * <p>One-time migration; once {@code alerts} is stripped from global, this
     * is never invoked again.
     */
    private static void migrateLegacyAlerts(String activeMode, JsonObject legacy, List<String> issues) {
        try {
            Path modePath = ModeConfig.resolveModePath(activeMode);
            if (modePath == null) return;

            JsonObject modeRoot = null;
            if (Files.exists(modePath)) {
                try (Reader r = Files.newBufferedReader(modePath, StandardCharsets.UTF_8)) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (el != null && el.isJsonObject()) modeRoot = el.getAsJsonObject();
                }
            }
            if (modeRoot == null) modeRoot = ModeConfig.buildDefaultJson(activeMode);

            JsonObject alerts;
            if (modeRoot.has("alerts") && modeRoot.get("alerts").isJsonObject()) {
                alerts = modeRoot.getAsJsonObject("alerts");
            } else {
                alerts = new JsonObject();
                modeRoot.add("alerts", alerts);
            }
            if (legacy.has("fkdrThreshold") && !alerts.has("fkdrThreshold")) {
                alerts.add("fkdrThreshold", legacy.get("fkdrThreshold"));
            }
            if (legacy.has("starsThreshold") && !alerts.has("starsThreshold")) {
                alerts.add("starsThreshold", legacy.get("starsThreshold"));
            }

            if (modePath.getParent() != null) Files.createDirectories(modePath.getParent());
            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(modeRoot);
            Files.write(modePath, pretty.getBytes(StandardCharsets.UTF_8));
            issues.add("migrated legacy `alerts` from config.json → modes/" + activeMode + ".json");
        } catch (Exception e) {
            issues.add("could not migrate legacy alerts: " + e.getMessage());
        }
    }

    /**
     * Rewrite {@code config.json} without the legacy {@code alerts} block.
     * Best-effort: a failure here just means the user keeps seeing the migration
     * note on next launch — not destructive.
     */
    private static void stripLegacyAlertsFromFile() {
        Path path = resolveConfigPath();
        if (path == null) return;
        try {
            if (!Files.exists(path)) return;
            JsonObject root;
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el == null || !el.isJsonObject()) return;
                root = el.getAsJsonObject();
            }
            if (!root.has("alerts")) return;
            root.remove("alerts");
            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // migration is best-effort; user sees next attempt or can delete manually
        }
    }

    private static boolean mutateIgnoreList(String lowered, boolean add) {
        Path path = resolveConfigPath();
        if (path == null) return false;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            JsonObject root = null;
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (el != null && el.isJsonObject()) root = el.getAsJsonObject();
                }
            }
            if (root == null) root = buildDefaultGlobalJson();

            JsonArray arr;
            if (root.has("ignoreList") && root.get("ignoreList").isJsonArray()) {
                arr = root.getAsJsonArray("ignoreList");
            } else {
                arr = new JsonArray();
                root.add("ignoreList", arr);
            }

            if (add) {
                // Scan for a case-insensitive match before appending to dedupe.
                for (int i = 0; i < arr.size(); i++) {
                    JsonElement item = arr.get(i);
                    if (item != null && item.isJsonPrimitive()
                            && item.getAsJsonPrimitive().isString()
                            && item.getAsString().trim().toLowerCase().equals(lowered)) {
                        return true;  // already present — caller treats as success
                    }
                }
                arr.add(lowered);
            } else {
                Iterator<JsonElement> it = arr.iterator();
                while (it.hasNext()) {
                    JsonElement item = it.next();
                    if (item != null && item.isJsonPrimitive()
                            && item.getAsJsonPrimitive().isString()
                            && item.getAsString().trim().toLowerCase().equals(lowered)) {
                        it.remove();
                    }
                }
            }

            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.write(path, pretty.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
