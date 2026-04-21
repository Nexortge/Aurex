package com.aurex.agent.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the Hypixel API key without ever taking it as source input.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>{@code HYPIXEL_API_KEY} environment variable — handy for CI / dev</li>
 *   <li>{@code %APPDATA%\Aurex\config.json} — structured config, M9 will extend
 *       this file with more settings</li>
 * </ol>
 *
 * <p>Returns {@code null} if nothing is configured. Callers decide whether
 * that's fatal (CLI harness: yes) or "just run without stats" (agent at
 * startup: also yes — fail loudly in log, silent in game).
 */
public final class ApiKeyConfig {

    private static final String ENV_VAR = "HYPIXEL_API_KEY";
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String APP_DIR_NAME = "Aurex";

    private ApiKeyConfig() {}

    /**
     * @return the API key, or {@code null} if none is configured.
     * @throws IOException if the config file exists but can't be read/parsed.
     */
    public static String load() throws IOException {
        String env = System.getenv(ENV_VAR);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }

        Path configPath = resolveConfigPath();
        if (configPath != null && Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                if (obj.has("apiKey") && !obj.get("apiKey").isJsonNull()) {
                    String key = obj.get("apiKey").getAsString();
                    if (key != null && !key.trim().isEmpty()) {
                        return key.trim();
                    }
                }
            } catch (Exception e) {
                throw new IOException("Failed to read API key from " + configPath, e);
            }
        }

        return null;
    }

    /** Canonical path we read the key from, for error messages. May be {@code null} on non-Windows. */
    public static Path resolveConfigPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        return Paths.get(appData, APP_DIR_NAME, CONFIG_FILE_NAME);
    }
}
