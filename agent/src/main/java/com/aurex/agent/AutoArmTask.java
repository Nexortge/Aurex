package com.aurex.agent;

import java.util.TimerTask;

/**
 * Deferred auto-arm trigger. Scheduled by {@link Agent#onIncomingChat(Object)}
 * when a Hypixel countdown line is seen — fires {@link Agent#autoArmNow()}
 * after a delay calibrated to land ~2s <i>after</i> the game actually starts.
 *
 * <p><b>Why delayed instead of immediate:</b> Hypixel masks player names during
 * the pre-game countdown ("Player", "ClassAssignment", etc.), so firing fetches
 * at "5 seconds!" would just stash garbage. We want to wait until the Bungee
 * handoff completes and real names unmask, then open the fetch window.
 *
 * <p>Each countdown tick reschedules with a fresh {@code delayMs} — all ticks
 * converge on the same wall-clock target (N seconds to countdown end + 2s for
 * handoff + lag settle). The {@link #token} snapshot of
 * {@link Agent#pendingAutoArmToken} ensures only the latest schedule actually
 * runs — stale tasks no-op on the mismatch.
 *
 * <p>Top-level class (not anonymous / not nested) so {@link AgentPublisher}
 * can publish it into Lunar's MC classloader — same reason as
 * {@link DisarmTask}.
 */
final class AutoArmTask extends TimerTask {

    private final long token;

    AutoArmTask(long token) {
        this.token = token;
    }

    @Override
    public void run() {
        try {
            if (Agent.pendingAutoArmToken != token) {
                // Superseded by a later countdown tick — the newer task owns the arm.
                return;
            }
            Agent.pendingAutoArmToken = 0L;
            Agent.autoArmNow();
        } catch (Throwable t) {
            Agent.log("AutoArmTask failed: " + t);
        }
    }
}
