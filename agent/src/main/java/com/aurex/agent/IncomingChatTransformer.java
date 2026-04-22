package com.aurex.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Hooks {@code net.minecraft.client.gui.GuiNewChat#printChatMessage(IChatComponent)}
 * so Aurex sees every incoming chat line the moment it hits the client's chat UI.
 *
 * <p>M10 uses this to pattern-match Hypixel's countdown lines
 * ({@code "The game starts in N seconds!"}) and auto-arm the fetch window without
 * requiring a manual {@code AX-on}.
 *
 * <p><b>Why here instead of {@code NetHandlerPlayClient#handleChat}:</b>
 * {@code handleChat} fires on the netty IO thread; {@code printChatMessage} runs
 * on the client main thread after Minecraft has dispatched the message onto it.
 * We want main-thread context so {@link Agent#onIncomingChat} can safely poke
 * volatile fields and schedule timers without worrying about netty-thread
 * quirks.
 *
 * <p>Injected bytecode at method HEAD:
 * <pre>
 *   ALOAD 1                                   ; the IChatComponent param
 *   INVOKESTATIC Agent.onIncomingChat(Object)V
 *   [original method body]
 * </pre>
 *
 * <p>Hook is {@code void} and non-branching, so no stack or frame edits are
 * needed — drastically simpler than {@link ChatCommandTransformer}. We also
 * never swallow the message (server messages must still render); this is pure
 * observation.
 *
 * <p>Also fires for client-injected messages (our own {@code [AX]} lines go
 * through {@code addChatMessage} → {@code printChatMessage}). That's fine —
 * the regex in {@link Agent#onIncomingChat} is specific enough not to collide.
 */
final class IncomingChatTransformer implements ClassFileTransformer {

    private static final String TARGET = "net/minecraft/client/gui/GuiNewChat";
    private static final String METHOD_NAME = "printChatMessage";
    private static final String METHOD_DESC = "(Lnet/minecraft/util/IChatComponent;)V";

    private static final String HOOK_OWNER = "com/aurex/agent/Agent";
    private static final String HOOK_NAME = "onIncomingChat";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;

        try {
            if (loader != null && !AgentPublisher.publishInto(loader)) {
                Agent.log("IncomingChatTransformer: could not publish Agent; leaving class untouched");
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
                Agent.log("IncomingChatTransformer: printChatMessage NOT FOUND on GuiNewChat");
                return null;
            }

            InsnList prefix = new InsnList();
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
            prefix.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
            target.instructions.insert(prefix);

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] patched = writer.toByteArray();
            Agent.log("IncomingChatTransformer: patched printChatMessage (+"
                    + (patched.length - classfileBuffer.length) + " bytes)");
            return patched;
        } catch (Throwable t) {
            Agent.log("IncomingChatTransformer: FAILED, leaving class untouched: " + t);
            return null;
        }
    }
}
