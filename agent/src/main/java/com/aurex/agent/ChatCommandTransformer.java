package com.aurex.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Intercept outgoing chat at the client level so we can implement {@code AX-on}
 * / {@code AX-off} / {@code AX-status} without ever sending them to the server.
 *
 * Target: {@code net.minecraft.client.entity.EntityPlayerSP#sendChatMessage(String)}
 * — 1.8.9 MCP name. EntityPlayerSP is the client's player entity; its
 * {@code sendChatMessage} queues a {@code C01PacketChatMessage} to the server.
 * Hooking at HEAD means we see the raw unfiltered string and can short-circuit
 * before any packet is built.
 *
 * Injected bytecode at method HEAD:
 * <pre>
 *   ALOAD 1                              ; load the String parameter
 *   INVOKESTATIC Agent.onOutgoingChat(String)Z
 *   IFEQ continueLabel                   ; if false (not our command), fall through
 *   RETURN                               ; if true, swallow the packet
 *   continueLabel:
 *   [original method body]
 * </pre>
 *
 * Works in singleplayer too: {@code EntityPlayerSP.sendChatMessage} is called
 * regardless of whether the server is integrated or remote.
 */
final class ChatCommandTransformer implements ClassFileTransformer {

    private static final String TARGET = "net/minecraft/client/entity/EntityPlayerSP";
    private static final String METHOD_NAME = "sendChatMessage";
    private static final String METHOD_DESC = "(Ljava/lang/String;)V";

    private static final String HOOK_OWNER = "com/aurex/agent/Agent";
    private static final String HOOK_NAME = "onOutgoingChat";
    private static final String HOOK_DESC = "(Ljava/lang/String;)Z";

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;

        try {
            if (loader != null && !AgentPublisher.publishInto(loader)) {
                Agent.log("ChatCommandTransformer: could not publish Agent; leaving class untouched");
                return null;
            }

            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            MethodNode target = null;
            for (MethodNode m : node.methods) {
                if (METHOD_NAME.equals(m.name) && METHOD_DESC.equals(m.desc)) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Agent.log("ChatCommandTransformer: sendChatMessage NOT FOUND on EntityPlayerSP");
                return null;
            }

            InsnList prefix = new InsnList();
            LabelNode continueLabel = new LabelNode();
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
            prefix.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
            prefix.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
            prefix.add(new InsnNode(Opcodes.RETURN));
            prefix.add(continueLabel);
            // Java 7+ bytecode requires a stackmap frame at every branch target.
            // Without this, the JVM verifier throws:
            //   VerifyError: Expecting a stackmap frame at branch target N
            // At this point the stack is empty (IFEQ popped its int) and locals
            // are unchanged from method entry — so F_SAME (no deltas) is correct.
            prefix.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

            target.instructions.insert(prefix);

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] patched = writer.toByteArray();
            Agent.log("ChatCommandTransformer: patched sendChatMessage (+"
                    + (patched.length - classfileBuffer.length) + " bytes)");
            return patched;
        } catch (Throwable t) {
            Agent.log("ChatCommandTransformer: FAILED, leaving class untouched: " + t);
            return null;
        }
    }
}
