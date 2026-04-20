package com.aurex.agent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        inst.addTransformer(new TabOverlayTransformer());
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

    /** Resolve %APPDATA%\Aurex\agent.log. Falls back to user home on non-Windows. */
    private static File logFile() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) {
            appdata = System.getProperty("user.home");
        }
        return new File(new File(appdata, "Aurex"), "agent.log");
    }
}
