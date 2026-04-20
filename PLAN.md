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

## M2 — Find the tab overlay class

**Goal:** From inside the agent, locate Lunar's obfuscated version of `GuiPlayerTabOverlay`.

**Deliverable:**
- On agent start, register a `ClassFileTransformer` that inspects every class as it loads
- Match a class as "tab overlay" by signature: extends `Gui` (or its obfuscated parent), contains a method that takes `(int, Scoreboard, ScoreObjective)` or equivalent bytecode shape, and references `NetworkPlayerInfo`
- Log the matched class's obfuscated name to `agent.log` when found
- Cache the match so we only log once

**Success check:** `agent.log` contains a line like `Aurex: matched tab overlay class = net.minecraft.client.gui.aXk` (obfuscated name will vary).

**Known risks:**
- Lunar may obfuscate parent classes too — we may need to match on a chain of signatures, not just one.
- Some classes are loaded before our transformer registers. We'll use `Instrumentation.getAllLoadedClasses()` + `retransformClasses` for classes already loaded.

---

## M3 — Hook render, log on each tab render

**Goal:** Inject a callback into the tab render method. Prove we can run arbitrary code on every tab frame.

**Deliverable:**
- ASM transformer that, on the matched tab overlay class, finds the render method and inserts a call to `Aurex.onTabRender(...)` at method entry
- `Aurex.onTabRender` logs once per second (throttled) to confirm it's being hit

**Success check:** While Lunar's tab is open, `agent.log` shows regular `tab rendered` lines; when closed, they stop (or slow).

**Known risks:**
- The render method may be called every frame even with tab closed (just rendering nothing). Throttle in code.
- Bytecode verification errors if we inject incorrectly — these crash the class load. Start with the simplest possible `INVOKESTATIC` injection.

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
