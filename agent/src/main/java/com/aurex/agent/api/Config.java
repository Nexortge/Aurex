package com.aurex.agent.api;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of {@code %APPDATA%\Aurex\config.json}.
 *
 * <p>Replaces the M5-era {@code ApiKeyConfig} — same file, richer schema:
 * <pre>
 * {
 *   "apiKey": "...",
 *   "columns": ["stars", "name", "fkdr", "wl", "wins"],   // order = render order
 *   "nickDetection": true,
 *   "chatAlerts": true,
 *   "colors": { ... }                                     // reserved for M11
 * }
 * </pre>
 *
 * <p>Every field is optional. A file containing only {@code apiKey} (the shape
 * M5 shipped with) still loads and gets every other field's default.
 *
 * <p><b>Issue reporting:</b> parse errors are non-fatal — we never refuse to
 * load. Any typed-wrong value, unknown key, or malformed column name goes into
 * {@link #issues} and the caller surfaces them (M9 flushes them to chat on
 * server-join). Defaults apply wherever a field is missing or broken.
 *
 * <p><b>API key precedence:</b> {@code HYPIXEL_API_KEY} env var wins over the
 * file. File's {@code apiKey} is only read when the env var is unset.
 *
 * <p><b>Thread-safe:</b> instances are immutable; {@link #load()} can be called
 * from any thread.
 */
public final class Config {

    public static final String COL_STARS  = "stars";
    public static final String COL_NAME   = "name";
    public static final String COL_FKDR   = "fkdr";
    public static final String COL_WL     = "wl";
    public static final String COL_WINS   = "wins";
    public static final String COL_HEALTH = "health";

    private static final Set<String> VALID_COLUMNS;
    private static final List<String> DEFAULT_COLUMNS;
    private static final Set<String> RECOGNIZED_KEYS;
    static {
        LinkedHashSet<String> valid = new LinkedHashSet<String>();
        valid.add(COL_STARS); valid.add(COL_NAME); valid.add(COL_FKDR);
        valid.add(COL_WL);    valid.add(COL_WINS); valid.add(COL_HEALTH);
        VALID_COLUMNS = Collections.unmodifiableSet(valid);

        // Default column set (what a user with no `columns` key gets). Health is
        // intentionally NOT in defaults — it's only populated on Hypixel during
        // matches, so an empty "HP" column in lobby would look broken. Users
        // who want it opt in via config.
        LinkedHashSet<String> def = new LinkedHashSet<String>();
        def.add(COL_STARS); def.add(COL_NAME); def.add(COL_FKDR);
        def.add(COL_WL);    def.add(COL_WINS);
        DEFAULT_COLUMNS = Collections.unmodifiableList(new ArrayList<String>(def));

        LinkedHashSet<String> keys = new LinkedHashSet<String>(Arrays.asList(
                "apiKey", "columns", "nickDetection", "chatAlerts", "colors"));
        RECOGNIZED_KEYS = Collections.unmodifiableSet(keys);
    }

    private static final String ENV_VAR = "HYPIXEL_API_KEY";
    private static final String APP_DIR = "Aurex";
    private static final String FILE_NAME = "config.json";

    /** @return canonical config-file path. {@code null} on non-Windows (no APPDATA). */
    public static Path resolveConfigPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return Paths.get(appData, APP_DIR, FILE_NAME);
    }

    public final String apiKey;
    public final List<String> columns;
    public final boolean nickDetection;
    public final boolean chatAlerts;
    /** Non-fatal parse issues. Each string is a human-readable one-liner. */
    public final List<String> issues;

    private Config(String apiKey, List<String> columns,
                   boolean nickDetection, boolean chatAlerts,
                   List<String> issues) {
        this.apiKey = apiKey;
        this.columns = columns;
        this.nickDetection = nickDetection;
        this.chatAlerts = chatAlerts;
        this.issues = issues;
    }

    /**
     * Read + parse the config file. Never throws — on any failure we log an
     * issue and keep going with defaults.
     */
    public static Config load() {
        List<String> issues = new ArrayList<String>();

        String envKey = System.getenv(ENV_VAR);
        String apiKey = (envKey != null && !envKey.trim().isEmpty()) ? envKey.trim() : null;

        JsonObject root = null;
        Path path = resolveConfigPath();
        if (path != null && Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(reader);
                if (el != null && el.isJsonObject()) {
                    root = el.getAsJsonObject();
                } else {
                    issues.add("config root must be a JSON object — using defaults");
                }
            } catch (IOException e) {
                issues.add("could not read config file: " + e.getMessage());
            } catch (Exception e) {
                issues.add("could not parse config file: " + e.getMessage());
            }
        }

        List<String> columns = DEFAULT_COLUMNS;
        boolean nickDetection = true;
        boolean chatAlerts = true;

        if (root != null) {
            if (apiKey == null) apiKey = readString(root, "apiKey", issues);
            columns = readColumns(root, issues);
            nickDetection = readBool(root, "nickDetection", true, issues);
            chatAlerts = readBool(root, "chatAlerts", true, issues);

            for (String key : root.keySet()) {
                if (!RECOGNIZED_KEYS.contains(key)) {
                    issues.add("unknown key \"" + key + "\" — ignored");
                }
            }
        }

        return new Config(apiKey, columns, nickDetection, chatAlerts,
                Collections.unmodifiableList(issues));
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

    private static List<String> readColumns(JsonObject root, List<String> issues) {
        if (!root.has("columns") || root.get("columns").isJsonNull()) return DEFAULT_COLUMNS;
        JsonElement el = root.get("columns");
        if (!el.isJsonArray()) {
            issues.add("columns must be an array — using defaults");
            return DEFAULT_COLUMNS;
        }
        JsonArray arr = el.getAsJsonArray();
        List<String> result = new ArrayList<String>(arr.size());
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                issues.add("columns[" + i + "]: not a string — skipped");
                continue;
            }
            String raw = e.getAsString();
            String name = raw.trim().toLowerCase();
            if (!VALID_COLUMNS.contains(name)) {
                issues.add("unknown column \"" + raw + "\" — skipped");
                continue;
            }
            if (!seen.add(name)) {
                issues.add("duplicate column \"" + name + "\" — skipped");
                continue;
            }
            result.add(name);
        }
        if (result.isEmpty()) {
            issues.add("columns list ended up empty — using defaults");
            return DEFAULT_COLUMNS;
        }
        return Collections.unmodifiableList(result);
    }
}
