package com.aurex.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Publishes {@code com.aurex.agent.Agent} (and its supporting classes) into a
 * target classloader via reflective {@link ClassLoader#defineClass}.
 *
 * Why this exists: Lunar's MC classloader ("IchorPipeline") refuses to delegate
 * to bootstrap/app for non-Lunar/MC packages, so a patched MC class doing
 * {@code INVOKESTATIC com/aurex/agent/Agent ...} can't resolve Agent unless
 * Agent is already findable via the MC loader's {@code findLoadedClass}. We
 * therefore reflect into {@code defineClass} and publish the class bytes
 * directly into whichever loader is about to own the MC class being patched.
 *
 * Requires {@code java.lang} to be {@code opens}'d to our module on JVM 9+.
 * See {@link Agent#premain}/{@code openJavaLangForReflection}.
 *
 * Idempotent: only the first call per JVM actually defines classes.
 */
final class AgentPublisher {

    /** Classes to publish. Any top-level class referenced by Agent from
     *  inside the MC loader (statically or via {@code new}) must appear here —
     *  otherwise MC loader can't resolve it at runtime. */
    private static final String[] CLASSES = {
            "com.aurex.agent.Agent",
            "com.aurex.agent.DisarmTask"
    };

    private static volatile boolean published;

    private AgentPublisher() {}

    /**
     * Publish our classes into {@code target}. Safe to call repeatedly; only
     * the first call does work.
     *
     * @return true if classes are available in {@code target} (either published
     *         now or on a previous call), false if something went wrong.
     */
    static synchronized boolean publishInto(ClassLoader target) {
        if (published) return true;
        try {
            Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);

            for (String name : CLASSES) {
                String resource = "/" + name.replace('.', '/') + ".class";
                byte[] bytes = readResource(resource);
                defineClass.invoke(target, name, bytes, 0, bytes.length);
                Agent.log("AgentPublisher: published " + name + " into "
                        + target.getClass().getName());
            }

            published = true;
            return true;
        } catch (Throwable t) {
            Agent.log("AgentPublisher.publishInto FAILED: " + t);
            return false;
        }
    }

    private static byte[] readResource(String name) throws IOException {
        InputStream in = AgentPublisher.class.getResourceAsStream(name);
        if (in == null) throw new IOException("resource not found: " + name);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            return buf.toByteArray();
        } finally {
            in.close();
        }
    }
}
