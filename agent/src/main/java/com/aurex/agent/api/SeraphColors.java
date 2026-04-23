package com.aurex.agent.api;

/**
 * RGB-to-§-code mapping for Seraph responses.
 *
 * <p>Cubelify hands us {@code color}/{@code textColor} as 24-bit RGB ints, but
 * Minecraft 1.8.9's chat renderer only understands the 16 named colors
 * ({@code §0}-{@code §9}, {@code §a}-{@code §f}). We pick the nearest palette
 * entry by squared-distance in RGB space once at parse time so the per-frame
 * render path is a plain string concat.
 *
 * <p>Palette values taken from vanilla MC 1.8.9 {@code net.minecraft.util.EnumChatFormatting}
 * color table (rgbcolor on each entry). Bright/dark variants are both included
 * so {@code §e} vs {@code §6} can snap correctly for yellow-ish vs gold-ish RGB.
 */
final class SeraphColors {

    /**
     * Index i corresponds to §-hex digit i (0-9, a-f). RGB values match MC's
     * EnumChatFormatting.getColorIndex-ordered entries.
     */
    private static final int[] PALETTE = {
            0x000000, // §0 black
            0x0000AA, // §1 dark_blue
            0x00AA00, // §2 dark_green
            0x00AAAA, // §3 dark_aqua
            0xAA0000, // §4 dark_red
            0xAA00AA, // §5 dark_purple
            0xFFAA00, // §6 gold
            0xAAAAAA, // §7 gray
            0x555555, // §8 dark_gray
            0x5555FF, // §9 blue
            0x55FF55, // §a green
            0x55FFFF, // §b aqua
            0xFF5555, // §c red
            0xFF55FF, // §d light_purple
            0xFFFF55, // §e yellow
            0xFFFFFF, // §f white
    };

    private static final char[] CODES = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private SeraphColors() {}

    /**
     * Pick the §-code closest to {@code rgb} in 24-bit RGB space (squared
     * distance). Alpha byte is masked off first, so inputs with the high byte
     * set (common when a JSON number came from signed-int-casted ARGB) still
     * resolve sensibly.
     */
    static String rgbToSection(int rgb) {
        int target = rgb & 0xFFFFFF;
        int tr = (target >> 16) & 0xFF;
        int tg = (target >> 8) & 0xFF;
        int tb = target & 0xFF;

        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int p = PALETTE[i];
            int dr = ((p >> 16) & 0xFF) - tr;
            int dg = ((p >> 8)  & 0xFF) - tg;
            int db = (p & 0xFF) - tb;
            long dist = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
                if (dist == 0) break;
            }
        }
        return "§" + CODES[best];
    }
}
