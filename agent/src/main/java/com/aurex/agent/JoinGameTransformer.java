package com.aurex.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Hooks {@code NetHandlerPlayClient#handleJoinGame(S01PacketJoinGame)} — fires
 * exactly once per server/world join (joining a real server, loading a
 * singleplayer world, or switching between Hypixel sub-servers via BungeeCord).
 *
 * <p>Injects {@code INVOKESTATIC Agent.onServerJoin()V} <b>before every RETURN</b>
 * in {@code handleJoinGame}. Can't inject at HEAD because this method is
 * what <i>creates</i> {@code mc.thePlayer} — the chat-send path needs thePlayer
 * to be non-null, which is only true after the method finishes. No new branches
 * / no stackmap edits (the hook is void, stack shape at RETURN is unchanged).
 *
 * <p>BungeeCord-backed servers (e.g. Hypixel) fire handleJoinGame multiple
 * times in rapid succession on connect (proxy → lobby → sub-server). Dedup
 * via a 500ms debounce in {@link Agent#onServerJoin} so we announce once.
 *
 * <p>This is M9's config-reload trigger: the only time we re-read
 * {@code config.json} after startup. No polling, no file watcher — intentional.
 */
final class JoinGameTransformer implements ClassFileTransformer {

    private static final String TARGET = "net/minecraft/client/network/NetHandlerPlayClient";
    private static final String METHOD_NAME = "handleJoinGame";
    private static final String METHOD_DESC =
            "(Lnet/minecraft/network/play/server/S01PacketJoinGame;)V";

    private static final String HOOK_OWNER = "com/aurex/agent/Agent";
    private static final String HOOK_NAME  = "onServerJoin";
    private static final String HOOK_DESC  = "()V";

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;

        try {
            if (loader != null && !AgentPublisher.publishInto(loader)) {
                Agent.log("JoinGameTransformer: could not publish Agent; leaving class untouched");
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
                Agent.log("JoinGameTransformer: handleJoinGame NOT FOUND on NetHandlerPlayClient");
                return null;
            }

            int injected = 0;
            AbstractInsnNode[] insns = target.instructions.toArray();
            for (AbstractInsnNode insn : insns) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList call = new InsnList();
                    call.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
                    target.instructions.insertBefore(insn, call);
                    injected++;
                }
            }
            if (injected == 0) {
                Agent.log("JoinGameTransformer: handleJoinGame had no RETURN insns — leaving untouched");
                return null;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] patched = writer.toByteArray();
            Agent.log("JoinGameTransformer: patched handleJoinGame "
                    + "(" + injected + " RETURNs, +"
                    + (patched.length - classfileBuffer.length) + " bytes)");
            return patched;
        } catch (Throwable t) {
            Agent.log("JoinGameTransformer: FAILED, leaving class untouched: " + t);
            return null;
        }
    }
}
