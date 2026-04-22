package com.aurex.agent.api;

import com.google.gson.GsonBuilder;
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

    private static final Set<String> VALID_COLUMNS;
    private static final Set<String> RECOGNIZED_KEYS;
    static {
        LinkedHashSet<String> valid = new LinkedHashSet<String>();
        valid.add(COL_STARS);  valid.add(COL_NAME);      valid.add(COL_FKDR);
        valid.add(COL_WL);     valid.add(COL_WINS);      valid.add(COL_HEALTH);
        valid.add(COL_FINALS); valid.add(COL_BEDS);      valid.add(COL_WINSTREAK);
        valid.add(COL_KDR);    valid.add(COL_BBLR);
        VALID_COLUMNS = Collections.unmodifiableSet(valid);

        LinkedHashSet<String> keys = new LinkedHashSet<String>(Arrays.asList(
                "apiKey", "activeMode", "nickDetection", "chatAlerts"));
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
    public final String activeMode;
    public final boolean nickDetection;
    public final boolean chatAlerts;
    /** The loaded mode config — source of columns + colors. Never null. */
    public final ModeConfig modeConfig;
    /** Non-fatal parse issues from BOTH files. Flushed to chat on server-join. */
    public final List<String> issues;

    // Convenience passthroughs so AgentImpl call sites stay flat.
    public final List<String> columns;
    public final Map<String, List<ColorTier>> colors;
    public final Map<String, String> headers;

    private Config(String apiKey, String activeMode,
                   boolean nickDetection, boolean chatAlerts,
                   ModeConfig modeConfig, List<String> issues) {
        this.apiKey = apiKey;
        this.activeMode = activeMode;
        this.nickDetection = nickDetection;
        this.chatAlerts = chatAlerts;
        this.modeConfig = modeConfig;
        this.issues = issues;
        this.columns = modeConfig.columns;
        this.colors = modeConfig.colors;
        this.headers = modeConfig.headers;
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

        if (root != null) {
            if (apiKey == null) apiKey = readString(root, "apiKey", issues);
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

            for (String key : root.keySet()) {
                if (!RECOGNIZED_KEYS.contains(key)) {
                    issues.add("unknown key \"" + key + "\" in config.json — ignored");
                }
            }
        }

        ModeConfig modeConfig = ModeConfig.load(activeMode, issues);

        return new Config(apiKey, activeMode, nickDetection, chatAlerts,
                modeConfig, Collections.unmodifiableList(issues));
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
        o.addProperty("activeMode", DEFAULT_MODE);
        o.addProperty("nickDetection", true);
        o.addProperty("chatAlerts", true);
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
}
