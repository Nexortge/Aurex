package com.aurex.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

/**
 * On GuiPlayerTabOverlay load, patch renderPlayerlist to call
 * {@link Agent#onTabRender()} at method HEAD.
 *
 * Classloader gotcha (discovered 2026-04-20 M3 crashes):
 * Lunar's MC classloader is wrapped by "IchorPipeline" which overrides
 * class resolution and does NOT delegate to bootstrap or app for packages
 * outside of Lunar/MC. So even after
 * {@link java.lang.instrument.Instrumentation#appendToBootstrapClassLoaderSearch},
 * the patched INVOKESTATIC on {@code com/aurex/agent/Agent} throws
 * {@code NoClassDefFoundError: IchorPipeline can't find class in Genesis}.
 *
 * Workaround: before patching, use {@link ClassLoader#defineClass} reflectively
 * on the MC classloader to publish {@code Agent} directly into it. After
 * that, the loader's own {@code findLoadedClass} check finds our class
 * before IchorPipeline is consulted. The cost is a third copy of {@code Agent}
 * living in MC's loader (app has one, bootstrap has one) — each with its own
 * static state, all writing to the same log file. Harmless.
 */
final class TabOverlayTransformer implements ClassFileTransformer {

    private static final String TARGET = "net/minecraft/client/gui/GuiPlayerTabOverlay";

    // renderPlayerlist(int, Scoreboard, ScoreObjective) -> void
    private static final String RENDER_NAME = "renderPlayerlist";
    private static final String RENDER_DESC =
            "(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V";

    private static final String HOOK_OWNER = "com/aurex/agent/Agent";
    private static final String HOOK_NAME = "onTabRender";
    private static final String HOOK_DESC = "()V";

    /** Once Agent is defined in a loader, we don't need to do it again. */
    private static volatile boolean agentPublished;

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;

        try {
            // Null loader means bootstrap — Agent is already there, safe to patch.
            // Non-null: Lunar's MC loader; we must seed Agent into it first.
            if (loader != null && !publishAgentInto(loader)) {
                Agent.log("TabOverlayTransformer: could not publish Agent; leaving class untouched");
                return null;
            }

            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            MethodNode target = null;
            for (MethodNode m : node.methods) {
                if (RENDER_NAME.equals(m.name) && RENDER_DESC.equals(m.desc)) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Agent.log("TabOverlayTransformer: renderPlayerlist NOT FOUND");
                return null;
            }

            target.instructions.insert(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] patched = writer.toByteArray();
            Agent.log("TabOverlayTransformer: patched renderPlayerlist (+"
                    + (patched.length - classfileBuffer.length) + " bytes)");
            return patched;
        } catch (Throwable t) {
            Agent.log("TabOverlayTransformer: FAILED, leaving class untouched: " + t);
            return null;
        }
    }

    /**
     * Reflectively invoke {@code ClassLoader.defineClass} on {@code target}
     * to install {@code com.aurex.agent.Agent} directly into that loader.
     * Returns true on success (or if already published), false otherwise.
     *
     * Requires {@code java.lang} to be open to our module — see
     * {@link Agent#openJavaLangForReflection}.
     */
    private static synchronized boolean publishAgentInto(ClassLoader target) {
        if (agentPublished) return true;
        try {
            byte[] bytes = readResource("/com/aurex/agent/Agent.class");

            Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            defineClass.invoke(target, "com.aurex.agent.Agent", bytes, 0, bytes.length);

            agentPublished = true;
            Agent.log("published Agent into " + target.getClass().getName());
            return true;
        } catch (Throwable t) {
            Agent.log("publishAgentInto FAILED: " + t);
            return false;
        }
    }

    private static byte[] readResource(String name) throws IOException {
        // Resource comes from the bootstrap-loaded jar (our transformer lives
        // in bootstrap at runtime, so its ClassLoader sees the agent jar).
        InputStream in = TabOverlayTransformer.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("resource not found: " + name);
        }
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
