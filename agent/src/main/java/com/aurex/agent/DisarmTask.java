package com.aurex.agent;

import java.util.TimerTask;

/**
 * Fetch-window close timer. Runs after AX-on (or the M10 auto-arm) on Aurex's
 * daemon Timer thread and clears {@link Agent#fetchArmed} so outbound API calls
 * stop. Does NOT touch {@link Agent#displayEnabled} — the display persists
 * until the user explicitly types AX-off.
 *
 * Top-level class (not an inner class of Agent) so it compiles to its own
 * {@code .class} file, which {@link AgentPublisher} can publish into Lunar's
 * MC classloader. Anonymous inner classes ({@code Agent$1}) would be invisible
 * to MC loader and fail at instantiation.
 *
 * Holds a snapshot of {@link Agent#disarmAt} at schedule time. If somebody
 * re-arms in the meantime, {@code disarmAt} moves, and this task no-ops when
 * it fires — avoiding the "two pending timers both try to close the window" race.
 *
 * {@code announce} controls whether we chat a disarm notice. Manual AX-on
 * passes {@code true} (user initiated the arm, so they should see it close);
 * the M10 countdown auto-arm passes {@code false} (user didn't ask for an
 * arm at all, a chat notice would just be noise on every game start).
 */
final class DisarmTask extends TimerTask {

    private final long expectedDisarmAt;
    private final boolean announce;

    DisarmTask(long expectedDisarmAt, boolean announce) {
        this.expectedDisarmAt = expectedDisarmAt;
        this.announce = announce;
    }

    @Override
    public void run() {
        try {
            if (Agent.disarmAt != expectedDisarmAt) {
                // Re-armed since we were scheduled — let the new task handle it.
                return;
            }
            if (!Agent.fetchArmed) {
                // AX-off beat us to it.
                return;
            }
            Agent.fetchArmed = false;
            Agent.disarmAt = 0L;
            Agent.log("fetch window closed");
            if (announce) Agent.sendClientChat(Agent.PREFIX + "§efetch window closed");
        } catch (Throwable t) {
            Agent.log("DisarmTask failed: " + t);
        }
    }
}
