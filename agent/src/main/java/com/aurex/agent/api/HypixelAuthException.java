package com.aurex.agent.api;

import java.io.IOException;

/**
 * Thrown by {@link HypixelClient} when Hypixel rejects our API key (HTTP
 * 401/403 from {@code /v2/player}).
 *
 * <p>Surfaced as a typed exception so {@link StatsCache} can flip its
 * session-wide {@code authFailed} flag and suppress further retries — a bad
 * key would otherwise machine-gun 403s every tab render. {@code AgentImpl}
 * reads the flag and fires a single red chat warning the first frame after
 * the failure, then goes silent until the user rotates via
 * {@code AX-hypixel <newkey>}.
 *
 * <p>Extends {@link IOException} so {@code CompletableFuture} machinery
 * propagates it the same way as transport errors — no special catch needed in
 * the {@code fetch} hot path.
 */
public final class HypixelAuthException extends IOException {
    public HypixelAuthException(String message) { super(message); }
}
