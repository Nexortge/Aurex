package com.aurex.agent.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * TTL cache wrapped around {@link HypixelClient}, with request deduplication.
 *
 * <p><b>Why the cache holds {@code CompletableFuture} rather than {@code BedwarsStats}:</b>
 * multiple tab renders can ask about the same UUID before the first HTTP response
 * lands. Caching the in-flight future means all callers share the same network
 * request — we never fire two lookups for the same player. Once the future
 * completes, further lookups within the TTL get the cached result directly.
 *
 * <p><b>TTL:</b> 1 hour by default. Bedwars stats drift slowly (a few stars per
 * hour at most), and the overlay's job is "roughly who is this" — not a live
 * leaderboard. A longer window sharply cuts repeat fetches for players we see
 * across multiple lobbies in one session. We intentionally cache failures too
 * (for the same window) so a misconfigured key doesn't machine-gun the API —
 * callers can {@link #invalidate} to force a retry.
 *
 * <p><b>Thread-safe.</b> All state lives in a {@link ConcurrentHashMap}; there
 * is no instance lock. Safe to read from any thread including the render
 * thread (because reads are non-blocking and {@code get(uuid)} only returns
 * the future, not its result).
 */
public final class StatsCache {

    private static final long DEFAULT_TTL_NS = TimeUnit.HOURS.toNanos(1);

    private final HypixelClient client;
    private final long ttlNs;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public StatsCache(HypixelClient client) {
        this(client, DEFAULT_TTL_NS);
    }

    public StatsCache(HypixelClient client, long ttlNs) {
        this.client = client;
        this.ttlNs = ttlNs;
    }

    /**
     * Return the (possibly in-flight) future for {@code uuid}.
     *
     * <p>First call for a fresh UUID kicks off a fetch. Subsequent calls return
     * the same future until it ages past the TTL, at which point the next call
     * starts a fresh fetch.
     */
    public CompletableFuture<BedwarsStats> get(UUID uuid) {
        long now = System.nanoTime();
        while (true) {
            Entry existing = entries.get(uuid);
            if (existing != null && !isExpired(existing, now)) {
                return existing.future;
            }
            // Expired or absent -> start a new fetch.
            CompletableFuture<BedwarsStats> fresh = client.fetch(uuid);
            Entry candidate = new Entry(fresh, now);
            Entry prev;
            if (existing == null) {
                prev = entries.putIfAbsent(uuid, candidate);
            } else {
                // Atomic CAS: only replace if the expired entry is still there.
                boolean replaced = entries.replace(uuid, existing, candidate);
                prev = replaced ? null : entries.get(uuid);
            }
            if (prev == null) {
                // We won the race; our fetch is the canonical one.
                return fresh;
            }
            // Somebody else slotted an entry while we were racing — cancel our
            // orphan fetch (if it hasn't actually started yet) and retry the
            // lookup so we pick up whatever landed.
            fresh.cancel(false);
        }
    }

    /**
     * Read the current future for {@code uuid} <b>without</b> triggering a
     * fetch. Returns {@code null} if nothing is cached. Safe from any thread.
     *
     * <p>Render-thread code uses this to distinguish "have stats / still
     * loading / nothing yet" so we can decide between a real prefix, a
     * {@code [...]} placeholder, or leaving the name alone — without kicking
     * off network traffic just by looking.
     */
    public CompletableFuture<BedwarsStats> peekFuture(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? null : e.future;
    }

    /** Evict the cache entry for {@code uuid} if any. Next {@link #get} will refetch. */
    public void invalidate(UUID uuid) {
        entries.remove(uuid);
    }

    public int size() {
        return entries.size();
    }

    private boolean isExpired(Entry e, long nowNs) {
        // In-flight entries are never "expired" — we want concurrent callers
        // to coalesce onto the same request regardless of TTL.
        if (!e.future.isDone()) return false;
        return nowNs - e.cachedAtNs > ttlNs;
    }

    private static final class Entry {
        final CompletableFuture<BedwarsStats> future;
        final long cachedAtNs;

        Entry(CompletableFuture<BedwarsStats> future, long cachedAtNs) {
            this.future = future;
            this.cachedAtNs = cachedAtNs;
        }
    }
}
