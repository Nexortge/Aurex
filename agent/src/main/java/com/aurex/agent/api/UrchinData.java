package com.aurex.agent.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Per-player Urchin data — cubelify-formatted blacklist response from
 * {@code https://urchin.ws/cubelify}.
 *
 * <p><b>Why not reuse {@link SeraphData.SeraphTag}:</b> both sources are
 * "cubelify-formatted" but Urchin's tag schema (per
 * {@code docs.urchin.ws}) is leaner —
 * {@code icon}/{@code color}/{@code tooltip}/{@code score}, no
 * {@code tag_name}/{@code text}/{@code textColor}. Classification has to look
 * at {@code tooltip} instead of {@code tag_name}, and there is no paired
 * text-color channel. Keeping a distinct tag class here avoids a confused
 * "half-shared" structure; if the two vendors ever converge, we collapse in
 * its own commit.
 *
 * <p><b>Cheater / bot flags</b> are precomputed using substring matches
 * against {@link #CHEATER_TOOLTIPS} / {@link #BOT_TOOLTIPS}. The vocabulary is
 * intentionally broad — Urchin's tooltips use plain English descriptions
 * ({@code "Banned for hacking"}, {@code "Sniper"}, {@code "Confirmed bot"})
 * and we'd rather false-positive on a suspicious keyword than miss a flagged
 * account.
 *
 * <p>Immutable snapshot — render-thread code reads without synchronization.
 */
public final class UrchinData {

    /** Empty singleton — non-key path returns this instead of null so callers skip null-checks. */
    public static final UrchinData EMPTY = new UrchinData(
            UUID.nameUUIDFromBytes(new byte[0]),
            Collections.<UrchinTag>emptyList(), false, false);

    /**
     * {@code tooltip} substrings (lowercased) that promote a tag to "cheater".
     * Urchin uses free-form English — broad matching keeps classification
     * aligned with {@link SeraphData#CHEATER_TAGS} (parity matters for the
     * cross-source double-flag alert).
     */
    private static final String[] CHEATER_TOOLTIPS =
            { "cheat", "hack", "sniper", "sniping", "blatant", "blacklist" };
    /** {@code tooltip} substrings we treat as "bot". */
    private static final String[] BOT_TOOLTIPS = { "bot" };

    public final UUID uuid;
    /**
     * Urchin tags, first-wins for rendering. Empty list when the player is
     * unblacklisted or the API returned no tags (not an error — common case).
     */
    public final List<UrchinTag> tags;
    public final boolean hasCheaterTag;
    public final boolean hasBotTag;

    private UrchinData(UUID uuid, List<UrchinTag> tags,
                       boolean hasCheaterTag, boolean hasBotTag) {
        this.uuid = uuid;
        this.tags = tags;
        this.hasCheaterTag = hasCheaterTag;
        this.hasBotTag = hasBotTag;
    }

    /**
     * Build an immutable record from Urchin's cubelify response body. A
     * {@code null} body is fine — it just means "no blacklist data" (empty
     * tags list), which is the expected outcome for most players.
     *
     * <p>Urchin's response also carries {@code score} (top-level summary
     * suspicion score). We don't surface it in V1 — tags alone drive the
     * column and alerts.
     */
    public static UrchinData fromResponse(UUID uuid, JsonObject body) {
        List<UrchinTag> parsedTags = parseTags(body);
        boolean cheater = false;
        boolean bot = false;
        for (UrchinTag t : parsedTags) {
            String tip = t.tooltip == null ? "" : t.tooltip.toLowerCase();
            if (!cheater && matchesAny(tip, CHEATER_TOOLTIPS)) cheater = true;
            if (!bot && matchesAny(tip, BOT_TOOLTIPS)) bot = true;
            if (cheater && bot) break;
        }
        return new UrchinData(uuid,
                Collections.unmodifiableList(parsedTags),
                cheater, bot);
    }

    private static List<UrchinTag> parseTags(JsonObject body) {
        if (body == null || !body.has("tags") || body.get("tags").isJsonNull()) {
            return new ArrayList<UrchinTag>(0);
        }
        JsonElement el = body.get("tags");
        if (!el.isJsonArray()) return new ArrayList<UrchinTag>(0);
        JsonArray arr = el.getAsJsonArray();
        List<UrchinTag> out = new ArrayList<UrchinTag>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (item == null || !item.isJsonObject()) continue;
            UrchinTag t = UrchinTag.fromJson(item.getAsJsonObject());
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
     * One entry from Urchin's {@code tags} array. {@code color} is an RGB int
     * mapped to the nearest MC §-code at parse time via
     * {@link SeraphColors#rgbToSection}. Urchin tags don't carry a separate
     * text color — {@code sectionColorCode} is used for both the icon glyph
     * and (if we ever render tooltip text inline) any body text.
     *
     * <p>Urchin's {@code icon} field is a Material Design Icon name (e.g.
     * {@code "mdi-alert-octagram-outline"}, {@code "mdi-signal-cellular-2"}) —
     * not something we want to dump verbatim into a tab cell. {@link #displayLabel}
     * is a precomputed short human label driven by {@link #prettyLabel},
     * which maps the icon family + tooltip content to one of a handful of
     * category strings (Cheater / Sniper / Seen / KD / "120ms" / etc.).
     */
    public static final class UrchinTag {
        public final String icon;
        public final String tooltip;
        /** MC §-code mapped from {@code color} RGB. Falls back to {@code §7} when omitted. */
        public final String sectionColorCode;
        /** Urchin's per-tag suspicion score. {@code 0} when omitted. Unused in V1 but preserved for future sort-by-score UX. */
        public final int score;
        /** Short human label for cell / alert rendering. Never null, never empty. */
        public final String displayLabel;

        UrchinTag(String icon, String tooltip, String sectionColorCode, int score, String displayLabel) {
            this.icon = icon;
            this.tooltip = tooltip;
            this.sectionColorCode = sectionColorCode;
            this.score = score;
            this.displayLabel = displayLabel;
        }

        static UrchinTag fromJson(JsonObject o) {
            String icon = optString(o, "icon");
            String tooltip = optString(o, "tooltip");
            Integer colorRgb = optInt(o, "color");
            Integer score = optInt(o, "score");
            String section = colorRgb != null ? SeraphColors.rgbToSection(colorRgb) : "§7";
            // Tag is only useful if there's SOMETHING to render. Urchin sometimes
            // omits icon but keeps tooltip (or vice-versa); accept either.
            boolean hasIcon = icon != null && !icon.isEmpty();
            boolean hasTip = tooltip != null && !tooltip.isEmpty();
            if (!hasIcon && !hasTip) return null;
            String label = prettyLabel(icon, tooltip);
            return new UrchinTag(icon, tooltip, section, score != null ? score : 0, label);
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

        /**
         * Map Urchin's {@code mdi-*} icon name + tooltip content to a short
         * human label for the tab cell / alert / AX-check line.
         *
         * <p>Urchin's public-access tag set (per their docs) buckets into:
         * <ul>
         *   <li><b>Cheater flags</b> — Sniper / Blatant / Closet / Confirmed.
         *       Usually drawn with an {@code mdi-alert*} / {@code mdi-octagram}
         *       icon; we resolve the specific subtype from substring matches
         *       on the tooltip so new cheater categories with the same icon
         *       still get a meaningful label.</li>
         *   <li><b>Seen-in-lobby history</b> — {@code mdi-timeline*} with a
         *       graph-style tooltip. Label: "Seen".</li>
         *   <li><b>KD summary</b> — {@code mdi-bow*} with void / sword K/D
         *       breakdowns. Label: "KD".</li>
         *   <li><b>Average ping</b> — {@code mdi-signal-cellular-*} with
         *       "Total Average: Nms" in the tooltip. Label: the ms value
         *       extracted ("120ms"), so users see the number at a glance
         *       without needing hover.</li>
         * </ul>
         *
         * <p>Unknown {@code mdi-*} icons fall back to the icon's trailing
         * token ({@code mdi-alert-octagram-outline} → {@code alert-octagram})
         * truncated — still readable, tells us at a glance that we should
         * add a case here. Nothing sensitive to guard: if the mapping is
         * wrong, classification ({@link #hasCheaterTag} / {@link #hasBotTag})
         * already runs independently off the tooltip in {@link UrchinData#fromResponse}.
         */
        static String prettyLabel(String icon, String tooltip) {
            String i = icon == null ? "" : icon.toLowerCase();
            String t = tooltip == null ? "" : tooltip.toLowerCase();

            boolean cheaterShape = i.startsWith("mdi-alert")
                    || i.contains("octagram")
                    || i.contains("crosshair");
            if (cheaterShape || containsAny(t, "cheat", "sniper", "blatant", "closet", "confirmed", "hack")) {
                if (t.contains("confirmed")) return "Confirmed";
                if (t.contains("blatant"))   return "Blatant";
                if (t.contains("closet"))    return "Closet";
                if (t.contains("sniper"))    return "Sniper";
                if (t.contains("bot"))       return "Bot";
                return "Cheater";
            }

            if (i.startsWith("mdi-timeline") || i.startsWith("mdi-history")
                    || i.startsWith("mdi-clock")) {
                return "Seen";
            }

            if (i.startsWith("mdi-bow") || i.startsWith("mdi-sword")
                    || i.startsWith("mdi-target")) {
                return "KD";
            }

            if (i.startsWith("mdi-signal") || i.startsWith("mdi-wifi")
                    || i.startsWith("mdi-network")) {
                String ms = extractPingMs(tooltip);
                return ms != null ? ms : "Ping";
            }

            // Fallback — strip the mdi- prefix, keep it short.
            String bare = i.startsWith("mdi-") ? i.substring(4) : i;
            if (bare.isEmpty() && !t.isEmpty()) bare = t;
            if (bare.isEmpty()) return "?";
            if (bare.length() > 12) bare = bare.substring(0, 12);
            return bare;
        }

        private static boolean containsAny(String haystack, String... needles) {
            for (String n : needles) {
                if (haystack.contains(n)) return true;
            }
            return false;
        }

        /**
         * Pull the last {@code Nms} substring out of the tooltip. Urchin's
         * ping tag ships it as {@code "Time since last ping: 5d 11h ago •
         * Total Average: 120ms"} — we want the "120ms" at the end so the
         * average is visible directly in the cell. Returns {@code null} if
         * no {@code Nms} pattern is present.
         */
        static String extractPingMs(String tooltip) {
            if (tooltip == null) return null;
            int len = tooltip.length();
            // Walk backwards so we prefer the LAST ms value (which tends to be
            // the "total average" rather than an intermediate label).
            for (int end = len - 2; end >= 0; end--) {
                if (tooltip.charAt(end) != 'm' || tooltip.charAt(end + 1) != 's') continue;
                int start = end;
                while (start > 0 && Character.isDigit(tooltip.charAt(start - 1))) start--;
                if (start < end) {
                    return tooltip.substring(start, end) + "ms";
                }
            }
            return null;
        }
    }
}
