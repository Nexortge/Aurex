# Aurex

A Seraph-style stat overlay for **Hypixel Bedwars**, injected into **Lunar Client 1.8.9**. Replaces the vanilla tab list with a columnar stats table (FKDR, stars, winstreak, W/L, etc.) pulled from the Hypixel API, and flags cheaters by cross-referencing the Seraph and Urchin community blacklists.

## What it does

- Replaces Lunar's tab list with a configurable stats table while you're in a Bedwars lobby or game.
- Fires a chat alert at game start listing threats — high FKDR / high stars / blacklisted players.
- Detects nicked accounts (usernames not present in Mojang's UUID database).
- Per-mode column sets, color ladders, headers, and alert thresholds via JSON config files.
- Hot-swappable API keys through in-game commands — no relaunch to rotate a leaked key.

## How it works

Not a Forge or Fabric mod. It's a **Java agent** (`-javaagent:aurex-agent.jar`) attached to Lunar's JVM at launch. ASM rewrites the obfuscated Minecraft classes at load time to intercept tab rendering, chat, and world-join events.

Because Lunar's classes are mangled per-build and we can't reference them by name, every target is discovered at runtime by bytecode shape — method signatures, opcode sequences, field types — and patched with ASM. All network I/O and JSON parsing happens on background threads; the render thread only reads a pre-computed concurrent cache.

The injection path depends on Lunar allowing `-javaagent:` in user JVM args (it does, as of writing). Runtime attach is blocked, so we run as `premain`, before Lunar's main class loads.

## Install (Windows)

Open PowerShell and paste:

```
irm https://github.com/Nexortge/Aurex/raw/main/install.ps1 | iex
```

The installer downloads the latest jar, prompts for API keys (all optional), and prints a `-javaagent:` line. Paste that line into **Lunar → Settings → Java Integration → JVM Arguments**, then restart Lunar.

Re-run the same command to update.

**Uninstall:** `install.ps1 -Uninstall` (wipes the jar). Add `-Purge` to also drop config and per-mode files.

## In-game commands

All typed into client chat (swallowed before reaching the server):

- `AX-on` / `AX-off` / `AX-status` — arm/disarm display; status readout.
- `AX-help` — full command list.
- `AX-mode <name>` — switch game mode (only `bedwars` ships today).
- `AX-hypixel <key>` / `AX-seraph <key>` / `AX-urchin <key>` — rotate API keys live.
- `AX-check <name>` — debug dump: resolve username → UUID, fire all three lookups, print results.
- `AX-ignore <name>` / `AX-removeignore <name>` — manage the threat-report ignore list (typically your own alts).

## Build from source

Requires **JDK 8** (Lunar 1.8.9 runs on JVM 8; newer bytecode won't load).

```
./gradlew :agent:build
```

Output: `agent/build/libs/aurex-agent.jar`.

## Caveats

- **This violates Lunar Client and Hypixel's Terms of Service.** Use at your own risk — accounts can be banned.
- **Windows only** for now. The agent code itself is portable, but the installer and paths are Windows-specific.
- **Bedwars only** at V1. SkyWars and Duels are planned but not shipped.
- **Access is gated by the owner.** If you see a red "not authorized" line in chat after install, the build isn't running on your account. DM the owner for access.

## Code quality disclaimer

This is **mostly vibecoded**. It works, and it's been tested in-game, but it's not a model of Java craftsmanship — expect duplicated patterns, pragmatic error handling, and comments that explain the weird reflection dances rather than the obvious stuff. The architecture notes in `CLAUDE.md` and the milestone breakdown in `PLAN.md` are the best way to get oriented. Cleanup PRs welcome.
