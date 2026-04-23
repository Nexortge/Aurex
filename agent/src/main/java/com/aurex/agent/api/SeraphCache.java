package com.aurex.agent.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * TTL cache wrapped around {@link SeraphClient}, with request deduplication.
 *
 * <p>Shape mirrors {@link StatsCache} — same {@code ConcurrentHashMap} of
 * in-flight futures, same {@link #get} / {@link #peekFuture} split, same TTL
 * semantics — so render-thread code reads this cache the same way it already
 * reads the Hypixel one. Two separate cache objects rather than one
 * multi-payload cache because the failure modes diverge: Seraph's 403 means
 * "bad key for the whole session," while Hypixel 401 can be a transient tier
 * change, and we want them isolated.
 *
 * <p><b>authFailed flag.</b> On the first {@link SeraphAuthException}
 * propagated through any cached future, {@link #authFailed()} flips true and
 * stays true for the life of the cache. Callers should check it before calling
 * {@link #get} — a stuck 403 would otherwise machine-gun Seraph every tab
 * render. {@code AgentImpl} uses this to fire a one-shot red chat warning and
 * then go silent until the user runs {@code AX-seraph <newkey>}.
 */
public final class SeraphCache {

    private static final long DEFAULT_TTL_NS = TimeUnit.HOURS.toNanos(1);

    private final SeraphClient client;
    private final long ttlNs;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean authFailed = false;

    public SeraphCache(SeraphClient client) {
        this(client, DEFAULT_TTL_NS);
    }

    public SeraphCache(SeraphClient client, long ttlNs) {
        this.client = client;
        this.ttlNs = ttlNs;
    }

    /**
     * Return the (possibly in-flight) future for {@code uuid}.
     *
     * <p>First call for a fresh UUID kicks off a fetch. Subsequent calls return
     * the same future until it ages past the TTL. Returns {@code null} if
     * {@link #authFailed()} is true — callers should not trigger more fetches
     * once the session key is known bad.
     */
    public CompletableFuture<SeraphData> get(UUID uuid) {
        if (authFailed) return null;
        long now = System.nanoTime();
        while (true) {
            Entry existing = entries.get(uuid);
            if (existing != null && !isExpired(existing, now)) {
                return existing.future;
            }
            CompletableFuture<SeraphData> fresh = client.fetch(uuid);
            // Hook a terminal check so the first auth failure anywhere flips
            // the session flag without the caller needing to inspect every
            // future.
            fresh.whenComplete((data, err) -> {
                if (err != null && isAuthFailure(err)) {
                    authFailed = true;
                }
            });
            Entry candidate = new Entry(fresh, now);
            Entry prev;
            if (existing == null) {
                prev = entries.putIfAbsent(uuid, candidate);
            } else {
                boolean replaced = entries.replace(uuid, existing, candidate);
                prev = replaced ? null : entries.get(uuid);
            }
            if (prev == null) return fresh;
            fresh.cancel(false);
        }
    }

    /**
     * Read the current future for {@code uuid} without triggering a fetch.
     * Returns {@code null} if nothing is cached. Safe from any thread.
     */
    public CompletableFuture<SeraphData> peekFuture(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? null : e.future;
    }

    public void invalidate(UUID uuid) {
        entries.remove(uuid);
    }

    public int size() {
        return entries.size();
    }

    /** True if any cached fetch failed with auth rejection. Sticky for session life. */
    public boolean authFailed() {
        return authFailed;
    }

    private boolean isExpired(Entry e, long nowNs) {
        if (!e.future.isDone()) return false;
        return nowNs - e.cachedAtNs > ttlNs;
    }

    /**
     * Unwrap {@code whenComplete}'s supplied throwable (which is typically a
     * {@code CompletionException} wrapping the real cause) and check for
     * {@link SeraphAuthException}. Walks one layer of cause because
     * {@link SeraphClient} wraps its {@code IOException}s in
     * {@code RuntimeException} via {@code supplyAsync}.
     */
    private static boolean isAuthFailure(Throwable t) {
        for (int i = 0; i < 4 && t != null; i++) {
            if (t instanceof SeraphAuthException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static final class Entry {
        final CompletableFuture<SeraphData> future;
        final long cachedAtNs;
        Entry(CompletableFuture<SeraphData> future, long cachedAtNs) {
            this.future = future;
            this.cachedAtNs = cachedAtNs;
        }
    }
}
