package com.aurex.agent.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * TTL cache wrapped around {@link UrchinClient}, with request deduplication.
 *
 * <p>Shape mirrors {@link SeraphCache}: same {@code ConcurrentHashMap} of
 * in-flight futures, same TTL semantics, same sticky {@code authFailed} flag.
 * Two separate cache objects rather than a shared multi-payload cache because
 * the failure modes diverge (different auth mechanisms, different vendors).
 *
 * <p><b>Key vs. IGN:</b> entries are keyed by UUID alone; the IGN passed to
 * {@link #get(UUID, String)} is only used for the first fetch that populates
 * an entry. Subsequent callers get the same future regardless of whether they
 * supply a different IGN. Fine in practice because IGN is advisory — Urchin
 * matches primarily on UUID.
 *
 * <p><b>authFailed flag.</b> On the first {@link UrchinAuthException}
 * propagated through any cached future, {@link #authFailed()} flips true and
 * stays true for the life of the cache. Callers should check it before calling
 * {@link #get} — a stuck 403 would otherwise machine-gun Urchin every tab
 * render. {@code AgentImpl} uses this to fire a one-shot red chat warning and
 * then go silent until the user runs {@code AX-urchin <newkey>}.
 */
public final class UrchinCache {

    private static final long DEFAULT_TTL_NS = TimeUnit.HOURS.toNanos(1);

    private final UrchinClient client;
    private final long ttlNs;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean authFailed = false;

    public UrchinCache(UrchinClient client) {
        this(client, DEFAULT_TTL_NS);
    }

    public UrchinCache(UrchinClient client, long ttlNs) {
        this.client = client;
        this.ttlNs = ttlNs;
    }

    /**
     * Return the (possibly in-flight) future for {@code uuid}. {@code ign} is
     * passed through on first fetch; ignored on cache hit. Returns {@code null}
     * when {@link #authFailed()} is true so callers don't trigger more fetches
     * after the key is known bad.
     */
    public CompletableFuture<UrchinData> get(UUID uuid, String ign) {
        if (authFailed) return null;
        long now = System.nanoTime();
        while (true) {
            Entry existing = entries.get(uuid);
            if (existing != null && !isExpired(existing, now)) {
                return existing.future;
            }
            CompletableFuture<UrchinData> fresh = client.fetch(uuid, ign);
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
    public CompletableFuture<UrchinData> peekFuture(UUID uuid) {
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
     * Walk up to 4 layers of cause looking for {@link UrchinAuthException} —
     * {@code CompletableFuture}'s {@code CompletionException} wraps whatever
     * {@code supplyAsync} threw, which in turn wraps the client's IOException.
     */
    private static boolean isAuthFailure(Throwable t) {
        for (int i = 0; i < 4 && t != null; i++) {
            if (t instanceof UrchinAuthException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static final class Entry {
        final CompletableFuture<UrchinData> future;
        final long cachedAtNs;
        Entry(CompletableFuture<UrchinData> future, long cachedAtNs) {
            this.future = future;
            this.cachedAtNs = cachedAtNs;
        }
    }
}
