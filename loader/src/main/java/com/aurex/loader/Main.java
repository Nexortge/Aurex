package com.aurex.loader;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Loader entry point. Run from a JDK 8 installation — NOT a bare JRE.
 *
 * <h2>Modes</h2>
 * <pre>
 *   java -jar aurex-loader.jar                       attach agent to Lunar (auto-discover)
 *   java -jar aurex-loader.jar --pid 2368            attach to explicit PID
 *   java -jar aurex-loader.jar --agent C:\...\agent.jar
 *   java -jar aurex-loader.jar --pid 2368 --agent ...
 *
 *   java -jar aurex-loader.jar test-api &lt;uuid&gt;       run the M5 API harness
 * </pre>
 *
 * <p>The {@code test-api} subcommand skips the whole attach flow — it's a
 * standalone test harness that exercises {@link com.aurex.agent.api.HypixelClient}
 * without needing Lunar running. See {@link ApiTest} for the actual work.
 *
 * <h2>Why Main is separate from {@link Attacher}</h2>
 * {@code com.sun.tools.attach.*} lives in the JDK's tools.jar, which is NOT on
 * the default Java classpath. Main appends tools.jar to the system classloader
 * first, then loads Attacher (which imports those classes). Java's lazy
 * class-linking makes the ordering safe.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        // Subcommand dispatch. Attach path is still the default (args are positional /
        // flag-style) so existing `java -jar aurex-loader.jar` invocations keep working.
        if (args.length > 0 && "test-api".equals(args[0])) {
            ApiTest.run(rest(args));
            return;
        }

        System.out.println("Aurex loader v0.0.1 -- M1");

        Args parsed = Args.parse(args);

        // --- Step 1: find tools.jar -----------------------------------------
        File toolsJar = findToolsJar();
        if (toolsJar == null) {
            System.err.println("ERROR: could not find tools.jar.");
            System.err.println("You must run this with a JDK 8, not a JRE.");
            System.err.println("  java.home = " + System.getProperty("java.home"));
            System.exit(1);
        }
        System.out.println("tools.jar: " + toolsJar.getAbsolutePath());

        // --- Step 2: find the agent jar -------------------------------------
        File agentJar = resolveAgentJar(parsed.agentPath);
        if (agentJar == null || !agentJar.exists()) {
            System.err.println("ERROR: could not locate aurex-agent.jar.");
            System.err.println("Pass --agent <path>, or place aurex-agent.jar next to this loader.");
            System.exit(1);
        }
        System.out.println("agent jar: " + agentJar.getAbsolutePath());

        // --- Step 3: splice tools.jar into the system classloader -----------
        // The standard JDK 8 trick — the system classloader is a URLClassLoader,
        // and its protected addURL(URL) method is reachable via reflection.
        appendToSystemClasspath(toolsJar);

        // --- Step 4: hand off to Attacher -----------------------------------
        // Class.forName triggers loading of Attacher AFTER tools.jar is on the
        // classpath. Attacher's com.sun.tools.attach.* references can now
        // resolve.
        Class<?> attacherClass = Class.forName("com.aurex.loader.Attacher");
        Method runMethod = attacherClass.getMethod("run", String.class, String.class);
        // First arg = agent path. Second arg = explicit PID, or null to auto-discover.
        runMethod.invoke(null, agentJar.getAbsolutePath(), parsed.pid);
    }

    /** Slice off argv[0] (the subcommand name) before handing to subcommand handlers. */
    private static String[] rest(String[] args) {
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    /** CLI args in a small holder, parsed with zero libraries. */
    private static final class Args {
        String pid;        // null = auto-discover
        String agentPath;  // null = auto-discover

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                if (("--pid".equals(s) || "-p".equals(s)) && i + 1 < argv.length) {
                    a.pid = argv[++i];
                } else if (("--agent".equals(s) || "-a".equals(s)) && i + 1 < argv.length) {
                    a.agentPath = argv[++i];
                } else if (!s.startsWith("-") && a.pid == null && s.matches("\\d+")) {
                    // Bare number = pid. Convenience form.
                    a.pid = s;
                } else if (!s.startsWith("-") && a.agentPath == null) {
                    // Bare non-number = agent path. Convenience form.
                    a.agentPath = s;
                } else {
                    System.err.println("Unrecognized arg: " + s);
                }
            }
            return a;
        }
    }

    /**
     * Locate tools.jar from the running JDK.
     * JDK 8 layouts: either <JDK>/jre (java.home) + ../lib/tools.jar,
     *                or <JDK> (java.home) + lib/tools.jar.
     */
    private static File findToolsJar() throws Exception {
        String javaHome = System.getProperty("java.home");
        File sibling = new File(javaHome, "../lib/tools.jar");
        if (sibling.exists()) return sibling.getCanonicalFile();
        File direct = new File(javaHome, "lib/tools.jar");
        if (direct.exists()) return direct.getCanonicalFile();
        return null;
    }

    /**
     * Resolve the agent jar path, in priority order:
     *   1. Explicit path (from --agent)
     *   2. Same directory as this loader jar  (distribution layout)
     *   3. ../../../agent/build/libs/aurex-agent.jar  (Gradle dev layout)
     */
    private static File resolveAgentJar(String explicit) throws Exception {
        if (explicit != null) {
            return new File(explicit).getAbsoluteFile();
        }
        File loaderJar = new File(
                Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        File loaderDir = loaderJar.getParentFile();

        File sameDir = new File(loaderDir, "aurex-agent.jar");
        if (sameDir.exists()) return sameDir;

        File devPath = new File(loaderDir, "../../../agent/build/libs/aurex-agent.jar");
        if (devPath.exists()) return devPath.getCanonicalFile();

        return null;
    }

    /**
     * Append a jar onto the system classloader's URL list, via reflection.
     * Works on JDK 8 because the system classloader is a URLClassLoader.
     */
    private static void appendToSystemClasspath(File jar) throws Exception {
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        if (!(sys instanceof URLClassLoader)) {
            throw new IllegalStateException(
                    "System classloader is not URLClassLoader (JDK 9+?). Aurex requires JDK 8. actual="
                  + sys.getClass().getName()
            );
        }
        Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        addURL.setAccessible(true);
        addURL.invoke(sys, jar.toURI().toURL());
    }
}
