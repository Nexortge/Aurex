package com.aurex.agent;

/**
 * Single source of truth for the Aurex version string.
 *
 * <p>Bumped in lockstep with {@code version.txt} at the repo root — the
 * installer fetches {@code version.txt} from GitHub and the agent compares
 * it against {@link #VERSION} on world-join to decide whether to surface
 * an "update available" chat reminder (see {@link UpdateCheck}).
 *
 * <p>Not driven from the jar manifest ({@code Implementation-Version})
 * because the manifest attribute is awkward to read at runtime with the
 * bootstrap-append + MC-loader-seed classloader split, and a plain Java
 * constant is easier to grep.
 */
public final class Version {

    /**
     * Current shipping version. When bumping, also update
     * {@code version.txt} at the repo root — the agent compares the two
     * on world-join and prompts the user to re-run the installer when they
     * diverge.
     */
    public static final String VERSION = "0.1.0";

    /**
     * User-Agent reused by every outbound HTTP call the agent makes
     * (Hypixel, Seraph, Urchin, Mojang, whitelist, version check). Carries
     * the running version so API operators can correlate traffic to a
     * build if something misbehaves.
     */
    public static final String USER_AGENT = "Aurex/" + VERSION + " (+github:aurex)";

    private Version() {}
}
