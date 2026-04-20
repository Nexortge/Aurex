# Aurex

A Seraph-style stat overlay **injected into Lunar Client** (Minecraft 1.8.9) that shows Hypixel Bedwars stats in the tab list. Not a Forge/Fabric mod — a **Java Agent** attached to Lunar's running JVM.

> **Note on folder name:** the project folder on disk is still `C:\Users\nexor\Documents\ownSeraph\` (historical; it was renamed mid-scaffolding). The app's name is **Aurex**; the folder name is purely cosmetic and can be changed whenever.

## Why this matters for how we work

- **User has a C# + React/TS background, limited Java experience.** Favor inline explanations over jargon. When a Java concept has a close C# analogue, mention it.
- **Lunar is closed-source and obfuscated.** We cannot hardcode class or method names from Lunar's own code. Anything we touch inside Lunar's classes must be discovered at runtime by bytecode signature (method shape, field types, superclass chain).
- **The unobfuscated MC 1.8.9 side is known.** We can reference vanilla MCP names (e.g. `GuiPlayerTabOverlay#renderPlayerlist`) as targets to find, but the actual names in Lunar's build will be mangled.
- **TOS:** This violates Lunar and Hypixel ToS. User has accepted the risk. Don't re-raise it.

## Tech stack

- **Language:** Java 8 (required — 1.8.9 runs on JVM 8; newer bytecode will not load)
- **Build:** Gradle (wrapper checked in)
- **Bytecode library:** ASM (org.ow2.asm) — for reading and rewriting class files at load time
- **Injection:** Java Attach API (`com.sun.tools.attach.VirtualMachine`) — ships with JDK `tools.jar`
- **HTTP:** `java.net.HttpURLConnection` (no external HTTP lib needed; keeps agent jar small)
- **JSON:** Gson (single dep, small)
- **Target runtime:** Lunar Client 1.8.9 on Windows
- **Chat prefix (in-game):** `[AX]`

## System paths (this machine)

- Lunar launcher: `C:\Users\nexor\AppData\Local\Programs\Lunar Client\`
- Lunar per-user data: `C:\Users\nexor\.lunarclient\`
- Our config (planned): `%APPDATA%\Aurex\config.json`
- Our log (planned): `%APPDATA%\Aurex\agent.log`
- Lunar main class (confirmed via `jps -l`): `com.moonsworth.lunar.genesis.Genesis`

## Project layout (planned)

```
ownSeraph/                     ← physical folder (legacy name)
├── CLAUDE.md                  ← this file
├── PLAN.md                    ← milestone roadmap
├── build.gradle
├── settings.gradle            ← rootProject.name = 'aurex'
├── gradle/wrapper/...
├── loader/                    ← small CLI that attaches the agent to a running Lunar JVM
│   └── src/main/java/com/aurex/loader/
└── agent/                     ← the actual injectable: transformers, hooks, API client, UI
    └── src/main/java/com/aurex/agent/
```

`loader` and `agent` are separate Gradle subprojects because they run in different JVMs (loader attaches FROM outside; agent runs INSIDE Lunar).

## Build & run (once M0 lands)

```
# Build both jars
./gradlew build

# Start Lunar manually, then attach
java -jar loader/build/libs/aurex-loader.jar
```

The loader finds Lunar's PID via `jps`/`VirtualMachine.list()`, then calls `vm.loadAgent("path/to/aurex-agent.jar")`. The agent's `agentmain` runs inside Lunar's JVM with full `Instrumentation` access.

## Architecture principles

1. **Two jars, two JVMs.** Loader is tiny and uses JDK's `tools.jar`. Agent is the thing that actually does work.
2. **Never block the render thread.** All API calls, disk I/O, JSON parsing happen on background threads. Render-thread code only reads pre-computed results from a concurrent cache.
3. **Fail silently in-game, log loudly off-game.** If stats can't be fetched, show nothing or `[?]` — never crash Lunar.
4. **Discover, don't assume.** Use ASM to match classes/methods by shape (opcode sequences, field types, method signatures). Never hardcode obfuscated names; they change on every Lunar update.
5. **Respect the Hypixel API.** 120 requests/minute hard limit. Cache aggressively (5–10 min TTL). Deduplicate concurrent requests for the same UUID.

## Key constraints / gotchas

- **Java Agent quirks:** `premain` runs before `main`; `agentmain` runs after (attach-time). We use `agentmain`. Manifest *must* include `Agent-Class`, `Can-Retransform-Classes: true`, and `Can-Redefine-Classes: true`.
- **ASM version:** Use ASM 9.x (supports Java 8 bytecode fine; newer API is cleaner).
- **Lunar may block reflection** into some internals. If a standard hook fails, we fall back to ASM bytecode rewrite.
- **Gradle version:** Must be 7.x to run on JDK 8 (Gradle 8+ requires JDK 17). We target **Gradle 7.6.4**.

## What's explicitly out of scope (for now)

- Any gameplay modification (movement, rendering entities differently, etc.). This is a **stats overlay only**.
- Linux/Mac support. Windows-first. Cross-platform later if ever.
- Non-Hypixel servers.
- Modes other than Bedwars for V1. The stats layer should be extensible enough that adding SkyWars/Duels is additive, not a rewrite.

## Pointers

- Milestone roadmap: `PLAN.md`
- Hypixel API reference: https://api.hypixel.net/ (public docs)
- MCP 1.8.9 vanilla names: https://mcp.thiakil.com/ (for knowing what we're looking for in the obfuscated build)
