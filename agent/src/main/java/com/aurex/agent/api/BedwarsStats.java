package com.aurex.agent.api;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Flat view of a player's Bedwars stats, as used by tab rendering.
 *
 * Built from Hypixel {@code /v2/player} responses via {@link #fromPlayerJson}.
 * Values are snapshotted at parse time — the object is immutable so render-thread
 * code can read it without synchronization.
 *
 * <p><b>Nicked / never-played players:</b> the parser returns {@code null} when
 * the response has no player object or no Bedwars stats.
 * {@link com.aurex.agent.AgentImpl#decorateInternal} treats {@code null} as
 * nicked and renders the row with a red {@code [NICK]} tag plus a one-time
 * chat alert.
 */
public final class BedwarsStats {

    public final UUID uuid;
    public final String displayName;
    /** Bedwars prestige level ("stars"). Read from {@code achievements.bedwars_level}. */
    public final int stars;
    public final int finalKills;
    public final int finalDeaths;
    public final int wins;
    public final int losses;
    /** final_kills / final_deaths, 0.0 if deaths == 0. */
    public final double fkdr;
    /** wins / losses, 0.0 if losses == 0. */
    public final double wlr;

    private BedwarsStats(UUID uuid, String displayName, int stars,
                         int finalKills, int finalDeaths,
                         int wins, int losses) {
        this.uuid = uuid;
        this.displayName = displayName;
        this.stars = stars;
        this.finalKills = finalKills;
        this.finalDeaths = finalDeaths;
        this.wins = wins;
        this.losses = losses;
        this.fkdr = finalDeaths > 0 ? (double) finalKills / finalDeaths : 0.0;
        this.wlr = losses > 0 ? (double) wins / losses : 0.0;
    }

    /**
     * Parse the {@code player} object from a Hypixel {@code /v2/player} response.
     * Returns {@code null} if the player has no Bedwars data (never played).
     * The caller is responsible for checking {@code success} and non-null
     * {@code player} on the outer envelope before calling this.
     */
    public static BedwarsStats fromPlayerJson(UUID uuid, JsonObject player) {
        if (player == null) return null;

        String displayName = optString(player, "displayname");

        int stars = 0;
        if (player.has("achievements") && player.get("achievements").isJsonObject()) {
            stars = optInt(player.getAsJsonObject("achievements"), "bedwars_level");
        }

        JsonObject bw = null;
        if (player.has("stats") && player.get("stats").isJsonObject()) {
            JsonObject stats = player.getAsJsonObject("stats");
            if (stats.has("Bedwars") && stats.get("Bedwars").isJsonObject()) {
                bw = stats.getAsJsonObject("Bedwars");
            }
        }
        if (bw == null) return null;

        int finalKills = optInt(bw, "final_kills_bedwars");
        int finalDeaths = optInt(bw, "final_deaths_bedwars");
        int wins = optInt(bw, "wins_bedwars");
        int losses = optInt(bw, "losses_bedwars");

        return new BedwarsStats(uuid, displayName, stars,
                finalKills, finalDeaths, wins, losses);
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static int optInt(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
    }

    @Override
    public String toString() {
        return String.format(
                "BedwarsStats{name=%s, stars=%d, FK=%d, FD=%d, FKDR=%.2f, W=%d, L=%d, WLR=%.2f}",
                displayName, stars, finalKills, finalDeaths, fkdr, wins, losses, wlr);
    }
}
