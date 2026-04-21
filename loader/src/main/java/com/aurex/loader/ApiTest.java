package com.aurex.loader;

import com.aurex.agent.api.ApiKeyConfig;
import com.aurex.agent.api.BedwarsStats;
import com.aurex.agent.api.HypixelClient;
import com.aurex.agent.api.StatsCache;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code test-api} subcommand: fetches Bedwars stats for a UUID and prints
 * them. Exists so we can iterate on the networking layer without launching
 * Lunar — restart-Lunar-and-tail-log loops are ~30s, {@code java -jar} is ~2s.
 *
 * <p>Runs two lookups back-to-back: one cold through {@link HypixelClient},
 * one through {@link StatsCache} to prove the cache short-circuits the second
 * call (prints "cached" vs "fresh fetch" timings).
 */
final class ApiTest {
    private ApiTest() {}

    static void run(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -jar aurex-loader.jar test-api <uuid>");
            System.out.println("  <uuid> may be dashed or undashed.");
            System.exit(1);
        }

        UUID uuid;
        try {
            uuid = parseUuid(args[0]);
        } catch (IllegalArgumentException e) {
            System.err.println("Bad UUID: " + args[0]);
            System.exit(2);
            return;
        }

        String apiKey;
        try {
            apiKey = ApiKeyConfig.load();
        } catch (Exception e) {
            System.err.println("Could not read API key config: " + e.getMessage());
            System.exit(3);
            return;
        }

        if (apiKey == null) {
            Path cfg = ApiKeyConfig.resolveConfigPath();
            System.err.println("No API key found.");
            System.err.println();
            System.err.println("Set one of:");
            System.err.println("  Environment variable:  HYPIXEL_API_KEY=<key>");
            if (cfg != null) {
                System.err.println("  Config file:           " + cfg);
                System.err.println("                         (JSON: {\"apiKey\": \"<key>\"})");
            }
            System.err.println();
            System.err.println("Get a key at: https://developer.hypixel.net/");
            System.exit(4);
            return;
        }

        HypixelClient client = new HypixelClient(apiKey);
        try {
            StatsCache cache = new StatsCache(client);

            System.out.println("Fetching " + uuid + " (cold)...");
            long t0 = System.nanoTime();
            BedwarsStats cold = cache.get(uuid).get(30, TimeUnit.SECONDS);
            long coldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            print(cold, coldMs, "cold");

            System.out.println();
            System.out.println("Fetching " + uuid + " again (should be cached)...");
            long t1 = System.nanoTime();
            BedwarsStats warm = cache.get(uuid).get(30, TimeUnit.SECONDS);
            long warmMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);
            print(warm, warmMs, "cached");
        } finally {
            client.shutdown();
        }
    }

    private static void print(BedwarsStats stats, long ms, String label) {
        if (stats == null) {
            System.out.println("  [" + label + " " + ms + "ms] no Bedwars data (nicked or never played)");
            return;
        }
        System.out.println("  [" + label + " " + ms + "ms] " + stats.displayName);
        System.out.println("    stars:        " + stats.stars);
        System.out.println("    final kills:  " + stats.finalKills);
        System.out.println("    final deaths: " + stats.finalDeaths);
        System.out.printf ("    FKDR:         %.2f%n", stats.fkdr);
        System.out.println("    wins:         " + stats.wins);
        System.out.println("    losses:       " + stats.losses);
        System.out.printf ("    W/L:          %.2f%n", stats.wlr);
    }

    /** Accept both dashed and undashed UUID forms (Hypixel accepts both too). */
    private static UUID parseUuid(String s) {
        String trimmed = s.trim();
        if (trimmed.length() == 32 && !trimmed.contains("-")) {
            // Insert dashes at 8-4-4-4-12 for UUID.fromString.
            String dashed = trimmed.substring(0, 8) + "-"
                    + trimmed.substring(8, 12) + "-"
                    + trimmed.substring(12, 16) + "-"
                    + trimmed.substring(16, 20) + "-"
                    + trimmed.substring(20);
            return UUID.fromString(dashed);
        }
        return UUID.fromString(trimmed);
    }
}
