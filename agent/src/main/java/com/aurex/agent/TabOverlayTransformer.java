package com.aurex.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Patches two methods on {@code GuiPlayerTabOverlay}:
 *
 * <ul>
 *   <li><b>{@code renderPlayerlist}</b> — injects
 *       {@code INVOKESTATIC Agent.onTabRender()V} at HEAD (M3 hook,
 *       render-throttled heartbeat log).</li>
 *   <li><b>{@code getPlayerName}</b> — before every {@code ARETURN}, pipes the
 *       name through {@code Agent.decorateName(String)String} so M4+ can
 *       rewrite what actually shows up in the tab list.</li>
 * </ul>
 *
 * Shares {@link AgentPublisher} with {@link ChatCommandTransformer} — the first
 * transformer whose target loads does the defineClass dance; the others just
 * see {@code published=true} and patch normally.
 *
 * See {@code memory/project_lunar_internals.md} for the classloader gotcha and
 * why publishing Agent directly into Lunar's MC loader is mandatory.
 */
final class TabOverlayTransformer implements ClassFileTransformer {

    private static final String TARGET = "net/minecraft/client/gui/GuiPlayerTabOverlay";

    // renderPlayerlist(int, Scoreboard, ScoreObjective) -> void
    private static final String RENDER_NAME = "renderPlayerlist";
    private static final String RENDER_DESC =
            "(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V";

    // getPlayerName(NetworkPlayerInfo) -> String
    private static final String GET_NAME_NAME = "getPlayerName";
    private static final String GET_NAME_DESC =
            "(Lnet/minecraft/client/network/NetworkPlayerInfo;)Ljava/lang/String;";

    private static final String HOOK_OWNER = "com/aurex/agent/Agent";
    private static final String ON_TAB_RENDER = "onTabRender";
    private static final String ON_TAB_RENDER_DESC = "()V";
    private static final String DECORATE_NAME = "decorateName";
    // Takes (String, Object) — the Object is actually the NetworkPlayerInfo from
    // ALOAD 1. We declare it as Object, not NetworkPlayerInfo, because the Agent
    // class is compiled without MC libs on the classpath (can't write
    // "NetworkPlayerInfo" as a Java type). The verifier accepts the NPI on the
    // stack because NPI is-a Object. Agent reflects on it to reach the UUID.
    //
    // Critical: the descriptor MUST match Agent.decorateName's declared signature
    // exactly — JVM method lookup is by (name, descriptor), no covariance.
    private static final String DECORATE_NAME_DESC =
            "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;";

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

            boolean patchedRender = false;
            boolean patchedGetName = false;

            for (MethodNode m : node.methods) {
                if (RENDER_NAME.equals(m.name) && RENDER_DESC.equals(m.desc)) {
                    m.instructions.insert(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, HOOK_OWNER, ON_TAB_RENDER,
                            ON_TAB_RENDER_DESC, false));
                    patchedRender = true;
                } else if (GET_NAME_NAME.equals(m.name) && GET_NAME_DESC.equals(m.desc)) {
                    patchedGetName = patchGetPlayerName(m);
                }
            }

            if (!patchedRender) {
                Agent.log("TabOverlayTransformer: renderPlayerlist NOT FOUND");
            }
            if (!patchedGetName) {
                Agent.log("TabOverlayTransformer: getPlayerName NOT FOUND or had no ARETURN");
            }
            if (!patchedRender && !patchedGetName) return null;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] patched = writer.toByteArray();
            Agent.log("TabOverlayTransformer: patched GuiPlayerTabOverlay "
                    + "(render=" + patchedRender + ", getName=" + patchedGetName
                    + ", +" + (patched.length - classfileBuffer.length) + " bytes)");
            return patched;
        } catch (Throwable t) {
            Agent.log("TabOverlayTransformer: FAILED, leaving class untouched: " + t);
            return null;
        }
    }

    /**
     * Before every {@code ARETURN} in {@code getPlayerName}, insert
     * {@code ALOAD 1} (the NetworkPlayerInfo param — slot 0 is {@code this})
     * then {@code INVOKESTATIC Agent.decorateName(String, NetworkPlayerInfo)String}.
     *
     * <p>Stack transitions:
     * <pre>
     *   before hook:  [..., String]
     *   + ALOAD 1:    [..., String, NetworkPlayerInfo]
     *   + INVOKESTATIC (pops 2, pushes 1):  [..., String]
     *   ARETURN:      [..., String]   — unchanged from original
     * </pre>
     * Because the ARETURN stack shape is preserved and we introduce no new
     * branches, no stackmap frame edits are needed ({@code COMPUTE_MAXS} is
     * sufficient; {@code COMPUTE_FRAMES} would be overkill).
     *
     * Iterates over a snapshot (via {@code toArray()}) because
     * {@code insertBefore} mutates the list during traversal.
     */
    private static boolean patchGetPlayerName(MethodNode m) {
        boolean any = false;
        AbstractInsnNode[] insns = m.instructions.toArray();
        for (AbstractInsnNode insn : insns) {
            if (insn.getOpcode() == Opcodes.ARETURN) {
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                call.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HOOK_OWNER, DECORATE_NAME,
                        DECORATE_NAME_DESC, false));
                m.instructions.insertBefore(insn, call);
                any = true;
            }
        }
        return any;
    }
}
