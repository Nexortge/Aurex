package com.aurex.agent.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Per-player Seraph data, as used by tab rendering.
 *
 * <p>Built from {@code /cubelify/blacklist/{uuid}} via {@link #fromResponses}.
 * Values are snapshotted at parse time — the object is immutable so render-thread
 * code reads it without synchronization.
 *
 * <p><b>Why §-codes are pre-computed:</b> Cubelify hands us {@code color} and
 * {@code textColor} as 24-bit RGB integers, but MC 1.8.9's chat renderer only
 * understands {@code §0}-{@code §9} + {@code §a}-{@code §f}. We map to the
 * nearest palette entry once at parse time so the per-frame format path is a
 * string concat, not a palette search.
 *
 * <p><b>Cheater / bot flags</b> are precomputed here (not in AgentImpl) so the
 * alert dedup path never has to walk the tag list. Classification is by
 * {@code tag_name} — see {@link #CHEATER_TAGS} / {@link #BOT_TAGS}. Heuristic:
 * Seraph's tag vocabulary isn't exhaustively documented; if real responses use
 * other names, extend those sets (or promote them to config).
 */
public final class SeraphData {

    /** Empty singleton — non-key path returns this instead of null so callers skip null-checks. */
    public static final SeraphData EMPTY = new SeraphData(
            UUID.nameUUIDFromBytes(new byte[0]),
            Collections.<SeraphTag>emptyList(), false, false);

    /**
     * {@code tag_name} values (lowercased) we treat as "cheater" — drives the
     * red one-shot chat alert and the COL_TAG priority. Seeded broadly because
     * Seraph buckets by sub-category (sniping, cheating, blacklist generic).
     */
    private static final String[] CHEATER_TAGS = { "cheater", "cheat", "sniper", "sniping", "blacklist" };
    /** {@code tag_name} values (lowercased) we treat as "bot". */
    private static final String[] BOT_TAGS = { "bot" };

    public final UUID uuid;
    /**
     * Cubelify tags, first-wins for rendering. Empty list when the player is
     * unblacklisted or the API returned no tags (not an error — common case).
     */
    public final List<SeraphTag> tags;
    public final boolean hasCheaterTag;
    public final boolean hasBotTag;

    private SeraphData(UUID uuid, List<SeraphTag> tags,
                       boolean hasCheaterTag, boolean hasBotTag) {
        this.uuid = uuid;
        this.tags = tags;
        this.hasCheaterTag = hasCheaterTag;
        this.hasBotTag = hasBotTag;
    }

    /**
     * Build an immutable record from the Cubelify blacklist response body. A
     * {@code null} body is fine — it just means "no blacklist data" (empty tags
     * list), which is the expected outcome for most players.
     */
    public static SeraphData fromResponses(UUID uuid, JsonObject cubelifyBody) {
        List<SeraphTag> parsedTags = parseTags(cubelifyBody);
        boolean cheater = false;
        boolean bot = false;
        for (SeraphTag t : parsedTags) {
            String name = t.tagName == null ? "" : t.tagName.toLowerCase();
            if (!cheater && matchesAny(name, CHEATER_TAGS)) cheater = true;
            if (!bot && matchesAny(name, BOT_TAGS)) bot = true;
            if (cheater && bot) break;
        }
        return new SeraphData(uuid,
                Collections.unmodifiableList(parsedTags),
                cheater, bot);
    }

    private static List<SeraphTag> parseTags(JsonObject body) {
        if (body == null || !body.has("tags") || body.get("tags").isJsonNull()) {
            return new ArrayList<SeraphTag>(0);
        }
        JsonElement el = body.get("tags");
        if (!el.isJsonArray()) return new ArrayList<SeraphTag>(0);
        JsonArray arr = el.getAsJsonArray();
        List<SeraphTag> out = new ArrayList<SeraphTag>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (item == null || !item.isJsonObject()) continue;
            SeraphTag t = SeraphTag.fromJson(item.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    private static boolean matchesAny(String needle, String[] haystack) {
        if (needle.isEmpty()) return false;
        for (String h : haystack) {
            if (needle.contains(h)) return true;
        }
        return false;
    }

    /**
     * One entry from Cubelify's {@code tags} array. Colors are mapped from the
     * API's RGB integers to the nearest MC §-code at parse time via
     * {@link SeraphColors#rgbToSection}.
     */
    public static final class SeraphTag {
        public final String tagName;
        public final String text;
        public final String tooltip;
        /** MC §-code for the tag background/primary color, e.g. {@code "§c"}. */
        public final String sectionColorCode;
        /** MC §-code for the tag text color, e.g. {@code "§f"}. Falls back to {@link #sectionColorCode} when API omits it. */
        public final String sectionTextCode;

        SeraphTag(String tagName, String text, String tooltip,
                  String sectionColorCode, String sectionTextCode) {
            this.tagName = tagName;
            this.text = text;
            this.tooltip = tooltip;
            this.sectionColorCode = sectionColorCode;
            this.sectionTextCode = sectionTextCode;
        }

        static SeraphTag fromJson(JsonObject o) {
            String tagName = optString(o, "tag_name");
            String text = optString(o, "text");
            String tooltip = optString(o, "tooltip");
            Integer colorRgb = optInt(o, "color");
            Integer textColorRgb = optInt(o, "textColor");
            String section = colorRgb != null ? SeraphColors.rgbToSection(colorRgb) : "§7";
            String textSection = textColorRgb != null ? SeraphColors.rgbToSection(textColorRgb) : section;
            // Tag is only useful if there's a label to render.
            if (text == null || text.isEmpty()) return null;
            return new SeraphTag(tagName, text, tooltip, section, textSection);
        }

        private static String optString(JsonObject o, String key) {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
        }

        private static Integer optInt(JsonObject o, String key) {
            if (!o.has(key)) return null;
            JsonElement el = o.get(key);
            if (el == null || el.isJsonNull()) return null;
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return null;
            return el.getAsInt();
        }
    }
}
