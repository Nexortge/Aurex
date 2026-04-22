package com.aurex.agent.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One "if value &gt;= min, use this color" rule in a column's tier ladder.
 *
 * <p>Tiers live in {@code modes/&lt;mode&gt;.json} under {@code colors.&lt;column&gt;[]}.
 * The renderer walks a column's list from highest {@code min} down and applies
 * the first tier whose {@code min} the value meets or exceeds.
 *
 * <p><b>Rainbow sentinel:</b> writing {@code "rainbow"} as the color produces
 * per-character cycling §-codes ({@code §c §6 §e §a §b §d}). Used for Bedwars
 * stars ≥ 1000 to match the community prestige palette.
 *
 * <p>Color names map to MC §-codes (full list in {@link #NAMED_COLORS}). Raw
 * {@code §x} codes are also accepted. Unknown names yield {@code null} and a
 * line in the caller's {@code issues} list — the tier is dropped.
 */
public final class ColorTier {

    /** Six-step per-char cycle used for {@code "rainbow"}. */
    private static final String[] RAINBOW_CYCLE = {"§c", "§6", "§e", "§a", "§b", "§d"};

    /** MC color name → §-code. Includes common aliases (grey/gray, pink/light_purple). */
    private static final Map<String, String> NAMED_COLORS = buildNamedColors();

    public final double min;
    /** As user wrote it — preserved so we can round-trip back to disk. */
    public final String rawColor;
    /** Resolved §-code ({@code §7}, {@code §c}, ...). Empty string when {@link #rainbow}. */
    public final String code;
    public final boolean rainbow;

    private ColorTier(double min, String rawColor, String code, boolean rainbow) {
        this.min = min;
        this.rawColor = rawColor;
        this.code = code;
        this.rainbow = rainbow;
    }

    /**
     * Parse one tier entry. Returns {@code null} on any problem and appends a
     * human-readable note to {@code issues}. {@code label} is included in
     * issue messages ({@code "colors.fkdr[2]"}) so users can find the broken
     * entry.
     */
    public static ColorTier parse(String label, double min, String rawColor, List<String> issues) {
        if (rawColor == null) {
            issues.add(label + ": color missing");
            return null;
        }
        String trimmed = rawColor.trim();
        if (trimmed.isEmpty()) {
            issues.add(label + ": color is empty");
            return null;
        }
        if (trimmed.equalsIgnoreCase("rainbow")) {
            return new ColorTier(min, "rainbow", "", true);
        }
        // Raw §-code pass-through: `§c` or `§c` or just `c`-prefixed name.
        if (trimmed.length() == 2 && trimmed.charAt(0) == '§') {
            return new ColorTier(min, trimmed, trimmed, false);
        }
        String key = trimmed.toLowerCase().replace('-', '_');
        String code = NAMED_COLORS.get(key);
        if (code == null) {
            issues.add(label + ": unknown color \"" + rawColor + "\"");
            return null;
        }
        return new ColorTier(min, trimmed, code, false);
    }

    /**
     * Find the matching tier for {@code value}. Expects {@code tiers} sorted
     * ascending by {@code min} (the loader does this). Walks from the end so
     * the highest satisfied threshold wins. Returns {@code null} if no tier
     * matches (e.g. value below the lowest {@code min}).
     */
    public static ColorTier pick(List<ColorTier> tiers, double value) {
        if (tiers == null || tiers.isEmpty()) return null;
        for (int i = tiers.size() - 1; i >= 0; i--) {
            ColorTier t = tiers.get(i);
            if (value >= t.min) return t;
        }
        return null;
    }

    /**
     * Convenience: pick + colorize in one call. If no tier matches, returns
     * {@code text} unchanged (no color). Always appends {@code §r}.
     */
    public static String colorize(List<ColorTier> tiers, double value, String text) {
        ColorTier t = pick(tiers, value);
        if (t == null) return text;
        return t.colorize(text);
    }

    /**
     * Wrap {@code text} with this tier's color.
     * <ul>
     *   <li>Solid tier: {@code <code>text§r}</li>
     *   <li>Rainbow: cycles {@link #RAINBOW_CYCLE} per character, preserving any
     *       pre-existing §-codes in the input (so e.g. a name with a rank prefix
     *       would still look sane if someone rainbow-colored the name column)</li>
     * </ul>
     */
    public String colorize(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!rainbow) return code + text + "§r";
        StringBuilder out = new StringBuilder(text.length() * 4);
        int ci = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Pass through any §x pair verbatim so existing color codes in the
            // input aren't shredded.
            if (c == '§' && i + 1 < text.length()) {
                out.append(c).append(text.charAt(++i));
                continue;
            }
            out.append(RAINBOW_CYCLE[ci % RAINBOW_CYCLE.length]).append(c);
            ci++;
        }
        out.append("§r");
        return out.toString();
    }

    private static Map<String, String> buildNamedColors() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("black",        "§0");
        m.put("dark_blue",    "§1");
        m.put("dark_green",   "§2");
        m.put("dark_aqua",    "§3");
        m.put("dark_red",     "§4");
        m.put("dark_purple",  "§5");
        m.put("gold",         "§6");
        m.put("gray",         "§7");
        m.put("grey",         "§7");
        m.put("dark_gray",    "§8");
        m.put("dark_grey",    "§8");
        m.put("blue",         "§9");
        m.put("green",        "§a");
        m.put("aqua",         "§b");
        m.put("red",          "§c");
        m.put("light_purple", "§d");
        m.put("pink",         "§d");
        m.put("yellow",       "§e");
        m.put("white",        "§f");
        return Collections.unmodifiableMap(m);
    }
}
