package com.aurex.agent;

import java.util.TimerTask;

/**
 * Auto-disarm timer body. Top-level class (not an inner class of Agent) so it
 * compiles to its own {@code .class} file, which {@link AgentPublisher} can
 * publish into Lunar's MC classloader. Anonymous inner classes ({@code Agent$1})
 * would be invisible to MC loader and fail at instantiation.
 *
 * Holds a snapshot of {@link Agent#disarmAt} at schedule time. If somebody
 * re-arms in the meantime, {@code disarmAt} moves, and this task no-ops when
 * it fires — avoiding the "two pending timers both try to disarm" race.
 */
final class DisarmTask extends TimerTask {

    private final long expectedDisarmAt;

    DisarmTask(long expectedDisarmAt) {
        this.expectedDisarmAt = expectedDisarmAt;
    }

    @Override
    public void run() {
        try {
            if (Agent.disarmAt != expectedDisarmAt) {
                // Re-armed since we were scheduled — let the new task handle it.
                return;
            }
            if (!Agent.enabled) {
                // AX-off beat us to it.
                return;
            }
            Agent.enabled = false;
            Agent.disarmAt = 0L;
            Agent.log("auto-disarmed (3s elapsed)");
            Agent.sendClientChat("\u00a7c[AX] auto-disarmed");
        } catch (Throwable t) {
            Agent.log("DisarmTask failed: " + t);
        }
    }
}
