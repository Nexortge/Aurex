package com.aurex.agent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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

    /** Startup attach — only hit if someone runs with -javaagent:aurex-agent.jar. */
    public static void premain(String args, Instrumentation inst) {
        log("hello from inside Lunar (premain)");
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
