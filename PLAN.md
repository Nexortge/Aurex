# Aurex — Milestone Roadmap

Each milestone is a **real checkpoint**: it ends with something we can run and visibly verify. If a milestone can't be demoed, it's not done.

We are deliberately not designing anything past M4 in detail — scope past the POC is intentionally loose so we can adjust based on what we learn.

---

## M0 — Environment & scaffolding

**Goal:** Empty but buildable project. No actual behavior yet.

**Deliverable:**
- Gradle multi-project (`loader` + `agent` subprojects)
- Both compile and produce jars with correct manifests
- `aurex-agent.jar` has `Agent-Class` / `Can-Retransform-Classes: true` in its manifest
- `aurex-loader.jar` has a `Main-Class` and can be run with `java -jar`
- `.gitignore`, `gradle.properties`, wrapper files committed

**Blocked by:** JDK 8 installed (currently JRE-only). — **Done.**

**Success check:** `./gradlew build` produces both jars; `java -jar aurex-loader.jar` runs and prints a stub message.

---

## M1 — Injection POC: "hello from agent" — **Done (2026-04-20).**

**Goal:** Prove we can run code inside Lunar's JVM.

**What we shipped:**
- Agent jar with `Premain-Class: com.aurex.agent.Agent` in the manifest
- `premain` writes a timestamped line to `%APPDATA%\Aurex\agent.log`
- Loader exists and builds, but the **runtime-attach path (agentmain) is blocked by Lunar's `-XX:+DisableAttachMechanism`** — kept for future use if Lunar ever drops the flag

**Injection path used:** `-javaagent:<path>\aurex-agent.jar` added via Lunar launcher → Settings → JVM Arguments. Premain runs at JVM startup before Lunar's `main`. This is the same approach Seraph uses.

**Verified:** `agent.log` contains `2026-04-20T... Aurex: hello from inside Lunar (premain)` after launching 1.8.9 through Lunar.

---

## M2 — Find the tab overlay class — **Done (2026-04-20).**

**Expected:** structural matching against obfuscated class. **Actual:** Lunar preserves MCP names for MC classes *and* methods — target is `net.minecraft.client.gui.GuiPlayerTabOverlay#renderPlayerlist(I;Lnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V`, hookable by name.

**Verified via:** a one-off `ClassFileTransformer` that parsed GuiPlayerTabOverlay's bytecode with ASM on load and logged its method table to `agent.log`. Every vanilla method (`getPlayerName`, `renderPlayerlist`, `drawPing`, etc.) is intact. Lunar has its own Mixin handlers on the class (`handler$zib000$lunar$...`, `redirect$...`), but those don't block our hook — they coexist.

---

## M3 — Hook render, log on each tab render — **Done (2026-04-20).**

**What shipped:** ASM transformer injects `INVOKESTATIC Agent.onTabRender()V` at HEAD of `GuiPlayerTabOverlay#renderPlayerlist`. `onTabRender` logs `tab rendered` throttled to 1/sec.

**Classloader battle (cost most of the milestone):** Lunar's MC classloader ("IchorPipeline") refuses to delegate to bootstrap for non-Lunar/MC packages, so `appendToBootstrapClassLoaderSearch` alone leaves our patched INVOKESTATIC unresolvable. Fix is three-layered:
1. Publish agent jar to bootstrap (so `Class.forName("com.aurex.agent.Agent", true, null)` works)
2. Re-enter `start()` via bootstrap-loaded Agent (keeps ASM + transformer on one consistent loader — fixes the loader-constraint violation on `ClassReader.accept`)
3. In the transformer, reflectively `ClassLoader.defineClass` Agent *directly* into Lunar's MC loader before patching, so the injected INVOKESTATIC finds the class without ever consulting IchorPipeline's filter. Requires `java.lang` opened via `Instrumentation.redefineModule` (Java 9+, called reflectively since we compile to Java 8).

**Verified:** `tab rendered` lines in `agent.log` ticking at 1/sec while tab held. All gotchas captured in `memory/project_lunar_internals.md`.

---

## M3.5 — Arm/disarm via chat command + gate all behavior behind it

**Why this exists:** once we move past log-only hooks, anything we do inside `renderPlayerlist` affects what the user sees *and* can cause side effects we don't want on servers like Hypixel — where the tab is used for lobby NPCs, game-start player lists (names obfuscated until round start), etc. We need a hard off switch before we ever modify a pixel or call the Hypixel API.

**Goal:** `AX-on` / `AX-off` typed in chat toggles a global `enabled` flag. The chat message is swallowed client-side (never sent to the server). Every M4+ side effect checks `Agent.enabled` first and no-ops when false.

**Deliverable:**
- `static volatile boolean enabled` on `Agent`, defaults to `false`
- Second ASM transformer on `EntityPlayerSP#sendChatMessage(Ljava/lang/String;)V`: at method HEAD, call `Agent.onOutgoingChat(String)` — if that returns `true`, swallow the packet via an injected `RETURN`; otherwise fall through to vanilla send
- `Agent.onOutgoingChat(String)` recognizes `AX-on` / `AX-off` / `AX-status`, flips the flag, writes a client-side chat line confirming the state (via `Minecraft.thePlayer.addChatMessage(...)` if reachable — otherwise fall back to `agent.log`), and returns `true` to swallow
- `Agent.onTabRender()` from M3 stays as-is (it's just logging, no API calls) — the gate matters from M4 onward

**Success check:** Type `AX-on` in chat → see client-side `[AX] armed` confirmation, message does not appear in server chat. Type `AX-off` → see `[AX] disarmed`. Server sees neither.

**Known risks:**
- `EntityPlayerSP.sendChatMessage` might be inlined or routed differently in Lunar — recon may be needed before writing the transformer
- Injecting an early `RETURN` is more delicate than a HEAD `INVOKESTATIC`; we have to be careful about any parameters still on the stack

---

## M4 — Modify a tab entry

**Goal:** Actually change what the user sees. Replace one player's displayed name with `[TEST] PlayerName`.

**Deliverable:**
- Hook deeper into the render method (or a helper it calls) where the display name is resolved per player
- Prepend `[TEST] ` to every entry, OR (cleaner) to just the local player's entry
- No crashes, no visual glitches

**Success check:** Open tab in-game, see modified names.

**This is the end of the POC phase.** Everything past here is building the actual product on top of a proven hook point.

---

## M5 — Hypixel API client + cache

**Goal:** Given a UUID, return `BedwarsStats` (stars, FKDR, wins, W/L) asynchronously.

**Deliverable:**
- `HypixelClient` class: async HTTP GET to `/v2/player?uuid=...` with API key, Gson-parsed response
- `StatsCache`: `ConcurrentHashMap<UUID, CachedStats>` with 5-min TTL
- Rate limiter: token bucket at 110/min (under the 120 limit with safety margin)
- Request deduplication: if two calls for same UUID arrive while one is in flight, both get the same `CompletableFuture`
- A tiny CLI test harness in the loader that takes a UUID and prints stats — runs independently of Lunar

**Success check:** `java -jar aurex-loader.jar test-api <uuid>` prints real Bedwars stats from the API.

---

## M6 — Wire stats into tab

**Goal:** The actual feature works end-to-end. Tab entries show real Bedwars stats.

**Deliverable:**
- On tab render, for each `NetworkPlayerInfo` in view, look up stats from cache
- If cached: prepend `[⭐350 | 2.5] ` (star + FKDR) with color-coded star tier
- If not cached: kick off async fetch, show `[...]` placeholder
- Star tier colors follow the standard Bedwars prestige palette (stone → iron → gold → diamond → emerald → sapphire → ruby → crystal → opal → amethyst → rainbow)

**Success check:** Join a Bedwars lobby, open tab, see real stats for real players.

---

## M7 — Nicked player detection + chat alert

**Goal:** When a nicked player is detected (API returns no match or flagged nick), show `[NICK]` in tab and print `[AX] -> PlayerName is nicked!` client-side in chat.

**Deliverable:**
- Detection: UUID lookup returns 404, OR Hypixel returns player with `null` stats object, OR player name matches a known nick pattern
- Tab: `[NICK] PlayerName` in red
- Chat injection: client-side-only message (doesn't actually send to server), appears in chat log prefixed `[AX]`

**Success check:** Queue with a friend using `/nick` — nick is flagged in tab and chat.

---

## M8 — Table-style tab layout (big UX swing)

**Goal:** Replace the default tab rendering with a full columnar table: `Stars | Name | FKDR | W/L | Wins`.

**Deliverable:**
- Redirect the render method: instead of letting the vanilla render run, call our own renderer that draws a table using `FontRenderer.drawString` and `Gui.drawRect`
- Columns align, header row, dynamic width based on longest name
- Toggle with a keybind (default: same as vanilla tab, but hold Shift for table view)

**Success check:** Hold tab + shift in a Bedwars lobby, see table-style layout.

**This is a real UX project in its own right — may warrant its own sub-plan when we get here.**

---

## M9 — Config & toggles

**Goal:** Users can configure behavior without editing code.

**Deliverable:**
- `%APPDATA%\Aurex\config.json` loaded on agent start, hot-reloaded on change (file watcher)
- Settings: API key, enabled stat columns (stars, FKDR, W/L, wins, etc.), nick detection on/off, chat alerts on/off, table mode on/off
- Graceful fallback if config is missing or malformed

**Success check:** Edit config file → see changes in-game within a few seconds, no restart needed.

---

## Post-V1 (not scheduled)

- Game-mode detection (read Hypixel locraw / scoreboard → know if we're in Bedwars vs SkyWars vs lobby)
- Stats for other modes (SkyWars, Duels, etc.) — the extensible stats layer from M5 should make this additive
- GUI config screen (vs JSON file)
- Auto-update check

---

## How we work

- **One milestone at a time.** No jumping ahead. Each milestone gets its own commits.
- **If a milestone turns out wrong, we re-plan before proceeding.** POC phase especially — if M1 or M2 reveals that Lunar blocks attachment, the whole architecture may shift.
- **User tests in-game after each milestone.** Nothing counts as done until the user has seen it work in Lunar themselves.
