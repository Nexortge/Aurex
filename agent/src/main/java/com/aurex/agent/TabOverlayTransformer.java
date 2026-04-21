package com.aurex.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * On GuiPlayerTabOverlay load, patch renderPlayerlist to call
 * {@link Agent#onTabRender()} at method HEAD.
 *
 * Shares {@link AgentPublisher} with {@link ChatCommandTransformer} — the first
 * transformer whose target loads does the defineClass dance; the other just
 * sees {@code published=true} and patches normally.
 *
 * See {@code memory/project_lunar_internals.md} for the classloader gotcha
 * and why publishing Agent directly into Lunar's MC loader is mandatory.
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

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;

        try {
            if (loader != null && !AgentPublisher.publishInto(loader)) {
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
}
