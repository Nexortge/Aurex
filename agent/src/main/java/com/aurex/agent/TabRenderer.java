package com.aurex.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * MC-loader resident renderer. Called from the early-return hook in
 * {@code GuiPlayerTabOverlay#renderPlayerlist} via {@link Agent#renderAurexTab}.
 *
 * <p>Lives on the MC loader (same as {@link Agent}) because it touches
 * {@code FontRenderer} / {@code Minecraft} — bootstrap can't resolve those.
 * Keeps zero compile-time imports of {@code net.minecraft.*}: every MC hop
 * goes through reflection so the agent jar still builds without MC on the
 * classpath (same pattern as {@link Agent#sendClientChat}).
 *
 * <p><b>Pipeline:</b>
 * <ol>
 *   <li>Collect {@code NetworkPlayerInfo} from {@code mc.thePlayer.sendQueue.getPlayerInfoMap}</li>
 *   <li>Cross the classloader hop via {@link Agent#getTableRows(Object[])} →
 *       {@code AgentImpl.getTableRows}, which returns preformatted String[] rows
 *       (colors already baked in)</li>
 *   <li>Measure per-column widths via {@code FontRenderer.getStringWidth}</li>
 *   <li>Draw background + header + rows with {@code Gui.drawRect} + {@code FontRenderer.drawStringWithShadow}</li>
 * </ol>
 *
 * <p>Must never throw from {@link #render(int)} — runs on Lunar's render
 * thread. On error we return {@code false} so vanilla runs as fallback.
 */
public final class TabRenderer {

    private TabRenderer() {}

    // --- layout constants -----------------------------------------------

    /** Column headers. Same order as {@code AgentImpl.COL_*} indices. */
    private static final String[] HEADERS = {"✫", "Name", "FKDR", "W/L", "Wins"};
    /** Rough text line-height for MC's default font. */
    private static final int ROW_HEIGHT  = 10;
    /** Gap between columns, in font pixels. */
    private static final int COL_GAP     = 10;
    /** Padding inside the panel (all sides). */
    private static final int PANEL_PAD   = 4;
    /** Distance from screen top to the top edge of the panel. */
    private static final int TOP_MARGIN  = 10;
    /** Panel background color (ARGB, ~50% black). */
    private static final int PANEL_BG    = 0x80000000;
    /** Header text color (white with full alpha). */
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    /** Default color passed to drawStringWithShadow. The §-codes baked into
     *  each cell override this per character. */
    private static final int CELL_COLOR = 0xFFFFFFFF;

    // --- reflection cache -----------------------------------------------

    private static volatile Method mcGetMinecraft;
    private static volatile Field  mcThePlayerField;
    private static volatile Field  mcFontRendererField;
    private static volatile Field  espSendQueueField;
    private static volatile Method nhpcGetPlayerInfoMap;
    private static volatile Method fontDrawStringShadow;
    private static volatile Method fontGetStringWidth;
    private static volatile Method guiDrawRect;
    private static volatile Class<?> guiClass;

    private static volatile Object cachedMinecraft;

    /**
     * Render the Aurex tab. Returns {@code true} if we drew our own tab
     * (caller skips vanilla), {@code false} if we gave up (caller falls
     * through to vanilla).
     *
     * @param width the {@code width} param from {@code renderPlayerlist} — the
     *              scaled-resolution screen width, in GUI pixels.
     */
    public static boolean render(int width) {
        try {
            Object mc = minecraft();
            if (mc == null) return false;
            Object font = fontRenderer(mc);
            if (font == null) return false;

            Object[] npis = collectNpis(mc);
            if (npis == null) return false;

            List<String[]> rows = Agent.getTableRows(npis);
            if (rows == null) return false;

            int[] widths = measureColumns(font, rows);
            int totalInner = sum(widths) + COL_GAP * (widths.length - 1);
            int panelW = totalInner + PANEL_PAD * 2;
            int panelH = PANEL_PAD * 2 + ROW_HEIGHT * (rows.size() + 1);  // +1 for header
            int x0 = (width - panelW) / 2;
            if (x0 < 2) x0 = 2;
            int y0 = TOP_MARGIN;

            drawRect(x0, y0, x0 + panelW, y0 + panelH, PANEL_BG);

            int cellY = y0 + PANEL_PAD;

            // Header row
            drawRowCells(font, x0 + PANEL_PAD, cellY, widths, HEADERS, HEADER_COLOR);
            cellY += ROW_HEIGHT;

            // Separator line under header — 1px translucent white rect.
            drawRect(x0 + PANEL_PAD, cellY - 2,
                     x0 + panelW - PANEL_PAD, cellY - 1, 0x40FFFFFF);

            // Data rows
            for (String[] row : rows) {
                drawRowCells(font, x0 + PANEL_PAD, cellY, widths, row, CELL_COLOR);
                cellY += ROW_HEIGHT;
            }

            return true;
        } catch (Throwable t) {
            Agent.log("TabRenderer.render failed: " + t);
            return false;
        }
    }

    // --- layout helpers -------------------------------------------------

    /** Per-column width = max(header width, max row cell width). */
    private static int[] measureColumns(Object font, List<String[]> rows) throws Exception {
        int n = HEADERS.length;
        int[] widths = new int[n];
        for (int c = 0; c < n; c++) widths[c] = stringWidth(font, HEADERS[c]);
        for (String[] row : rows) {
            for (int c = 0; c < n; c++) {
                int w = stringWidth(font, row[c]);
                if (w > widths[c]) widths[c] = w;
            }
        }
        return widths;
    }

    private static void drawRowCells(Object font, int xStart, int y, int[] widths,
                                     String[] cells, int defaultColor) throws Exception {
        int x = xStart;
        for (int c = 0; c < cells.length; c++) {
            drawStringShadow(font, cells[c], x, y, defaultColor);
            x += widths[c] + COL_GAP;
        }
    }

    private static int sum(int[] arr) {
        int s = 0;
        for (int v : arr) s += v;
        return s;
    }

    // --- Minecraft reflection layer -------------------------------------

    private static Object minecraft() throws Exception {
        Object m = cachedMinecraft;
        if (m != null) return m;
        Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft",
                true, TabRenderer.class.getClassLoader());
        Method gm = mcGetMinecraft;
        if (gm == null) {
            gm = mcClass.getMethod("getMinecraft");
            mcGetMinecraft = gm;
        }
        m = gm.invoke(null);
        cachedMinecraft = m;
        return m;
    }

    /**
     * {@code mc.fontRendererObj}. Re-read every frame (don't cache) because
     * the font can flip between the default + Galactic fonts in vanilla, and
     * Lunar themes do swap it. Cost is one {@code Field.get} per frame.
     */
    private static Object fontRenderer(Object mc) throws Exception {
        Field ff = mcFontRendererField;
        if (ff == null) {
            ff = mc.getClass().getField("fontRendererObj");
            mcFontRendererField = ff;
        }
        return ff.get(mc);
    }

    /**
     * Pull {@code mc.thePlayer.sendQueue.getPlayerInfoMap().toArray()}.
     * All calls reflective because we compile without MC on the classpath.
     * Returns {@code null} if any link in the chain is missing (e.g. title
     * screen, before a world loads).
     */
    private static Object[] collectNpis(Object mc) throws Exception {
        Field tpf = mcThePlayerField;
        if (tpf == null) {
            tpf = mc.getClass().getField("thePlayer");
            mcThePlayerField = tpf;
        }
        Object thePlayer = tpf.get(mc);
        if (thePlayer == null) return null;

        Field sq = espSendQueueField;
        if (sq == null) {
            sq = thePlayer.getClass().getField("sendQueue");
            espSendQueueField = sq;
        }
        Object netHandler = sq.get(thePlayer);
        if (netHandler == null) return null;

        Method gpim = nhpcGetPlayerInfoMap;
        if (gpim == null) {
            gpim = netHandler.getClass().getMethod("getPlayerInfoMap");
            nhpcGetPlayerInfoMap = gpim;
        }
        Object raw = gpim.invoke(netHandler);
        if (!(raw instanceof Collection)) return null;
        Collection<?> coll = (Collection<?>) raw;
        return coll.toArray();
    }

    // --- draw primitives ------------------------------------------------

    /** {@code FontRenderer.drawStringWithShadow(String, float, float, int) -> int}. */
    private static void drawStringShadow(Object font, String text, int x, int y, int color) throws Exception {
        Method m = fontDrawStringShadow;
        if (m == null) {
            m = font.getClass().getMethod("drawStringWithShadow",
                    String.class, float.class, float.class, int.class);
            fontDrawStringShadow = m;
        }
        m.invoke(font, text, (float) x, (float) y, color);
    }

    /** {@code FontRenderer.getStringWidth(String) -> int}. Handles §-codes correctly. */
    private static int stringWidth(Object font, String text) throws Exception {
        Method m = fontGetStringWidth;
        if (m == null) {
            m = font.getClass().getMethod("getStringWidth", String.class);
            fontGetStringWidth = m;
        }
        return (Integer) m.invoke(font, text);
    }

    /** {@code Gui.drawRect(int, int, int, int, int)} — static. */
    private static void drawRect(int x1, int y1, int x2, int y2, int color) throws Exception {
        Class<?> gc = guiClass;
        if (gc == null) {
            gc = Class.forName("net.minecraft.client.gui.Gui",
                    true, TabRenderer.class.getClassLoader());
            guiClass = gc;
        }
        Method m = guiDrawRect;
        if (m == null) {
            m = gc.getMethod("drawRect", int.class, int.class, int.class, int.class, int.class);
            guiDrawRect = m;
        }
        m.invoke(null, x1, y1, x2, y2, color);
    }
}
