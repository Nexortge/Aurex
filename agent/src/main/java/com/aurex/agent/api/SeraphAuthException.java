package com.aurex.agent.api;

import java.io.IOException;

/**
 * Thrown by {@link SeraphClient} when Seraph rejects our API key (HTTP 401/403).
 *
 * <p>Surfaced as a typed exception so {@link SeraphCache} can flip its
 * session-wide {@code authFailed} flag and suppress further retries — a bad
 * key would otherwise machine-gun 403s every tab render. {@link AgentImpl}
 * reads the flag and fires a single red chat warning the first frame after
 * the failure, then goes silent.
 *
 * <p>Extends {@link IOException} so {@code CompletableFuture} machinery
 * propagates it the same way as transport errors — no special catch needed in
 * the {@code fetch} hot path.
 */
public final class SeraphAuthException extends IOException {
    public SeraphAuthException(String message) { super(message); }
}
