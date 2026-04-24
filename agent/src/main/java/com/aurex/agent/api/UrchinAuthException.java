package com.aurex.agent.api;

import java.io.IOException;

/**
 * Thrown by {@link UrchinClient} when Urchin rejects our API key (HTTP 401/403).
 *
 * <p>Parallel to {@link SeraphAuthException}. Surfaced as a typed exception so
 * {@link UrchinCache} can flip its session-wide {@code authFailed} flag and
 * suppress further retries — a bad key would otherwise spray 403s on every tab
 * render. {@code AgentImpl} reads the flag and fires a single red chat warning
 * the first frame after the failure, then goes silent.
 *
 * <p>Extends {@link IOException} so {@code CompletableFuture} machinery
 * propagates it the same way as transport errors — no special catch needed in
 * the {@code fetch} hot path.
 */
public final class UrchinAuthException extends IOException {
    public UrchinAuthException(String message) { super(message); }
}
