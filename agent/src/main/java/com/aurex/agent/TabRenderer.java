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
 *   <li>Cross the classloader hop via {@link Agent#getTabData(Object[])} →
 *       {@code AgentImpl.getTabData}, which returns {@code {headers, rows}}
 *       with §-codes already baked in</li>
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

    /** Pixel size of the head icon drawn inside the Name cell (same as vanilla tab). */
    private static final int HEAD_SIZE = 8;
    /** Gap between the head icon and the name text. */
    private static final int HEAD_GAP  = 2;

    /** Column id for the Name column. Compile-time-stable — matches {@code Config.COL_NAME}.
     *  Duplicated as a string literal because TabRenderer lives on Lunar's MC
     *  loader, which can't resolve {@code com.aurex.agent.api.Config}. */
    private static final String COL_ID_NAME = "name";

    // --- reflection cache -----------------------------------------------

    private static volatile Method mcGetMinecraft;
    private static volatile Field  mcThePlayerField;
    private static volatile Field  mcFontRendererField;
    private static volatile Field  mcTheWorldField;
    private static volatile Field  mcTextureManagerField;
    private static volatile Field  espSendQueueField;
    private static volatile Method nhpcGetPlayerInfoMap;
    private static volatile Method worldGetScoreboard;
    private static volatile Method scoreboardGetObjectiveInDisplaySlot;
    private static volatile Method fontDrawStringShadow;
    private static volatile Method fontGetStringWidth;
    private static volatile Method guiDrawRect;
    private static volatile Method guiDrawScaledCustomSizeModalRect;
    private static volatile Method npiGetLocationSkin;
    private static volatile Method textureManagerBindTexture;
    private static volatile Method glColor4f;
    private static volatile Method glEnableBlend;
    private static volatile Method glDisableBlend;
    private static volatile Class<?> guiClass;
    private static volatile Class<?> glStateManagerClass;

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

            // Scoreboard + tab-slot objective: may be null if the server hasn't
            // set an objective in display slot 0 (typical for Hypixel lobbies).
            // AgentImpl handles null gracefully — HP cells show "—".
            Object scoreboard = collectScoreboard(mc);
            Object objective = collectTabObjective(scoreboard);

            Object[] data = Agent.getTabData(npis, scoreboard, objective);
            if (data == null || data.length < 3) return false;
            String[] colIds = (String[]) data[0];
            String[] headers = (String[]) data[1];
            @SuppressWarnings("unchecked")
            List<Object[]> rowEntries = (List<Object[]>) data[2];
            if (colIds == null || headers == null || headers.length == 0 || rowEntries == null) return false;

            int nameColIdx = indexOf(colIds, COL_ID_NAME);

            int[] widths = measureColumns(font, headers, rowEntries, nameColIdx);
            int totalInner = sum(widths) + COL_GAP * (widths.length - 1);
            int panelW = totalInner + PANEL_PAD * 2;
            int panelH = PANEL_PAD * 2 + ROW_HEIGHT * (rowEntries.size() + 1);  // +1 for header
            int x0 = (width - panelW) / 2;
            if (x0 < 2) x0 = 2;
            int y0 = TOP_MARGIN;

            drawRect(x0, y0, x0 + panelW, y0 + panelH, PANEL_BG);

            int cellY = y0 + PANEL_PAD;

            // Header row — no heads, no per-row npi. Pass -1 as nameColIdx to
            // skip the head-drawing branch.
            drawRowCells(font, x0 + PANEL_PAD, cellY, widths, headers, HEADER_COLOR, null, -1, mc);
            cellY += ROW_HEIGHT;

            // Separator line under header — 1px translucent white rect.
            drawRect(x0 + PANEL_PAD, cellY - 2,
                     x0 + panelW - PANEL_PAD, cellY - 1, 0x40FFFFFF);

            // Data rows
            for (Object[] entry : rowEntries) {
                Object rowNpi = entry[0];
                String[] cells = (String[]) entry[1];
                drawRowCells(font, x0 + PANEL_PAD, cellY, widths, cells, CELL_COLOR, rowNpi, nameColIdx, mc);
                cellY += ROW_HEIGHT;
            }

            return true;
        } catch (Throwable t) {
            Agent.log("TabRenderer.render failed: " + t);
            return false;
        }
    }

    // --- layout helpers -------------------------------------------------

    /** Per-column width = max(header width, max row cell width). The Name
     *  column gets extra width for the head icon (drawn left of the name text). */
    private static int[] measureColumns(Object font, String[] headers,
                                        List<Object[]> rowEntries, int nameColIdx) throws Exception {
        int n = headers.length;
        int[] widths = new int[n];
        for (int c = 0; c < n; c++) widths[c] = stringWidth(font, headers[c]);
        for (Object[] entry : rowEntries) {
            String[] cells = (String[]) entry[1];
            for (int c = 0; c < n && c < cells.length; c++) {
                int w = stringWidth(font, cells[c]);
                if (c == nameColIdx) w += HEAD_SIZE + HEAD_GAP;
                if (w > widths[c]) widths[c] = w;
            }
        }
        return widths;
    }

    /**
     * Draw one row of cells. If {@code rowNpi != null} and {@code nameColIdx >= 0}
     * we draw the player's head inside the Name cell and shift the name text by
     * {@code HEAD_SIZE + HEAD_GAP}. The header passes {@code rowNpi == null} so
     * it never gets a head.
     */
    private static void drawRowCells(Object font, int xStart, int y, int[] widths,
                                     String[] cells, int defaultColor,
                                     Object rowNpi, int nameColIdx, Object mc) throws Exception {
        int x = xStart;
        for (int c = 0; c < cells.length; c++) {
            if (c == nameColIdx && rowNpi != null) {
                drawPlayerHead(mc, rowNpi, x, y);
                drawStringShadow(font, cells[c], x + HEAD_SIZE + HEAD_GAP, y, defaultColor);
            } else {
                drawStringShadow(font, cells[c], x, y, defaultColor);
            }
            x += widths[c] + COL_GAP;
        }
    }

    private static int indexOf(String[] arr, String target) {
        for (int i = 0; i < arr.length; i++) {
            if (target.equals(arr[i])) return i;
        }
        return -1;
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
        Class<?> gc = guiClass();
        Method m = guiDrawRect;
        if (m == null) {
            m = gc.getMethod("drawRect", int.class, int.class, int.class, int.class, int.class);
            guiDrawRect = m;
        }
        m.invoke(null, x1, y1, x2, y2, color);
    }

    private static Class<?> guiClass() throws Exception {
        Class<?> gc = guiClass;
        if (gc == null) {
            gc = Class.forName("net.minecraft.client.gui.Gui",
                    true, TabRenderer.class.getClassLoader());
            guiClass = gc;
        }
        return gc;
    }

    // --- scoreboard (health column) -------------------------------------

    /** {@code mc.theWorld.getScoreboard()}. Null if no world loaded. */
    private static Object collectScoreboard(Object mc) {
        try {
            Field tw = mcTheWorldField;
            if (tw == null) {
                tw = mc.getClass().getField("theWorld");
                mcTheWorldField = tw;
            }
            Object world = tw.get(mc);
            if (world == null) return null;

            Method gs = worldGetScoreboard;
            if (gs == null) {
                gs = world.getClass().getMethod("getScoreboard");
                worldGetScoreboard = gs;
            }
            return gs.invoke(world);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code scoreboard.getObjectiveInDisplaySlot(0)} — slot 0 = LIST (tab).
     *  Null if the server hasn't set a tab objective (common in lobbies). */
    private static Object collectTabObjective(Object scoreboard) {
        if (scoreboard == null) return null;
        try {
            Method gos = scoreboardGetObjectiveInDisplaySlot;
            if (gos == null) {
                gos = scoreboard.getClass().getMethod("getObjectiveInDisplaySlot", int.class);
                scoreboardGetObjectiveInDisplaySlot = gos;
            }
            return gos.invoke(scoreboard, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- head icon ------------------------------------------------------

    /**
     * Draw the 8×8 player face + hat overlay at {@code (x, y)}. Mirrors what
     * vanilla {@code GuiPlayerTabOverlay} does when it draws the tab:
     * <ol>
     *   <li>{@code GlStateManager.color(1,1,1,1)} — reset color modulation so
     *       the texture isn't tinted.</li>
     *   <li>{@code textureManager.bindTexture(npi.getLocationSkin())} — bind
     *       the player's skin texture.</li>
     *   <li>{@code Gui.drawScaledCustomSizeModalRect} at UV (8,8) — the face
     *       layer of the skin.</li>
     *   <li>Enable blend, draw again at UV (40,8) — the hat overlay, which
     *       has an alpha channel.</li>
     * </ol>
     *
     * <p>All reflection. Swallows exceptions — a skin-load failure shouldn't
     * kill the whole tab render.
     */
    private static void drawPlayerHead(Object mc, Object npi, int x, int y) {
        try {
            setGlColor(1f, 1f, 1f, 1f);

            Object texMan = textureManager(mc);
            Object skinLoc = npiSkinLocation(npi);
            if (texMan == null || skinLoc == null) return;
            bindTexture(texMan, skinLoc);

            // Face layer at UV (8, 8), 8×8 region scaled 1:1 into an 8×8 quad,
            // texture atlas is 64×64 (modern skins — legacy 64×32 skins still
            // work because the face region is in the top half).
            drawScaledRect(x, y, 8f, 8f, HEAD_SIZE, HEAD_SIZE, HEAD_SIZE, HEAD_SIZE, 64f, 64f);

            // Hat overlay at UV (40, 8). Needs blending — hat can have alpha.
            glEnableBlend();
            drawScaledRect(x, y, 40f, 8f, HEAD_SIZE, HEAD_SIZE, HEAD_SIZE, HEAD_SIZE, 64f, 64f);
            glDisableBlend();
        } catch (Throwable ignored) {
            // Best-effort: missing skin / texture binds shouldn't kill the frame.
        }
    }

    private static Object textureManager(Object mc) throws Exception {
        Field ft = mcTextureManagerField;
        if (ft == null) {
            // {@code mc.renderEngine} is the TextureManager in 1.8.9 (MCP name).
            ft = mc.getClass().getField("renderEngine");
            mcTextureManagerField = ft;
        }
        return ft.get(mc);
    }

    private static Object npiSkinLocation(Object npi) throws Exception {
        Method m = npiGetLocationSkin;
        if (m == null) {
            m = npi.getClass().getMethod("getLocationSkin");
            npiGetLocationSkin = m;
        }
        return m.invoke(npi);
    }

    private static void bindTexture(Object texMan, Object resourceLocation) throws Exception {
        Method m = textureManagerBindTexture;
        if (m == null) {
            // TextureManager.bindTexture takes ResourceLocation — we have the
            // ResourceLocation object in hand, so getClass gives us the type.
            m = texMan.getClass().getMethod("bindTexture", resourceLocation.getClass());
            textureManagerBindTexture = m;
        }
        m.invoke(texMan, resourceLocation);
    }

    private static void drawScaledRect(int x, int y, float u, float v,
                                       int uWidth, int uHeight, int width, int height,
                                       float tileWidth, float tileHeight) throws Exception {
        Class<?> gc = guiClass();
        Method m = guiDrawScaledCustomSizeModalRect;
        if (m == null) {
            m = gc.getMethod("drawScaledCustomSizeModalRect",
                    int.class, int.class, float.class, float.class,
                    int.class, int.class, int.class, int.class,
                    float.class, float.class);
            guiDrawScaledCustomSizeModalRect = m;
        }
        m.invoke(null, x, y, u, v, uWidth, uHeight, width, height, tileWidth, tileHeight);
    }

    private static Class<?> glStateManager() throws Exception {
        Class<?> c = glStateManagerClass;
        if (c == null) {
            c = Class.forName("net.minecraft.client.renderer.GlStateManager",
                    true, TabRenderer.class.getClassLoader());
            glStateManagerClass = c;
        }
        return c;
    }

    private static void setGlColor(float r, float g, float b, float a) throws Exception {
        Method m = glColor4f;
        if (m == null) {
            m = glStateManager().getMethod("color",
                    float.class, float.class, float.class, float.class);
            glColor4f = m;
        }
        m.invoke(null, r, g, b, a);
    }

    private static void glEnableBlend() throws Exception {
        Method m = glEnableBlend;
        if (m == null) {
            m = glStateManager().getMethod("enableBlend");
            glEnableBlend = m;
        }
        m.invoke(null);
    }

    private static void glDisableBlend() throws Exception {
        Method m = glDisableBlend;
        if (m == null) {
            m = glStateManager().getMethod("disableBlend");
            glDisableBlend = m;
        }
        m.invoke(null);
    }
}
