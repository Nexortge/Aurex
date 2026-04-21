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
            return false;
        } catch (Throwable t) {
            log("onOutgoingChat failed: " + t);
            return false;
        }
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
        timer.schedule(new DisarmTask(fireAt), ARM_MS);
    }

    private static void disarm(boolean announce) {
        displayEnabled = false;
        fetchArmed = false;
        disarmAt = 0L;
        log("AX-off (display + fetch off)");
        if (announce) sendClientChat("\u00a7c[AX] off");
    }

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
     * Print a client-side chat line (not sent to the server). Used for AX
     * command confirmations.
     *
     * Reflective because this code path ends up running in three classloader
     * copies (app / bootstrap / MC); only the MC copy can see MC classes at
     * compile time, but reflection works from any of them since
     * {@link Class#forName(String)} uses the caller's classloader, which for
     * the MC copy is Lunar's MC loader — where {@code net.minecraft.*} lives.
     *
     * Package-private so {@link DisarmTask} can call it from the Timer thread.
     */
    static void sendClientChat(String text) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Method getMinecraft = mcClass.getMethod("getMinecraft");
            Object mc = getMinecraft.invoke(null);

            Field thePlayerField = mcClass.getField("thePlayer");
            Object thePlayer = thePlayerField.get(mc);
            if (thePlayer == null) {
                log("sendClientChat: thePlayer is null, skipping: " + text);
                return;
            }

            Class<?> componentClass = Class.forName("net.minecraft.util.ChatComponentText");
            Object component = componentClass.getConstructor(String.class).newInstance(text);

            Class<?> iChatComponent = Class.forName("net.minecraft.util.IChatComponent");
            Class<?> entityPlayer = Class.forName("net.minecraft.entity.player.EntityPlayer");
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
