package com.aurex.agent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.jar.JarFile;

/**
 * Aurex Java Agent entry points.
 *
 * When the loader calls vm.loadAgent("aurex-agent.jar") on Lunar's running
 * JVM, Lunar's JVM reads this jar's manifest, sees Agent-Class pointing here,
 * and invokes {@link #agentmain(String, Instrumentation)} — synchronously,
 * on a special JVM-internal thread.
 *
 * Whatever we do here runs with the same privileges as Lunar itself, inside
 * Lunar's JVM. From M2 onward we'll register ClassFileTransformers on the
 * Instrumentation handle; for M1 we just prove we got here.
 *
 * Keep these methods SHORT. vm.loadAgent blocks until agentmain returns —
 * a slow or stuck agentmain hangs the loader too.
 */
public final class Agent {

    /** Marker line prefix for everything we write. Makes grep easy. */
    private static final String TAG = "Aurex";

    private Agent() {}

    /** Runtime attach — our primary path (loader calls loadAgent). */
    public static void agentmain(String args, Instrumentation inst) {
        log("hello from inside Lunar (agentmain)");
    }

    /**
     * Startup entry point. Runs in the **app** (system) classloader, because
     * that's where the JVM's -javaagent machinery lives.
     *
     * Two-stage init:
     * 1. Publish our jar to the bootstrap classloader so MC classes can resolve
     *    {@code com.aurex.agent.Agent} at INVOKESTATIC time (Lunar's MC loader
     *    doesn't delegate to app; it does delegate to bootstrap).
     * 2. Reflectively re-enter via {@link #start(Instrumentation)} loaded from
     *    bootstrap. Reason: once our jar is on bootstrap, any class reference
     *    in Agent/app (ASM, our transformer) exists in BOTH app and bootstrap,
     *    and the JVM throws a loader-constraint violation when the mismatched
     *    copies meet at a method signature. Verified 2026-04-20: calling
     *    {@code ClassReader.accept(ClassVisitor, int)} from app's transformer
     *    fails because ASM got resolved via bootstrap mid-call. Running the
     *    real setup code from bootstrap-loaded Agent keeps every reference
     *    (transformer, ASM, Agent itself) on a single consistent classloader.
     */
    public static void premain(String args, Instrumentation inst) {
        truncateLog();
        log("hello from inside Lunar (premain)");
        try {
            File self = new File(Agent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(self));
            log("appended to bootstrap classloader: " + self);

            // ClassLoader=null means bootstrap. This loads a second copy of
            // Agent, distinct from the one running this very method, and
            // invokes start() on the bootstrap copy.
            Class.forName("com.aurex.agent.Agent", true, null)
                    .getMethod("start", Instrumentation.class)
                    .invoke(null, inst);
        } catch (Throwable t) {
            log("premain init FAILED: " + t);
        }
    }

    /**
     * Second-stage init. Invoked reflectively from {@link #premain} after the
     * agent jar has been published to bootstrap. THIS method runs inside
     * bootstrap-loaded Agent, so everything it touches — TabOverlayTransformer,
     * ASM — also resolves via bootstrap, keeping the entire hook path on a
     * single classloader.
     */
    public static void start(Instrumentation inst) {
        log("Agent.start (bootstrap classloader)");
        openJavaLangForReflection(inst);
        preloadImpl();
        inst.addTransformer(new TabOverlayTransformer());
        inst.addTransformer(new ChatCommandTransformer());
        inst.addTransformer(new JoinGameTransformer());
        inst.addTransformer(new IncomingChatTransformer());
    }

    /**
     * Trigger {@link AgentImpl}'s static initializer now (on bootstrap), so the
     * API-key load + {@link com.aurex.agent.api.HypixelClient} construction happen
     * at premain time — not on the render thread the first time a tab opens.
     *
     * <p>We keep AgentImpl out of this class's imports on purpose (see
     * {@link AgentImpl}'s javadoc): Agent is {@code defineClass}'d into Lunar's
     * MC loader, which can't see {@code com.aurex.agent.api.*}. Class.forName
     * by name is loader-safe — the MC copy of Agent never actually links to
     * AgentImpl's bytecode, it just reflects into the bootstrap copy at call time.
     */
    private static void preloadImpl() {
        try {
            Class.forName("com.aurex.agent.AgentImpl", true, null);
        } catch (Throwable t) {
            log("preloadImpl failed: " + t);
        }
    }

    /**
     * Open {@code java.lang} from module {@code java.base} to our own module
     * so we can later reflect on {@code ClassLoader.defineClass} without
     * hitting {@code InaccessibleObjectException} on Java 9+.
     *
     * Why we need it: Lunar's MC classloader (IchorPipeline) doesn't
     * delegate to bootstrap for our package. A bootstrap-only {@code Agent}
     * is therefore invisible to patched MC bytecode at INVOKESTATIC time.
     * The workaround (see {@link TabOverlayTransformer#publishAgentInto})
     * is to call {@code defineClass} directly on Lunar's MC classloader —
     * which requires reflective access to a java.base internal method.
     *
     * Called reflectively because {@code Instrumentation.redefineModule}
     * is a Java 9+ API and we compile to Java 8 bytecode.
     */
    private static void openJavaLangForReflection(Instrumentation inst) {
        try {
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Method getModule = Class.class.getMethod("getModule");
            Object javaBase = getModule.invoke(ClassLoader.class);
            Object ourModule = getModule.invoke(Agent.class);

            Set<Object> ourSet = new HashSet<Object>();
            ourSet.add(ourModule);
            Map<String, Set<Object>> opens = new HashMap<String, Set<Object>>();
            opens.put("java.lang", ourSet);

            Method redefineModule = Instrumentation.class.getMethod("redefineModule",
                    moduleClass, Set.class, Map.class, Map.class, Set.class, Map.class);
            redefineModule.invoke(inst,
                    javaBase,
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    opens,
                    Collections.emptySet(),
                    Collections.emptyMap());
            log("opened java.lang to our module");
        } catch (Throwable t) {
            // Non-fatal: on Java 8 the reflective calls aren't needed, so
            // missing Module / redefineModule isn't a problem.
            log("openJavaLangForReflection failed (may be OK on JVM 8): " + t);
        }
    }

    /**
     * Called from inside GuiPlayerTabOverlay#renderPlayerlist (via ASM-injected
     * INVOKESTATIC at method entry). Throttled to once per second so the log
     * doesn't explode at 60+ fps.
     *
     * MUST stay ()V — the injected call passes no args and expects no return.
     * Also must never throw: an exception here would propagate out of the
     * render method and crash Lunar.
     */
    private static volatile long lastTabLog;

    public static void onTabRender() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastTabLog >= 1000L) {
                lastTabLog = now;
                log("tab rendered");
            }
        } catch (Throwable ignored) {
            // Swallow everything — this runs on Lunar's render thread.
        }
    }

    /**
     * Full-tab-replacement gate. Called from the HEAD of
     * {@code GuiPlayerTabOverlay#renderPlayerlist} immediately after
     * {@link #onTabRender()}. Return {@code true} to tell the injected bytecode
     * to {@code RETURN} — vanilla's body is skipped and we've drawn our own
     * tab. Return {@code false} to fall through to the vanilla renderer.
     *
     * <p>Gate order: if {@link #displayEnabled} is off we always return false
     * so AX-off users see unmodified vanilla tab. When on we hand off to
     * {@link TabRenderer#render(int)} which owns the actual drawing.
     *
     * <p>Must never throw — on error we return false so the user at least
     * keeps vanilla tab instead of losing it entirely to a crash.
     */
    public static boolean renderAurexTab(int width) {
        try {
            if (!displayEnabled) return false;
            return TabRenderer.render(width);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- M3.5: arm/disarm gate ------------------------------------------------

    /**
     * Persistent display gate. AX-on turns it on, AX-off turns it off — no
     * auto-disarm. Any M4+ feature that mutates what the user sees (tab, HUD,
     * chat injections) must check this before touching rendering.
     *
     * Separate from {@link #fetchArmed} so the 3s safety window only limits
     * outbound API traffic; once data is in the cache, it stays visible until
     * the user explicitly types AX-off.
     */
    static volatile boolean displayEnabled;

    /**
     * Fetch-window gate. AX-on flips this to true for {@link #ARM_MS}, then
     * {@link DisarmTask} clears it. Gates outbound network calls (Hypixel API
     * from M5 onward) so we can't accidentally spray requests by leaving the
     * overlay on.
     */
    static volatile boolean fetchArmed;

    /**
     * Epoch millis at which the currently-scheduled fetch-window close will
     * fire. Used as a "generation token": {@link DisarmTask} captures this
     * value at schedule time and no-ops if it doesn't match when the timer
     * fires. A second AX-on simply bumps {@code disarmAt}, scheduling a new
     * task, and the stale task sees the mismatch and returns.
     */
    static volatile long disarmAt;

    /** Single background Timer for all scheduled tasks. Daemon = doesn't block JVM shutdown. */
    private static final Timer timer = new Timer("Aurex-Timer", true);

    /** How long AX-on keeps the fetch window open before auto-closing. */
    private static final long ARM_MS = 3000L;

    /**
     * How long the game-start auto-arm keeps the fetch window open. Matches
     * {@link #ARM_MS} (manual AX-on) for consistency. Starts ~2s after the
     * final countdown tick — not at the countdown itself — so Hypixel's
     * name-masking in the pre-game lobby doesn't pollute the fetch.
     */
    private static final long AUTO_ARM_MS = 3000L;

    /**
     * Delay between "countdown reaches 0" and the auto-arm actually firing.
     * Hypixel masks player names during the pre-game lobby ("Player",
     * "ClassAssignment", etc.) — fetching them at countdown time would just
     * stash garbage. Waiting 2s past T=0 gives Bungee time to hand off to the
     * match sub-server and for the client to receive the real roster via
     * {@code S38PacketPlayerListItem}.
     */
    private static final long POST_START_DELAY_MS = 2000L;

    /**
     * Generation token for the pending {@link AutoArmTask}. Countdown ticks
     * bump this at reschedule time; the scheduled task captures it, and
     * no-ops at fire time if it's been superseded. Mirrors the {@code disarmAt}
     * pattern {@link DisarmTask} uses.
     */
    static volatile long pendingAutoArmToken;

    /**
     * Minimum gap between consecutive auto-arms. Below this, duplicate chat
     * packets (network echo, Hypixel's own replay, etc.) can't double-fire the
     * arm. Above this, each real countdown tick (~1000ms apart) can re-arm.
     */
    private static final long AUTO_ARM_DEDUP_MS = 500L;

    /** Wall-clock of the last auto-arm fire; used only for the dedup check. */
    private static volatile long lastAutoArmMs;

    /**
     * Called from the HEAD of {@code EntityPlayerSP#sendChatMessage(String)}.
     * Return {@code true} to swallow the outgoing packet (our commands), {@code
     * false} to let it go to the server (normal chat).
     *
     * Must never throw — an exception here propagates out of the client's chat
     * send path. On error we return false so chat still works.
     */
    public static boolean onOutgoingChat(String message) {
        try {
            if (message == null) return false;
            String trimmed = message.trim();

            if (trimmed.equalsIgnoreCase("AX-on")) {
                arm();
                return true;
            }
            if (trimmed.equalsIgnoreCase("AX-off")) {
                disarm(true);
                return true;
            }
            if (trimmed.equalsIgnoreCase("AX-status")) {
                sendClientChat("\u00a7e[AX] display=" + (displayEnabled ? "on" : "off")
                        + " fetch=" + (fetchArmed ? "armed" : "idle"));
                return true;
            }
            // AX-mode [name] — list known modes, or switch active mode and hot-reload.
            // Handled BEFORE the typo guard so "AX-mode" alone (no arg) reaches the
            // list branch instead of being dismissed as unknown.
            if (isAxModeCommand(trimmed)) {
                String rest = trimmed.length() > 7 ? trimmed.substring(7).trim() : "";
                onAxMode(rest);
                return true;
            }
            // Typo guard: anything that LOOKS like a botched AX command —
            // starts with "ax" (case-insensitive), optional space, then dash or
            // underscore — gets swallowed and hinted instead of broadcasting
            // the typo to public chat. Catches "AX-onn", "ax-stats", "AX_on",
            // "ax -on", "AX _status", etc.
            if (looksLikeAxCommand(trimmed)) {
                sendClientChat("§e[AX] unknown: \"" + trimmed
                        + "\" — try AX-on / AX-off / AX-status");
                log("swallowed unknown AX command: " + trimmed);
                return true;
            }
            return false;
        } catch (Throwable t) {
            log("onOutgoingChat failed: " + t);
            return false;
        }
    }

    /**
     * Called from the HEAD of {@code GuiNewChat#printChatMessage(IChatComponent)}
     * via {@link IncomingChatTransformer}. Observes every chat line that reaches
     * the client — pattern-matches Hypixel's countdown ("The game starts in N
     * seconds!") and auto-arms the fetch window so stats are cached before the
     * match begins.
     *
     * <p>Parameter is typed {@link Object} because {@link Agent} lives on the
     * MC classloader and must not compile-time reference
     * {@code net.minecraft.util.IChatComponent} (fine on MC loader but breaks
     * the bootstrap + app copies of this class — they don't see MC classes).
     * We reflect into {@code getUnformattedText()} at call time; any MC loader
     * servicing the call can resolve it.
     *
     * <p>Must never throw: runs on the client main thread on every chat packet
     * and for every {@code addChatMessage} call we ourselves make. An exception
     * here would break the entire chat pipeline. On error we silently bail.
     *
     * <p>Cheap-path first: plain {@code String.contains} filter before regex,
     * because 99.9% of chat lines aren't countdown lines and a failing regex on
     * every chat packet would waste CPU.
     */
    public static void onIncomingChat(Object chatComponent) {
        try {
            if (chatComponent == null) return;
            String text = extractChatText(chatComponent);
            if (text == null) return;
            int seconds = parseCountdownSeconds(text);
            if (seconds < 0) return;

            long now = System.currentTimeMillis();
            if (now - lastAutoArmMs < AUTO_ARM_DEDUP_MS) return;
            lastAutoArmMs = now;

            // Formula: delay = N*1000ms (time until countdown ends) +
            // POST_START_DELAY_MS (lag settle / Bungee handoff). Every tick
            // converges on the same wall-clock target — the generation-token
            // dance in AutoArmTask ensures only the latest fires.
            long delay = (long) seconds * 1000L + POST_START_DELAY_MS;
            long token = System.nanoTime();
            pendingAutoArmToken = token;
            timer.schedule(new AutoArmTask(token), delay);
            log("auto-arm scheduled: +" + delay + "ms (countdown " + seconds + "s) on: \"" + text + "\"");
        } catch (Throwable t) {
            // Deliberately no log — a broken chat pipeline log-spamming once
            // per message would fill the log fast. If this breaks, manual
            // AX-on still works.
        }
    }

    /**
     * Reflectively pull the unformatted text out of an {@code IChatComponent}.
     * We use {@code getUnformattedText()} (strips color/formatting) so the
     * regex doesn't have to deal with §-codes Hypixel may or may not inject.
     *
     * <p>Resolves the class via the component's own loader so we don't care
     * which classloader the caller handed us.
     */
    private static String extractChatText(Object chatComponent) {
        try {
            Method m = chatComponent.getClass().getMethod("getUnformattedText");
            Object result = m.invoke(chatComponent);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Parse Hypixel's Bedwars pre-game countdown line and return the number of
     * seconds remaining. Returns {@code -1} on no match.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "The game starts in 5 seconds!"} → {@code 5}</li>
     *   <li>{@code "The game starts in 1 second!"}  → {@code 1}</li>
     * </ul>
     *
     * <p>Done manually to avoid Pattern compilation per call — this runs on
     * every chat line so a tight loop over a short string is cheaper than
     * regex setup.
     */
    private static int parseCountdownSeconds(String text) {
        // Cheap-path: most chat lines don't contain "starts in".
        if (text.length() < 24 || text.indexOf("starts in") < 0) return -1;
        String s = text.trim();
        if (!s.startsWith("The game starts in ")) return -1;
        if (!s.endsWith("!")) return -1;
        // What's between "The game starts in " and "!" should be "N second(s)".
        String middle = s.substring(19, s.length() - 1).trim();
        int spaceIdx = middle.indexOf(' ');
        if (spaceIdx < 0) return -1;
        String numPart = middle.substring(0, spaceIdx);
        String unitPart = middle.substring(spaceIdx + 1);
        if (!unitPart.equals("second") && !unitPart.equals("seconds")) return -1;
        if (numPart.isEmpty()) return -1;
        int value = 0;
        for (int i = 0; i < numPart.length(); i++) {
            char c = numPart.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
            if (value > 60) return -1;  // sanity cap
        }
        return value;
    }

    /**
     * Shape-match a message against the "possibly-an-AX-command" pattern:
     * {@code ^[aA][xX] ?[-_]}. If true, the caller swallows the message and
     * prints a client-side hint — the goal is to keep typos like {@code ax_on}
     * or {@code AX -status} off public chat.
     */
    /**
     * Shape-match against {@code AX-mode}: a case-insensitive {@code "ax-mode"}
     * prefix, followed by either end-of-string or a single space. Tighter than
     * {@link #looksLikeAxCommand} so "AX-modex" doesn't accidentally route here.
     */
    private static boolean isAxModeCommand(String s) {
        if (s.length() < 7) return false;
        String prefix = "ax-mode";
        for (int i = 0; i < 7; i++) {
            if (Character.toLowerCase(s.charAt(i)) != prefix.charAt(i)) return false;
        }
        return s.length() == 7 || s.charAt(7) == ' ';
    }

    /**
     * Hop into {@link AgentImpl#onAxMode(String)} — validate, write, hot-reload
     * the config, and announce in chat. Same classloader-bridge pattern as the
     * other Impl calls on this class.
     */
    private static void onAxMode(String rest) {
        try {
            Method m = implOnAxModeMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxMode", String.class);
                implOnAxModeMethod = m;
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxMode bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxModeMethod;

    private static boolean looksLikeAxCommand(String s) {
        if (s.length() < 3) return false;
        char c0 = Character.toLowerCase(s.charAt(0));
        char c1 = Character.toLowerCase(s.charAt(1));
        if (c0 != 'a' || c1 != 'x') return false;
        int i = 2;
        if (s.charAt(i) == ' ') i++;
        if (i >= s.length()) return false;
        char sep = s.charAt(i);
        return sep == '-' || sep == '_';
    }

    private static void arm() {
        long fireAt = System.currentTimeMillis() + ARM_MS;
        disarmAt = fireAt;
        displayEnabled = true;
        fetchArmed = true;
        log("AX-on (display on; fetch armed for " + ARM_MS + "ms)");
        sendClientChat("\u00a7a[AX] on (fetch 3s)");
        // Timer.schedule(task, delay) — task.run() fires on the Timer's thread
        // after delay ms. DisarmTask checks disarmAt == fireAt before actually
        // disarming, so a later arm() simply invalidates this task.
        timer.schedule(new DisarmTask(fireAt, true), ARM_MS);
        kickoffFetches();
    }

    /**
     * Actually perform the auto-arm. Called from {@link AutoArmTask#run()} —
     * not directly from {@link #onIncomingChat(Object)}, because incoming-chat
     * only <i>schedules</i> the arm for ~2s after the game starts (see
     * {@link #POST_START_DELAY_MS}).
     *
     * <p>Same as {@link #arm()} but silent (no chat confirmation) and uses
     * {@link #AUTO_ARM_MS}. Flips {@link #displayEnabled} too, not just
     * {@link #fetchArmed}: if display were left off, the tab renderer would
     * stay gated off, which gates off the fetch kick-off path too. Auto-arming
     * both mirrors the manual {@code AX-on} UX.
     *
     * <p>Package-private so {@link AutoArmTask} can reach it.
     */
    static void autoArmNow() {
        long fireAt = System.currentTimeMillis() + AUTO_ARM_MS;
        disarmAt = fireAt;
        displayEnabled = true;
        fetchArmed = true;
        timer.schedule(new DisarmTask(fireAt, false), AUTO_ARM_MS);
        kickoffFetches();
        log("auto-arm fired (window " + AUTO_ARM_MS + "ms)");
    }

    /**
     * Eagerly pre-warm {@code StatsCache} for every player currently in the
     * client's player-info map. Called from {@link #arm()} and {@link #autoArm()}
     * so fetches fire immediately on arm, regardless of whether the user is
     * holding TAB.
     *
     * <p>Before this was added, fetch kick-off only happened inside
     * {@code AgentImpl.getTabData}, which only runs from {@code TabRenderer.render},
     * which only runs while {@code renderPlayerlist} is rendering — i.e., while
     * the user holds TAB. Arming without holding TAB during the window was a
     * silent no-op. Now: the arm triggers a fetch burst up front; once responses
     * land in cache, the first tab-render reads them instantly.
     *
     * <p>{@code NetHandlerPlayClient.playerInfoMap} is kept live by the client
     * independently of tab-render — the server pushes
     * {@code S38PacketPlayerListItem} updates as players join/leave/change, and
     * the handler mutates the map on the netty→main thread boundary. Reading
     * the map from the main thread (our call site) is safe.
     *
     * <p>Reflective path: {@code Minecraft.getMinecraft().getNetHandler()
     * .getPlayerInfoMap()} returns {@code Collection<NetworkPlayerInfo>}. We
     * convert to {@code Object[]} and hand off to bootstrap's {@code AgentImpl}
     * which already owns the v4-UUID filter + StatsCache kickoff pattern.
     *
     * <p>Silent no-op if {@code netHandler} is null (user at main menu / not
     * connected yet). Never throws — arm must not be able to crash the client.
     */
    private static void kickoffFetches() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = Agent.class.getClassLoader();

            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
            Object mc = mcClass.getMethod("getMinecraft").invoke(null);
            Object netHandler = mcClass.getMethod("getNetHandler").invoke(mc);
            if (netHandler == null) return;  // not in a world yet

            Object infos = netHandler.getClass().getMethod("getPlayerInfoMap").invoke(netHandler);
            if (!(infos instanceof java.util.Collection)) return;
            java.util.Collection<?> coll = (java.util.Collection<?>) infos;
            if (coll.isEmpty()) return;

            Object[] npis = coll.toArray();

            Method m = implPreWarmMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("preWarmFetches", Object[].class);
                implPreWarmMethod = m;
            }
            m.invoke(null, (Object) npis);
        } catch (Throwable t) {
            log("kickoffFetches failed: " + t);
        }
    }

    private static volatile Method implPreWarmMethod;

    private static void disarm(boolean announce) {
        displayEnabled = false;
        fetchArmed = false;
        disarmAt = 0L;
        clearNickAlerts();
        log("AX-off (display + fetch off)");
        if (announce) sendClientChat("\u00a7c[AX] off");
    }

    /**
     * Drain {@link AgentImpl}'s nick-alert dedup set so a subsequent AX-on
     * re-announces any nicks that are still in the lobby. Reflective hop
     * across the classloader split — same pattern as {@link #loadImpl()}.
     * Never throws: alert-reset is best-effort and failing to clear just
     * means the user sees each nick announced once per JVM, not per arm cycle.
     */
    private static void clearNickAlerts() {
        try {
            Method m = implClearNicksMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("clearNickAlerts");
                implClearNicksMethod = m;
            }
            m.invoke(null);
        } catch (Throwable t) {
            log("clearNickAlerts failed: " + t);
        }
    }

    private static volatile Method implClearNicksMethod;

    /**
     * Gate for rendering-side features (tab mutation, HUD, etc.). Persists
     * across the fetch window, only flips off on explicit AX-off.
     */
    public static boolean isDisplayEnabled() {
        return displayEnabled;
    }

    /**
     * Gate for outbound network calls. Only true during the 3s window after
     * AX-on so a stuck overlay can't keep hitting Hypixel's API.
     */
    public static boolean isFetchArmed() {
        return fetchArmed;
    }

    /**
     * Called from {@code GuiPlayerTabOverlay.getPlayerName} before each
     * {@code ARETURN}. Stack at call site is {@code [String, NetworkPlayerInfo]};
     * we pop both and push a (possibly) modified String — stack shape at ARETURN
     * is unchanged, so no stackmap edits are needed.
     *
     * <p>The real work (API-key / StatsCache / formatting) lives in
     * {@link AgentImpl#decorateInternal}. This method only exists on the MC
     * classloader copy of Agent, where we can't compile-time reference any
     * {@code com.aurex.agent.api.*} class — so we reflect across to the
     * bootstrap copy of AgentImpl once and cache the {@link Method} handle.
     *
     * <p>Gate order matters: we check {@code displayEnabled} first so a disarmed
     * overlay pays zero reflection cost per tab entry per frame. Only when the
     * overlay is on do we cross the classloader boundary.
     *
     * <p>Must never throw: runs on Lunar's render thread, once per tab entry,
     * every frame. On error we return the original name so tab still renders.
     */
    public static String decorateName(String originalName, Object networkPlayerInfo) {
        try {
            if (!displayEnabled || originalName == null) return originalName;
            Method m = implDecorateMethod;
            if (m == null) m = loadImpl();
            if (m == null) return originalName;
            return (String) m.invoke(null, originalName, networkPlayerInfo, fetchArmed);
        } catch (Throwable t) {
            return originalName;
        }
    }

    /** Cached {@link AgentImpl#decorateInternal} handle; null until first successful lookup. */
    private static volatile Method implDecorateMethod;

    private static Method loadImpl() {
        try {
            Method m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                    .getMethod("decorateInternal", String.class, Object.class, boolean.class);
            implDecorateMethod = m;
            return m;
        } catch (Throwable t) {
            log("AgentImpl.decorateInternal lookup failed: " + t);
            return null;
        }
    }

    /**
     * Reflective hop to {@code AgentImpl.getTabData}. Called by
     * {@link TabRenderer} once per tab frame (while AX-on) to get the
     * preformatted row data, the header array, and column IDs. Same
     * classloader bridge pattern as {@link #decorateName(String, Object)}.
     *
     * <p>{@code scoreboard} + {@code objective} are the MC objects from the
     * tab display slot — plumbed through so AgentImpl can read the health
     * column. Either may be {@code null} if the server hasn't set one.
     *
     * <p>Returns {@code Object[3] = {String[] colIds, String[] headers,
     * List<Object[]> rowEntries}} where each rowEntry is {@code {Object npi,
     * String[] cells}}. Returns {@code null} on any failure so the caller
     * can fall back to vanilla — never throws.
     */
    public static Object[] getTabData(Object[] npis, Object scoreboard, Object objective) {
        try {
            Method m = implGetTabDataMethod;
            if (m == null) m = loadGetTabData();
            if (m == null) return null;
            return (Object[]) m.invoke(null, npis, scoreboard, objective, fetchArmed);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Method implGetTabDataMethod;

    private static Method loadGetTabData() {
        try {
            Method m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                    .getMethod("getTabData", Object[].class, Object.class, Object.class, boolean.class);
            implGetTabDataMethod = m;
            return m;
        } catch (Throwable t) {
            log("AgentImpl.getTabData lookup failed: " + t);
            return null;
        }
    }

    /**
     * Called from RETURN sites of {@code NetHandlerPlayClient#handleJoinGame}
     * via {@link JoinGameTransformer}. Injected at RETURN (not HEAD) because
     * the method is what creates {@code mc.thePlayer} — the chat send path
     * needs thePlayer non-null.
     *
     * <p>Debounced at 500ms because BungeeCord-backed servers (Hypixel) fire
     * handleJoinGame multiple times on connect as the proxy hands the client
     * off to a sub-server. One announcement per real join is the UX we want.
     *
     * <p>Reloads config from disk and announces the result in chat. Reflective
     * hop to bootstrap-resident state. Never throws — world join must not be
     * blocked by Aurex.
     */
    public static void onServerJoin() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastJoinFireMs < JOIN_DEBOUNCE_MS) {
                return;
            }
            lastJoinFireMs = now;

            Method m = implOnServerJoinMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onServerJoin");
                implOnServerJoinMethod = m;
            }
            m.invoke(null);
        } catch (Throwable t) {
            log("onServerJoin bridge failed: " + t);
        }
    }

    private static volatile Method implOnServerJoinMethod;
    private static volatile long lastJoinFireMs;
    /** Window for collapsing Bungee proxy→sub-server double-fires. */
    private static final long JOIN_DEBOUNCE_MS = 500L;

    /**
     * Print a client-side chat line (not sent to the server). Used for AX
     * command confirmations and nick alerts.
     *
     * <p>Reflective because this code path ends up running in three
     * classloader copies (app / bootstrap / MC); only the MC copy can see
     * {@code net.minecraft.*} via its own loader. Callers from bootstrap
     * (e.g. {@link AgentImpl}) must pass an explicit MC-capable loader via
     * {@link #sendClientChat(String, ClassLoader)} — bootstrap's own search
     * won't resolve MC classes.
     *
     * <p>Package-private so {@link DisarmTask} and {@link AgentImpl} can call it.
     */
    static void sendClientChat(String text) {
        sendClientChat(text, null);
    }

    /**
     * Same as {@link #sendClientChat(String)} but uses {@code mcLoader} to
     * resolve {@code net.minecraft.*} classes. Falls back to a chain of
     * candidate loaders if {@code null}, ending at this class's own loader.
     *
     * <p>Never throws — running on the render thread; a chat-send failure
     * must not bring down the frame.
     */
    static void sendClientChat(String text, ClassLoader mcLoader) {
        try {
            ClassLoader cl = mcLoader;
            if (cl == null) cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = Agent.class.getClassLoader();

            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
            Method getMinecraft = mcClass.getMethod("getMinecraft");
            Object mc = getMinecraft.invoke(null);

            Field thePlayerField = mcClass.getField("thePlayer");
            Object thePlayer = thePlayerField.get(mc);
            if (thePlayer == null) {
                log("sendClientChat: thePlayer is null, skipping: " + text);
                return;
            }

            Class<?> componentClass = Class.forName("net.minecraft.util.ChatComponentText", true, cl);
            Object component = componentClass.getConstructor(String.class).newInstance(text);

            Class<?> iChatComponent = Class.forName("net.minecraft.util.IChatComponent", true, cl);
            Class<?> entityPlayer = Class.forName("net.minecraft.entity.player.EntityPlayer", true, cl);
            Method addChatMessage = entityPlayer.getMethod("addChatMessage", iChatComponent);
            addChatMessage.invoke(thePlayer, component);
        } catch (Throwable t) {
            log("sendClientChat failed: " + t);
        }
    }

    /**
     * Append a timestamped line to %APPDATA%\Aurex\agent.log.
     *
     * We write to a file, not System.out, because Lunar swallows stdout —
     * println lines from inside Lunar's JVM don't show up anywhere visible.
     * A file on disk is the reliable way to see that our code ran.
     */
    static void log(String message) {
        File file = logFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            // Can't create %APPDATA%\Aurex — bail silently. We deliberately
            // do NOT throw, because an exception from agentmain would be
            // caught by the JVM and surfaced as AgentInitializationException
            // in the loader, which is noisy for a best-effort log write.
            return;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        // try-with-resources — guarantees the writer is closed even on throw.
        // FileWriter(file, true) opens in append mode (like "a" in Python).
        try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
            out.println(timestamp + " " + TAG + ": " + message);
        } catch (IOException ignored) {
            // Same rationale as above — never let a log failure bubble up.
        }
    }

    /**
     * Truncate the log at the start of each JVM run so old launches don't
     * pile up. Called from {@link #premain} exactly once per JVM — the
     * bootstrap and MC-loader copies of Agent never run this, they just
     * append. Safe if the file doesn't exist yet.
     */
    private static void truncateLog() {
        File file = logFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        try (PrintWriter out = new PrintWriter(new FileWriter(file, false))) {
            // opening in non-append mode truncates; nothing to write.
        } catch (IOException ignored) {
            // Same as log(): never let a log-init failure bubble up.
        }
    }

    /** Resolve %APPDATA%\Aurex\agent.log. Falls back to user home on non-Windows. */
    private static File logFile() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) {
            appdata = System.getProperty("user.home");
        }
        return new File(new File(appdata, "Aurex"), "agent.log");
    }
}
