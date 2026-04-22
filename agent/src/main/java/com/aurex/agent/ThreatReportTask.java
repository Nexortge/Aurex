package com.aurex.agent;

import java.util.TimerTask;

/**
 * Fires the M14 threat analyzer ~{@link Agent#THREAT_REPORT_DELAY_MS}ms after
 * the auto-arm kicks off fetches. Runs on Aurex's Timer thread; hops to
 * {@code AgentImpl.fireThreatReport} via reflection.
 *
 * <p><b>Why delayed:</b> the auto-arm kicks off a fetch burst for every real
 * player in the tab. Responses trickle in asynchronously — waiting a few
 * seconds post-arm gives {@link com.aurex.agent.api.StatsCache} time to settle
 * so the report reads from a mostly-warm cache rather than skipping every
 * still-in-flight future.
 *
 * <p><b>Once-per-game semantics:</b> {@link Agent#autoArmNow()} is itself
 * already token-dedup'd via {@link AutoArmTask}, so only one
 * {@code autoArmNow} → one {@code ThreatReportTask} fires per countdown
 * sequence. No extra generation-token dance needed here.
 *
 * <p>Top-level class (not anonymous) so it compiles to a standalone {@code
 * .class} file — mandatory for {@link AgentPublisher} to publish it into
 * Lunar's MC classloader alongside Agent.
 */
final class ThreatReportTask extends TimerTask {

    @Override
    public void run() {
        try {
            if (!Agent.isDisplayEnabled()) {
                // User hit AX-off between the auto-arm and the report firing.
                Agent.log("ThreatReportTask: display disabled, skipping");
                return;
            }
            Agent.fireThreatReport();
        } catch (Throwable t) {
            Agent.log("ThreatReportTask failed: " + t);
        }
    }
}
