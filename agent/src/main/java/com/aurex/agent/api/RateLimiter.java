package com.aurex.agent.api;

import java.util.concurrent.TimeUnit;

/**
 * Simple token-bucket rate limiter.
 *
 * <p>Hypixel's per-key limit depends on the app's tier in the Developer
 * Dashboard, not a fixed number — responses include {@code RateLimit-Limit},
 * {@code RateLimit-Remaining}, {@code RateLimit-Reset} headers that describe
 * the live budget. This class is a conservative client-side floor (default
 * 30/min, burst 16) so we never punch past a modest default tier even if the
 * server hasn't had a chance to tell us the real ceiling yet.
 * {@link HypixelClient} observes the response headers and handles 429s; a
 * future iteration can feed observed {@code RateLimit-Limit} back in to raise
 * this floor for higher-tier keys.
 *
 * <p>{@link #acquire()} blocks the calling thread until a token is available.
 * Safe to call from any thread — access is serialized on an internal lock.
 * The caller is expected to be on a background executor (see {@link HypixelClient});
 * <b>never call this from the render thread.</b>
 */
public final class RateLimiter {

    private final int burst;
    /** Nanoseconds between token refills. Smaller = faster refill. */
    private final long refillIntervalNs;

    /** Current token count. Guarded by {@code lock}. Fractional to avoid granularity loss. */
    private double tokens;
    /** Last time we credited refill tokens. Guarded by {@code lock}. */
    private long lastRefillNs;

    private final Object lock = new Object();

    public RateLimiter(int tokensPerMinute, int burst) {
        if (tokensPerMinute <= 0) throw new IllegalArgumentException("tokensPerMinute must be > 0");
        if (burst <= 0) throw new IllegalArgumentException("burst must be > 0");
        this.burst = burst;
        this.refillIntervalNs = TimeUnit.MINUTES.toNanos(1) / tokensPerMinute;
        this.tokens = burst;
        this.lastRefillNs = System.nanoTime();
    }

    /** Defaults: 30 req/min (≈1 token every 2s), burst of 16. */
    public static RateLimiter defaultHypixel() {
        return new RateLimiter(30, 16);
    }

    /**
     * Defaults: 90 req/min, burst of 16. Verified via response headers: Seraph
     * advertises {@code x-ratelimit-limit: 120}; we sit a little under so a
     * burst doesn't trip the cliff and the user has headroom for browser /
     * other-tool traffic on the same key.
     */
    public static RateLimiter defaultSeraph() {
        return new RateLimiter(90, 16);
    }

    /**
     * Defaults: 60 req/min, burst of 16. Urchin's published ceiling is looser
     * than Seraph's but we stay conservative for now — a 100-player lobby
     * pre-warm burst ({@code preWarmFetches}) needs headroom on top of whatever
     * the user has running in parallel in a browser mod. Revisit once we see
     * real {@code x-ratelimit-*} headers on live traffic.
     */
    public static RateLimiter defaultUrchin() {
        return new RateLimiter(60, 16);
    }

    /**
     * Block until a single token is available, then consume it.
     * <p>Sleeps outside the lock so other threads can still refill + acquire
     * concurrently. May still take multiple iterations if many threads contend
     * for a tight bucket.
     */
    public void acquire() throws InterruptedException {
        while (true) {
            long sleepMs;
            synchronized (lock) {
                long now = System.nanoTime();
                long elapsed = now - lastRefillNs;
                if (elapsed > 0) {
                    double gained = (double) elapsed / refillIntervalNs;
                    tokens = Math.min(burst, tokens + gained);
                    lastRefillNs = now;
                }
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                double deficit = 1.0 - tokens;
                long waitNs = (long) (deficit * refillIntervalNs);
                sleepMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(waitNs));
            }
            Thread.sleep(sleepMs);
        }
    }
}
