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

## M3.5 — Arm/disarm via chat command + gate all behavior behind it — **Done (2026-04-21).**

**What shipped:**
- `ChatCommandTransformer` hooks `EntityPlayerSP#sendChatMessage(String)` at HEAD. Injected prefix calls `Agent.onOutgoingChat(String)`; if it returns `true`, an injected `RETURN` swallows the packet client-side.
- `Agent.onOutgoingChat` recognizes `AX-on` / `AX-off` / `AX-status` (case-insensitive). Initial implementation used a single `enabled` flag with 3s auto-disarm on everything; **refactored during M4 prep into two flags:**
  - `displayEnabled` — persistent, toggled by `AX-on`/`AX-off`, no auto-off. Gates all render-side mutations (M4+).
  - `fetchArmed` — 3s auto-off window, cleared by `DisarmTask`. Will gate outbound API calls (M5+).
  - `AX-on` turns both on (display persistently, fetch for 3s). `AX-off` turns both off. This way the 3s safety timer limits *only* network traffic; what the user sees stays up until they explicitly type `AX-off`.
- Chat confirmation via reflective `Minecraft.thePlayer.addChatMessage(new ChatComponentText(...))` — green for `[AX] on (fetch 3s)`, yellow for `[AX] fetch window closed`, red for `[AX] off`, yellow for status readout `display=on/off fetch=armed/idle`.
- `DisarmTask` is a top-level class (not a lambda/inner class) so `AgentPublisher` can publish it alongside `Agent` into Lunar's MC classloader. Anonymous inner classes would be invisible to MC loader. Task now only clears `fetchArmed`, leaving `displayEnabled` intact.
- `AgentPublisher` extracted from `TabOverlayTransformer` — now shared between both transformers with a single `published` flag.
- Log truncates on every JVM launch (requested UX — was piling up across crashes).

**Classloader gotcha #2 (on top of the M3 three-layer fix):** Java 7+ bytecode requires a stackmap frame at every branch target. `ClassWriter.COMPUTE_MAXS` does NOT compute frames; injecting an `IFEQ` without a `FrameNode` at its target throws `VerifyError: Expecting a stackmap frame at branch target N` on class load. Fix is a single `FrameNode(F_SAME, 0, null, 0, null)` after the continue label — stack is empty, locals unchanged. Saved to `memory/project_asm_branch_target_frames.md`.

**Verified 2026-04-21:** AX-on → green `[AX] armed (3s)` in chat, message not sent to server; 3s later → red `[AX] auto-disarmed`; AX-on then AX-off → red `[AX] disarmed` immediately; AX-status → yellow state readout. Tab render hook from M3 still works alongside the new chat hook. Works in singleplayer too (no server required). Flag-split refactor verified as part of M4.

---

## M4 — Modify a tab entry — **Done (2026-04-21).**

**Goal:** Actually change what the user sees. Append ` [TEST]` to every name rendered in the tab list, gated on `Agent.isDisplayEnabled()`.

**What shipped:**
- Extended `TabOverlayTransformer` to also patch `GuiPlayerTabOverlay#getPlayerName(NetworkPlayerInfo)`. Before every `ARETURN` it injects `INVOKESTATIC Agent.decorateName(String)String`, which consumes the existing String on the stack and pushes a (possibly) modified one. Stack shape at ARETURN unchanged → no stackmap frame edits needed, and no new branches introduced.
- `Agent.decorateName(String)` returns `originalName` unchanged when `displayEnabled` is false; otherwise appends `" [TEST]"`. Never throws — render-thread safety.
- Scoped to *every* tab entry for M4 (simpler proof-of-concept). Refining to local-player-only or per-UUID decoration is M6 work when real stats land.

**Why no column alignment yet:** briefly experimented with reflective `FontRenderer.getStringWidth` + space-padding to line up `[TEST]` as a right-aligned column. MC's default font is variable-width so accuracy was capped at ±4px. Decided to skip — pixel-perfect column layout is M8's job (replace vanilla rendering entirely with our own `FontRenderer.drawString` at computed coords), and tuning a throwaway approximation first is pure churn. Reverted to a plain ` [TEST]` suffix.

**Verified 2026-04-21:** `AX-on`, opened tab in Hypixel lobby — every row ended with ` [TEST]` including Lunar-cosmetic rows. 3s later fetch window closed, names stayed decorated. `AX-off` → tab back to vanilla. Lunar's own mixin icon renders alongside unaffected (separate draw call from the name string).

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

## M6 — Wire stats into tab — **Done (2026-04-21).**

**Goal:** The actual feature works end-to-end. Tab entries show real Bedwars stats.

**What shipped in code:**
- `AgentImpl` (bootstrap-only) owns the `StatsCache` + `HypixelClient` and formats the prefix. Kept separate from `Agent` because Lunar's MC loader can't see `com.aurex.agent.api.*` or `com.google.gson.*` — Agent (which lives on MC loader) reflectively forwards across to AgentImpl on bootstrap.
- `TabOverlayTransformer` now pushes `ALOAD 1` (the `NetworkPlayerInfo` param) before the decorate INVOKESTATIC, so the hook receives the NPI and can reach UUID via reflective `getGameProfile().getId()`.
- `Agent.decorateName(String, Object)` gates on `displayEnabled`, then hops to `AgentImpl.decorateInternal(String, Object, boolean)` with the cached Method handle. `fetchArmed` is passed so the 3s arm window still gates new fetches; once cached, stats stay visible until `AX-off`.
- `StatsCache.peekFuture(UUID)` added so render-thread reads don't trigger fetches just by looking.
- Prefix format `§<c>[<stars>✫ <fkdr>]§r ` with standard Bedwars prestige colors (0-99 gray → 900 amethyst; 1000+ placeholder solid gold, rainbow is M8 territory).
- `AgentImpl` is preloaded at `premain` via `Class.forName(..., null)` so API-key load + client construction don't run on the render thread.

**Deliverable:**
- On tab render, for each `NetworkPlayerInfo` in view, look up stats from cache
- If cached: prepend `[<stars>✫ <fkdr>]` with color-coded star tier
- If miss + armed: kick off async fetch, show `[...]` placeholder
- If miss + not armed: leave the name alone (no phantom `[...]` after the arm window closes)
- Star tier colors follow the standard Bedwars prestige palette (stone → iron → gold → diamond → emerald → sapphire → ruby → crystal → opal → amethyst → rainbow)

**Success check:** Join a Bedwars lobby, open tab, see real stats for real players.

---

## M7 — Nicked player detection + chat alert — **Done (2026-04-21).**

**Goal:** When a nicked player is detected (API returns no match or flagged nick), show `[NICK]` in tab and print `[AX] -> PlayerName is nicked!` client-side in chat.

**What shipped in code:**
- `AgentImpl.handleNick` — fires the nick branch of `decorateInternal` when the completed fetch yields `null` stats (HTTP 404 UUID, missing `player` object, or no Bedwars subtree).
- Tab: `§4[NICK]§r ` (dark red) prepended to the row.
- Chat: `§4[AX] -> <name> is nicked!§r` fired via `Agent.sendClientChat`. Dedup via a `ConcurrentHashMap.newKeySet()` keyed on UUID — at most one alert per UUID per arm/disarm cycle. `Agent.disarm` reflectively calls `AgentImpl.clearNickAlerts` on `AX-off` so a subsequent `AX-on` in the same lobby re-announces anyone still nicked.
- NPC / info-row guard: `AgentImpl.extractAlertName` strips `§.` codes, takes the last whitespace-separated token, and only accepts strings matching the MC username shape (`[A-Za-z0-9_]{1,16}`). If it fails, we neither tag nor alert — prevents spam on Hypixel lobby NPCs and decorative info rows (cf. `memory/project_hypixel_tab_hazards.md`).
- `Agent.sendClientChat(String, ClassLoader)` overload — the existing method used `Class.forName(name)`, which uses the caller's classloader; from bootstrap-Agent (where `AgentImpl` lives) that can't resolve `net.minecraft.*`. New overload takes an explicit loader; `AgentImpl` snatches one off the first `NetworkPlayerInfo` it sees and caches it for subsequent chat sends. Falls back to context classloader + `Agent.class.getClassLoader()` if null.

**Deliverable:**
- Detection: UUID lookup returns 404, OR Hypixel returns player with `null` stats object, OR player name matches a known nick pattern
- Tab: `[NICK] PlayerName` in red
- Chat injection: client-side-only message (doesn't actually send to server), appears in chat log prefixed `[AX]`

**Success check:** Queue with a friend using `/nick` — nick is flagged in tab and chat.

**False-positive note:** Anyone who has never played Bedwars on their real account returns the same "no stats" shape as a nick. Plan explicitly folds this into the detection criteria; in practice on Hypixel the false-positive rate is low because most lobby/match participants have at least some Bedwars history. Tighter gating (e.g. pre-game UUID snapshot + Mojang lookup for modes that mask names) is post-M7 work.

---

## M8 — Table-style tab layout — **Done (2026-04-21, teams-of-one verified).**

**Goal:** Replace the default tab rendering with a full columnar table: `Stars | Name | FKDR | W/L | Wins`.

**What shipped in code:**
- New `TabRenderer` class on Lunar's MC loader (published by `AgentPublisher` alongside `Agent` + `DisarmTask`). Reflection-only for every `net.minecraft.*` touch so the agent still compiles without MC libs on the classpath. Must never throw from `render(int)` — catches `Throwable` and returns `false` to fall back to vanilla.
- `TabOverlayTransformer.patchRenderPlayerlist` swapped from single `INVOKESTATIC` to a conditional early-return at HEAD: `Agent.onTabRender()V`; `ILOAD 1`; `Agent.renderAurexTab(I)Z`; `IFEQ skip`; `RETURN`; `skip: F_SAME`. The `FrameNode` is mandatory (cf. `memory/project_asm_branch_target_frames.md`).
- `Agent.renderAurexTab(int)` = thin gate — checks `displayEnabled` then delegates to `TabRenderer.render`. Always-on once `AX-on`; no Shift toggle.
- `Agent.getTableRows(Object[])` hops across to `AgentImpl.getTableRows` (bootstrap) carrying the `NetworkPlayerInfo[]`. Data crossing the loader boundary is `java.*` only (`List<String[]>`) so both loaders resolve.
- `AgentImpl.getTableRows` reads `StatsCache` via `peekFuture` (no fetch kickoff) per row, then fires a fetch when `fetchArmed && displayEnabled && UUID is v4`. Fetch trigger lives here now because vanilla `getPlayerName` is skipped under M8 early-return so the M4 decorator path no longer runs.
- Row layout: `[✫ stars | name | FKDR | W/L | Wins]` with §-codes baked in by `AgentImpl` and drawn as-is by `TabRenderer`.
- **Ranks + team colors:** instead of using `gameProfile.getName()`, we replicate vanilla's upstream chain — `npi.getDisplayName().getFormattedText()` first (Hypixel sets this for rank prefixes like `[MVP++]`), then `ScorePlayerTeam.formatPlayerName(team, raw)`, then plain `rawName`. This deliberately skips our own M4 `decorateName` hook so stats don't leak back into the name cell.
- **Sort order:** primary key = scoreboard team registered name (Hypixel uses `0000_MVPPP`, `0001_MVPP`, … so lex order reproduces rank stratification in lobby and team grouping in a Bedwars match). Secondary = status rank (NICK → REAL → PLACEHOLDER → UNKNOWN). Tertiary within REAL = FKDR desc; else name alphabetical.
- NPC / info-row filter: v4-UUID check pre-fetch (Hypixel NPCs are v2 UUIDs) so we don't burn rate-limit slots on decorative rows.
- Nick alert dedup shares `alertedNicks` set with the M4/M7 decorator path, so a nick is announced once per UUID per arm/disarm cycle regardless of which path detected it.

**Known gaps / polish that moved into later milestones:**
- Stars/FKDR/W/L/Wins colors are only partially tiered — full color-code schema lives in M11.
- No auto-arm fetch on game start — M10.
- Chat target alert system for specific players — M14.
- Cheater / client tags — M15 (Seraph API integration).
- **Health indicator missing.** Vanilla tab renders each player's hearts (scoreboard-health objective, the row of red hearts next to the name) inside `renderPlayerlist`. Our early-return skips it. Re-add as a dedicated column — read the same scoreboard objective vanilla uses (`GuiPlayerTabOverlay.drawScoreboardValues` / objective slot 0) and render it as either ascii hearts or an icon column. Placement TBD; user flagged as important, so handle before or during M11.
- **Player head icon missing.** Vanilla tab draws each player's 8×8 face on the left of the row (skin texture, bound via `AbstractClientPlayer.getLocationSkin()` → `Minecraft.getTextureManager()`; vanilla uses `Gui.drawTexturedModalRect` with UV 8,8,8,8 for the face layer and 40,8,8,8 for the hat overlay). Early-return skips this too. **Tied to the Name cell, not its own column** — draw the face to the left of the name inside whatever x-slot the `name` column occupies, and offset the name string by ~10px to make room. This way reordering columns in config keeps the head next to the name (where the user expects it), and users who drop `name` from `columns` also drop the head. Needs GL state (`GlStateManager.color`, texture bind) and the skin-texture location from NPI. Handle alongside health in M11.

**Verified 2026-04-21:** private Bedwars game with teams of one — table renders with rank prefixes intact, team colors preserved, order matches vanilla Hypixel tab stratification. Larger team sizes not yet tested in-game.

---

## M9 — Config & toggles

**Goal:** Users can configure behavior without editing code.

**Deliverable:**
- `%APPDATA%\Aurex\config.json` loaded on agent start, hot-reloaded on change (file watcher)
- Settings: API key, enabled stat columns (stars, FKDR, W/L, wins, etc.), column order, nick detection on/off, chat alerts on/off
- Graceful fallback if config is missing or malformed

**Success check:** Edit config file → see changes in-game within a few seconds, no restart needed.

---

## M10 — Auto-fetch on game-start chat trigger — **Done (2026-04-22).**

**Goal:** Stats are already loaded by the time the match starts — no manual `AX-on` needed mid-queue.

**What shipped:**
- New `IncomingChatTransformer` targeting `net.minecraft.client.gui.GuiNewChat#printChatMessage(IChatComponent)V`. Injects `ALOAD 1; INVOKESTATIC Agent.onIncomingChat(Object)V` at HEAD. Void hook, no branches — no stackmap frame edits needed, unlike M3.5's `ChatCommandTransformer`. Picked `printChatMessage` over `NetHandlerPlayClient#handleChat` so the hook runs on the client main thread, not the netty IO thread.
- `Agent.onIncomingChat(Object)` reflects `getUnformattedText()` off the component (parameter is Object so Agent still compiles MC-loader-safe), cheap-paths a `contains("starts in")` filter, then hand-matches `"The game starts in <N> second(s)!"` without regex (tight loop is cheaper than Pattern compile per chat line).
- On match, calls `autoArm()` — sister to `arm()` but silent (no chat feedback) and shorter window (`AUTO_ARM_MS = 2000ms`). Flips BOTH `displayEnabled` and `fetchArmed`, not just fetch: if display were left off the tab renderer stays gated, which also gates fetch kick-off, so the feature would be a no-op. Auto-arming both mirrors the manual `AX-on` UX.
- Dedup: 500ms timestamp guard (`AUTO_ARM_DEDUP_MS`). Countdown ticks fire ~1s apart so each real tick re-arms; duplicate echoes of the same line (within 500ms) don't double-fire. No per-line-content dedup — the DisarmTask generation-token pattern already handles multiple schedules correctly, and each real countdown tick legitimately extending the window is desired (keeps the fetch window open across 5→4→3→2→1 plus 2s into the match).
- Manual `AX-on` path untouched. `arm()` still chats feedback + uses the 3s window.
- **Eager pre-warm (both arm paths):** `arm()` and `autoArm()` now call `Agent.kickoffFetches()` immediately after flipping the gate flags. Reflects `Minecraft.getMinecraft().getNetHandler().getPlayerInfoMap()` (lives independent of TAB — the client mutates it on every `S38PacketPlayerListItem`, not just during render) → hands the NPI collection to new `AgentImpl.preWarmFetches`, which iterates, v4-filters NPCs, and calls `StatsCache.get(uuid)` for each. Previously the kick-off only fired inside the render path, so arming without holding TAB was a silent no-op; now the arm triggers a fetch burst up front and the first tab-render reads cached data instantly. Dedup is handled by `StatsCache`'s in-flight map, so the 5-tick countdown re-pre-warming on each tick is free.

**Success check:** Queue Bedwars, don't type anything and don't touch TAB — when the countdown ends, press TAB once and every real player already has cached stats (no `[...]` placeholders).

---

## M11 — Stat column coloring + per-mode config — **Done (2026-04-22).**

**Goal:** All data columns get color tiers (not just stars), fully user-configurable, with the config split per game mode so mode-specific settings can be swapped without touching identity settings.

**What shipped:**
- `ColorTier` primitive (`min` threshold + resolved §-code or `rainbow` flag). `parse(label, min, rawColor, issues)` factory drops bad entries into an issues list without throwing; `pick` walks tiers descending so the highest satisfied threshold wins; `colorize` applies the tier (solid §-code, or per-char cycling `§c §6 §e §a §b §d` for rainbow tiers on stars ≥ 1000). Supports MC named colors (`gray`, `light_purple`, `dark_aqua`, ...) with aliases (`grey`, `pink`) and raw §-codes.
- **Config file split** from a single file to two:
  - `%APPDATA%\Aurex\config.json` — identity-scoped (`apiKey`, `activeMode`, `nickDetection`, `chatAlerts`).
  - `%APPDATA%\Aurex\modes\<mode>.json` — per-mode (`columns`, `colors`). First file shipped: `bedwars.json`.
  - Rationale: swapping mode shouldn't churn apiKey / nick settings; promoting any field to per-mode later is a line move, not a schema migration.
- **Self-documenting auto-generate:** on first launch the generated `modes/bedwars.json` contains a `colors` tier ladder for *every* supported column (`stars`, `fkdr`, `wl`, `wins`, `health`) even when the default `columns` array only has a subset. Users discover available columns by reading the file — no separate reference doc.
- **Forward-compat backfill:** `ModeConfig.parse` fills in missing column tiers with built-in defaults, so adding a new stat column in a later release doesn't break existing user configs.
- `AgentImpl.formatCell` / `formatStatsPrefix` / `formatHealth` all route through `ColorTier.colorize(cfg.colors.get(COL_X), value, text)` — one lookup path regardless of render context.
- Default palettes (source of truth in `ModeConfig` `Object[][]` tables, used both in-memory and when serializing the default JSON):
  - Stars per 100 prestige: gray → white → gold → aqua → dark_green → dark_aqua → dark_red → light_purple → blue → dark_purple → rainbow at 1000.
  - FKDR: 0 gray, 1 white, 3 green, 5 blue, 10 light_purple, 20 gold, 50 red, 100 dark_red.
  - W/L: 0 gray, 0.5 white, 1 green, 2 blue, 5 light_purple, 10 gold, 20 red.
  - Wins: 0 gray, 100 white, 500 green, 1k blue, 5k light_purple, 10k gold, 25k red.
  - Health: 1 red, 6 yellow, 11 dark_green.
- `AX-mode` chat command — `AX-mode` / `AX-mode list` prints known modes with a `§a*` marker on the active one; `AX-mode <name>` validates, calls `Config.writeActiveMode` (preserves other fields in `config.json`), reloads config, and announces the switch. Parse issues from the freshly-loaded mode file flush to chat the same way `onServerJoin` handles them.

**Verified 2026-04-22:** default config auto-generates on first launch with all five columns tiered; stars render rainbow past 1000; FKDR/W/L/Wins pop red for sweats; `AX-mode list` correctly highlights the active mode.

---

## M12 — Extended Bedwars stat columns — **Done (2026-04-22).**

**Goal:** Flesh out the Bedwars-mode column catalog beyond the M11 five so users can actually tune what they see. Every new column ships with its default color ladder in `modes/bedwars.json` so the self-documenting file stays useful.

**What shipped:**
- Added columns: `finals` (total final kills), `beds` (beds broken), `winstreak` (API-gated per account — null-safe fallback to `—` when the user has hidden it), `kdr` (regular kill / death), `bblr` (beds broken / lost).
- `BedwarsStats` extended with the matching fields; `HypixelClient` parses them from the same `/v2/player` payload (no extra API call).
- New `Config.COL_*` constants + `ModeConfig.BW_*` default palettes + `AgentImpl.formatCell` branches. One `case` per column — additive.
- Defaults NOT added to the default `columns` array — users opt in by editing `modes/bedwars.json`. The palette entries exist in `colors.*` even when the column isn't displayed, so they're discoverable (M11 self-documenting pattern).

**Why here and not later:** scaling the column set after other mode-specific features (target alerts, Seraph integration) would need every added feature to retrofit per-column hooks. Landing the extended catalog before M14+ lets later milestones assume a stable column schema.

---

## M13 — Custom column headers — **Done (2026-04-22).**

**Goal:** Promote tab column headers from hardcoded strings to a user-editable map in the per-mode config, so users can rename `FKDR` → `FK/FD`, swap `✫` for `★`, or localize labels.

**What shipped:**
- `headers: { <colId>: <string> }` object in `modes/<mode>.json`. Ships with defaults for every column M11 + M12 added (`stars → ✫`, `name → Name`, `fkdr → FKDR`, `wl → W/L`, `wins → Wins`, `health → HP`, `finals → Finals`, `beds → Beds`, `ws → WS`, `kdr → KDR`, `bblr → BBLR`).
- `ModeConfig.parseHeaders` parses the map; unknown column ids go into `issues` and are ignored. Missing entries fall back to built-in defaults — same forward-compat/self-heal pattern as `colors`, so pre-M13 user files pick up the new entries on next launch without a wipe.
- `AgentImpl.headerFor(String col)` reads from the loaded config; `buildHeaders` stays unchanged otherwise.
- Auto-generated default mode file includes a header entry for every column so the file keeps being self-documenting.

---

## M14 — Game-start threat report + ignore list — **Mostly done (2026-04-22).**

**Goal redirected during implementation:** the original plan was a *targeted* alert system — per-player list with reasons and row tags. What we actually needed in-game was the opposite shape: a *threshold* alert (call out whichever opponents are dangerous, not opponents you named ahead of time), plus an *ignore* list to exclude your own alts from being flagged as threats. Shipped the threshold flavor; the named-target flavor is deferred.

**What shipped:**
- `ThreatReportTask` fires once per game, `THREAT_REPORT_DELAY_MS` after auto-arm (M10 countdown hook). Scheduled on Aurex's Timer thread; reads from the warm `StatsCache`.
- `AgentImpl.fireThreatReport` groups opponents by scoreboard team → one chat line per team, listing every player that clears `fkdrThreshold` OR `starsThreshold`. Detected nicks always flagged. When nothing clears thresholds anywhere, falls back to a single "§ano sweats — best: …" line with the highest-FKDR opponent so the report is never empty.
- **Bedwars per-slot team canonicalization** (required for teams-of-one / filled teams): Hypixel assigns each player their own scoreboard team (`Green10`, `Green11`, `Red0`, …). Raw `getRegisteredName` grouping emitted one line per opponent and missed the user's own teammates. `AgentImpl.canonicalizeTeamKey` strips the trailing digit run so `Green{N}` collapses to `Green` — one line per color, and the user's team excludes all teammates.
- **Own-team exclusion with three signals** (any one wins): (1) `thePlayer.getTeam().getRegisteredName()` via `fetchOwnTeamKey`, (2) UUID match against `fetchOwnUuid`, (3) name match against `ignoreList` promoting the whole team. Redundant on purpose — (1) fails briefly at world transitions when `thePlayer` is null.
- Per-mode `alerts.fkdrThreshold` / `alerts.starsThreshold` added to `modes/<mode>.json`; defaults `5.0` / `500` for Bedwars. Legacy global `alerts` block auto-migrated into the active-mode file on first load (then stripped from `config.json`) so pre-M14 user edits aren't lost.
- `ignoreList` (lowercased usernames) in global config, edited via `AX-ignore <name>` / `AX-removeignore <name>`. Lives in the global file because alt lists are identity-scoped — same alt across all modes.
- `DisarmTask` chat banner cosmetic tweak to match the M14 `[AX] §efetch window closed` prefix style (was inconsistent).

**Not shipped (deferred):**
- Named-target system: UUID/IGN-specific alerts with per-entry reasons (`AX-target add <name> <reason>` / `AX-target list`) and row tagging in tab. If the threshold report covers "identify sweats" well enough in practice, this may not need its own milestone — can be absorbed into M15 (Seraph cheater/client tags) since the row-tag + chat-alert plumbing overlaps.

**Verified 2026-04-22:** private Bedwars game, team-of-one — threat report fires at match start, groups opponents under `Team Red`, excludes own `Team Green`. Nicks appear with `[NICK]` tag. `AX-ignore <name>` excludes the named player's whole team from the report.

---

## M15 — Seraph API integration (cheater tags) — **Shipped, pending in-game verification (2026-04-22).**

**What shipped:**
- `SeraphClient` (`agent/src/main/java/com/aurex/agent/api/SeraphClient.java`) — async HTTP client mirroring `HypixelClient`. `fetch(UUID)` hits `/cubelify/blacklist/{uuid}` with the `Seraph-API-Key` header and returns a `SeraphData`. 401/403 → typed `SeraphAuthException`. Defaults to 90 req/min via new `RateLimiter.defaultSeraph()` factory (Seraph advertises `x-ratelimit-limit: 120`; we sit under for headroom).
- `SeraphCache` — copy of `StatsCache` (1h TTL, ConcurrentHashMap of in-flight futures, `get` / `peekFuture` / `invalidate`). Adds a sticky `authFailed()` flag — first `SeraphAuthException` flips it true and all future `get` calls short-circuit until the user rotates their key, so a bad key doesn't machine-gun Seraph every tab render.
- `SeraphData` + `SeraphTag` — immutable POJOs. RGB-to-§-code mapping in `SeraphColors.rgbToSection` runs at parse time so render-path cells are plain string concats. Cheater + bot flags are precomputed off `tag_name` (contains `cheat` / `sniper` / `blacklist` → cheater; `bot` → bot).
- Global config gains `seraphApiKey` (identity-scoped, preserves other fields via new `Config.writeSeraphApiKey`). Env-var fallback intentionally not shipped — M16 territory.
- One new opt-in column registered in `ModeConfig`: `tag` (Seraph blacklist state, Seraph-supplied colors passed through). Default header + placeholder color ladder ship with the mode defaults so the self-documenting JSON keeps listing it even when not displayed (M11 pattern).
- `AgentImpl`: `seraphCache` field alongside `statsCache`, kicked off in both `preWarmFetches` and the render-path `buildRawRow`. `RawRow` carries `SeraphData` so the tag cell renders on any row type (a nicked cheater still gets called out). `formatCell` branch for `COL_TAG` handles placeholder/blank states per row status.
- One-shot chat alerts on first cheater/bot sighting per arm cycle via `alertedCheaters` / `alertedBots` sets, dedup-cleared alongside nicks in `AgentImpl.clearNickAlerts` (called from `Agent.disarm` on `AX-off`). `§4<name> is tagged: <reason>` for cheaters, `§e<name> is a bot` for bots.
- `AX-seraph <key>` chat command wired through `Agent.onOutgoingChat` → `AgentImpl.onAxSeraph`. Persists key via `Config.writeSeraphApiKey`, reloads config, rebuilds `SeraphCache` in place, and clears the auth-warning latch so a fresh 403 can re-raise. **Raw key is never written to `agent.log`** — only the character length is logged as a fingerprint.
- `AX-status` readout extended with a `seraph=on/off` leg so users can confirm the pipeline is live without tailing the log.
- `AX-check <name>` debug command — Mojang username → UUID, fires Hypixel + Seraph lookups, dumps results in client chat. Bypasses arm/display gates so it works any time.
- Auth-fail UX: first frame after `SeraphCache.authFailed()` flips true, `AgentImpl.maybeFireSeraphAuthWarning` posts one red `§c[AX] Seraph API key rejected — run AX-seraph <key> to update` line and then stays silent.

**Scope redirect: detected-client column dropped.** The original plan included a `client` column sourced from `/mod/tests/client/{id}`. That route turned out to be a mod-integration test harness, not a production player lookup (404s on any UUID that hasn't written test data to it first). Cubelify populates their equivalent column via a private server-to-server arrangement with Seraph that our public API key can't replicate. Column removed entirely — tag works, alerts work, blank-until-spec'd wasn't worth the dead code.

**Scope redirect from the M14 deferred piece:** the original M15 plan also listed marker/row-tag rendering as an option. Shipped as one additive column instead (`tag`) rather than extending the row schema — keeps the `TabRenderer` loader-boundary unchanged and matches how every other stat column already works. Row-level tinting can land later if anyone asks.

**Not shipped (deferred):**
- `SERAPH_API_KEY` env-var fallback (matches the planned Hypixel env-var story in M16).
- Named-target system (the M14 deferred piece) — cheater tags cover most of that use case.
- Seraph safelist / personal-add commands (e.g. `AX-safelist add <name>`) — not needed for V1; users manage their list on seraph.si.
- Detected-client column — public Seraph API does not expose a viable endpoint; revisit only if one lands.

**Success check (awaiting user confirmation in-game):** paste a valid key via `AX-seraph <key>`, add `"tag"` and `"client"` to `modes/bedwars.json > columns`, `AX-mode bedwars` to hot-reload, queue a Bedwars lobby. Confirm `tag` populates for blacklisted players, `client` shows `[LUNAR]`/etc. for Seraph-known users, cheater/bot alerts fire once per session. Bad-key path: set an invalid key → expect one red `API key rejected` line and no 403 spam in `agent.log`.

---

## M16 — API key rotation UX — **Done (2026-04-23).**

**Goal:** Make it painless for users on Hypixel's free developer-portal keys (which rotate and can get revoked) to recover without digging in config files. Parity with M15's Seraph rotation UX.

**What shipped:**
- `HypixelAuthException` — typed `IOException` thrown by `HypixelClient` on HTTP 401/403. Preserved through the `supplyAsync` → `CompletionException` wrap so `StatsCache`'s cause-chain walk (4 layers) still sees it.
- `StatsCache.authFailed()` — sticky session-life latch mirroring `SeraphCache`. First auth failure anywhere in a cached future flips the flag; subsequent `get()` calls short-circuit to `null` so a bad key can't machine-gun `/v2/player` on every tab render. Rebuilt in place on rotation — the new cache starts with a clean latch.
- `Config.writeApiKey(String)` — merge-write parallel to `writeSeraphApiKey`, preserves other global-config fields.
- **Launch-time probe:** `AgentImpl.initStatsCache(true)` fires one `statsCache.get(PROBE_UUID)` right after the cache is built (PROBE_UUID = `534de97c-ada7-4a67-b2a4-10fd6dbb9012`, arbitrary valid UUID — Hypixel auth checks the key before touching the UUID, so any well-formed UUID works). Result is logged via the existing `HypixelClient` logger; auth failure latches `StatsCache.authFailed` before the user ever opens tab.
- **Post-rotation probe with chat feedback:** the hot-swap path (`rebuildHypixelPipeline`) fires the same probe with a `whenComplete` callback that chats the outcome — green "key works" on success, red "key rejected" on 401/403 (and pre-fires `hypixelAuthWarningFired` so the render-path warning stays silent), yellow "couldn't verify (network?)" on anything else. No blocking — user sees "Hypixel key updated — probing..." immediately and the outcome line when the response lands.
- `AX-hypixel <key>` chat command — parses a length-10 prefix, bridges to `AgentImpl.onAxHypixel` via the same reflective pattern as `AX-seraph`. Key is validated non-empty, persisted via `Config.writeApiKey`, config reloaded, pipeline rebuilt, probe fired. Raw key never written to `agent.log` — only `length=N` fingerprint.
- `maybeFireHypixelAuthWarning()` — one-shot red chat line (`§c[AX] Hypixel API key rejected — run AX-hypixel <key> to update`) fired from `getTabData` the first tab render after `StatsCache.authFailed()` flips true. Dedup'd via `hypixelAuthWarningFired`; reset on rotation.
- `onServerJoin` hot-swap: if `apiKey` changed between reloads (e.g. user edited `config.json` directly), the pipeline rebuilds in place rather than showing the old M9 "restart Lunar to apply" warning. That warning is removed.
- `AX-status` readout gains a `hypixel=on/off` leg (mirrors the existing `seraph=` leg).
- `AX-help` gains an `AX-hypixel` line.
- `AX-check <name>` output distinguishes the auth-failed state from "no key" (red `auth failed (run AX-hypixel <newkey>)` vs grey `disabled (no key)`).
- **Response-body logging on non-2xx** — both `HypixelClient` and `SeraphClient` now slurp the error stream (truncated to 200 chars, single-lined, UTF-8) and log the body alongside the status code. Landed as part of M16 because debugging "why is my key rejected" is the main use case, but it also helps diagnose 429 rate-limit cliffs and 5xx outages going forward.

**Not shipped (deferred):**
- UUID-shape validation on `AX-hypixel <key>`. The plan originally called for `isUuidShaped` validation, but the PROBE now rejects bad keys with a clear chat line anyway, so pre-validating would only add a second error surface.
- Startup-probe re-run on network reconnect. If the initial probe fails with a transport error (not auth), the latch stays clear and real tab-render lookups will re-try organically as the user queues games.

---

## M17 — Urchin API integration (second-opinion cheater check)

**Goal:** Cross-reference Seraph's blacklist with a second community-maintained blacklist (Urchin, `docs.urchin.ws`) so a player flagged by *both* sources surfaces as a high-confidence cheater, while single-source flags are still visible but visually distinct.

**Why:** Seraph has false positives and false negatives — so does Urchin. Neither alone is authoritative; the overlap is. Urchin also uses *Cubelify-formatted* tag objects (color/textColor/tag_name/text/tooltip), so we can reuse the existing `SeraphData.SeraphTag` parser without building a second tag schema.

**Endpoint:** `GET https://urchin.ws/cubelify?id=<uuid>&key=<apikey>&name=<ign>&sources=GAME,MANUAL`
  - Returns `{ score: {value, mode}, tags: [{color, textColor, tag_name, text, tooltip, ...}] }` — Cubelify-compatible, parser parity with Seraph.
  - Auth is via **query parameter `key=<apikey>`**, not a header — important: URL must never appear verbatim in `agent.log`. Log with the key masked (`key=****`).
  - `sources` is a required comma-separated enum of `GAME,PARTY,PARTY_INVITES,CHAT,CHAT_MENTIONS,MANUAL,ME`. V1 sends `GAME,MANUAL` (the two we can justify from Aurex's context); a future config knob could expose it.
  - Also takes `id` (UUID) and `name` (current IGN) — we always have both by the time we fetch.
  - Response includes a top-level `rate_limit` field — read it and feed back into the client-side limiter in a future iteration; V1 uses a conservative 60 req/min default.

**Alternative endpoints (not used V1):**
  - `GET /player/{username}` — simpler response shape (`{uuid, tags, rate_limit}`), but `/cubelify` is shape-compatible with the existing SeraphTag parser so we pick that.
  - `POST /players` (batch) — optimization for the "10 players in a lobby" case; skip V1 since per-UUID cache dedup already collapses concurrent asks.
  - `wss://urchin.ws/snipers` — real-time sniper tag WebSocket. Cool but orthogonal to the lookup path; separate milestone if we ever want live alerts.

**New files:**
- `agent/src/main/java/com/aurex/agent/api/UrchinClient.java` — mirrors `SeraphClient`. One HTTP call per `fetch(UUID, String ign)`. 401/403 → typed `UrchinAuthException`; 404 → empty `UrchinData`. Uses `RateLimiter.defaultUrchin()` (new factory, 60/min for now). Masks the API key in all log lines.
- `agent/src/main/java/com/aurex/agent/api/UrchinCache.java` — mirrors `SeraphCache`. 1h TTL, sticky `authFailed()` flag, `get` / `peekFuture` / `invalidate`.
- `agent/src/main/java/com/aurex/agent/api/UrchinData.java` — mirrors `SeraphData`. Reuses `SeraphData.SeraphTag` (don't duplicate the class — the tag shape is Cubelify-generic, not source-specific). Precomputes `hasCheaterTag` / `hasBotTag` using the same `CHEATER_TAGS` / `BOT_TAGS` substring sets so both sources classify consistently.
- `agent/src/main/java/com/aurex/agent/api/UrchinAuthException.java` — typed IOException for 401/403.

**Modified files:**
- `Config.java` — add `urchinApiKey` field, `RECOGNIZED_KEYS` entry, `writeUrchinApiKey(String)` static method (same preserve-other-fields pattern as `writeSeraphApiKey`). Register `COL_URCHIN = "urchin"`.
- `ModeConfig.java` — new `urchin` column with header "Urchin" and a placeholder color ladder (colors come from Urchin's response, so the ladder is self-documenting only, same pattern as `BW_TAG`).
- `AgentImpl.java`:
  - New static `UrchinCache urchinCache` alongside `seraphCache`. Init in `initApiPipeline` off `cfg.urchinApiKey`.
  - Extend `RawRow` with `UrchinData urchinData` (nullable) — same nullable-when-disabled pattern as `seraphData`.
  - `peekOrFetchUrchin` parallel to `peekOrFetchSeraph`; both fire in parallel with Hypixel in `preWarmFetches` and in the render-path `buildRawRow`.
  - `formatUrchin(RawRow)` — renders first tag the same way `formatTag` does, using colors from the Urchin response.
  - Alert sets: `alertedUrchinCheaters`, `alertedUrchinBots`. Cleared in the same `clearNickAlerts` sweep (rename consideration: `clearSessionAlerts` — or just keep the existing name, it's already the single catchall).
  - **Cross-source corroboration alert:** new `alertedDoubleFlags` set. When both `seraphData.hasCheaterTag` AND `urchinData.hasCheaterTag` are true for the same UUID, fire ONE promoted line: `§4§l[AX] -> <name> FLAGGED by Seraph + Urchin§r§c: <seraph reason> / <urchin reason>`. This replaces the two separate single-source alerts for that player (dedup across the three sets by preferring the double-flag when applicable).
  - `sendSeraphLine` → generalize into `sendSourceLine(String prefix, ... tags, ClassLoader)` so both `seraph:` and `urchin:` lines reuse the hover-tooltip path; or just clone it.
  - `maybeFireUrchinAuthWarning` parallel to `maybeFireSeraphAuthWarning`. One-shot red warning pointing at `AX-urchin <key>`.
- `Agent.java`:
  - `AX-urchin <key>` chat command (mirror `AX-seraph`). Validates non-empty, persists via `Config.writeUrchinApiKey`, hot-swaps `urchinCache`, clears auth-warning latch, log scrubs the key body.
  - `AX-status` readout gains `urchin=on/off`.
  - `AX-help` gains a line for `AX-urchin <key>`.
  - `AX-check <name>` dumps a new `urchin:` line with the same hover-tooltip treatment Seraph gets.
- `CLAUDE.md` — add `urchinApiKey` to the global-config list, `AX-urchin` + `AX-check`'s new urchin leg to the commands list.

**Unchanged by design:**
- `SeraphClient` / `SeraphCache` / `SeraphData` — parallel copies, not abstracted. Two API integrations with diverging auth shapes (Seraph header, Urchin query param) and diverging error semantics don't justify a base class yet. Revisit if a third blacklist lands.
- `TabRenderer`, `ColorTier` — no changes; `urchin` is just another cell string.
- `SeraphTag` — reused as-is for Urchin's tags (Cubelify format is source-agnostic). If Urchin's tag shape turns out to diverge, rename `SeraphTag` → `CubelifyTag` in its own commit.

**Rendering & alerts:**
- `tag` column stays Seraph-only (preserves M15 behavior, minimum risk).
- `urchin` column is additive — users opt in by adding `"urchin"` to `modes/<mode>.json > columns`.
- Single-source alerts fire independently and look the same as today's Seraph alerts (just with `[Urchin]` prefix on the reason).
- Double-source corroboration alert is the *one* new alert shape and the whole point of the milestone — fires bold red when both sources agree.

**Security:**
- Urchin puts the API key in the query string. Any log line that would otherwise include the URL must strip / mask the `key=` parameter first. Centralize in `UrchinClient.maskKeyForLog(String url)`.
- `AX-urchin <key>` body is scrubbed before being written to `agent.log` — same pattern as `AX-seraph`.

**Verification:**
1. Build: `./gradlew :agent:build`. No new warnings.
2. Config: delete `%APPDATA%\Aurex\config.json`, relaunch, confirm `urchinApiKey: ""` regenerates in default.
3. Empty-key path: tab renders exactly as pre-M17.
4. Key path: `AX-urchin <key>`, add `"urchin"` to `modes/bedwars.json > columns`, `AX-mode bedwars` to hot-reload, queue Bedwars, confirm urchin column populates and `agent.log` shows `urchin: <uuid> -> tags=N (Nms)` per player.
5. Bad key: one red `API key rejected` line, no 403 spam.
6. Double-flag: `AX-check` a UUID known to both blacklists — expect the bold promoted alert and no duplicate single-source alerts for that player.
7. Key masking: grep `agent.log` for the raw key string — zero hits.
8. `AX-urchin` hot-swap: revoke key, see warning, run `AX-urchin <newkey>`, next lookup works without restart.

**Out of scope / deferred:**
- WebSocket `/snipers` live feed.
- Historical stats endpoint (admin-gated).
- Batch `/players` endpoint (use single-UUID lookups; cache dedup already collapses bursts).
- Using Urchin's `score.value` / `score.mode` fields — interesting for sort-by-suspiciousness UX, but V1 treats them as opaque.
- Unifying `SeraphClient` / `UrchinClient` behind a shared interface. Revisit if a third blacklist API shows up.
- Per-source column merging (single `threats` column with mixed Seraph+Urchin entries) — easy later if users prefer one column over two.

**Success check:** In a Bedwars lobby with a known double-flagged cheater, both `tag` and `urchin` cells populate with independent reasons, the bold double-flag alert fires once, and `AX-check <name>` prints both `seraph:` and `urchin:` lines with hover tooltips.

---

## M18 — Friend-group whitelist (UUID gate)

**Goal:** Keep Aurex inside the intended friend group without a per-user licensing rig. On startup the agent fetches a UUID whitelist from a URL the owner controls; accounts not on the list fall into a dormant mode (no tab hook, no chat commands, no network traffic beyond the whitelist fetch itself).

**Why now (before the installer):** if we ship an installer first, revocation becomes awkward — friends who shouldn't have it anymore keep a working copy. Building the gate first means the installer's first real action is "check whether you're allowed," and revocation is just "edit the JSON."

**Threat model — explicit:** the goal is "stop casual resharing," NOT "stop a determined reverser." The repo is public-ish (or at least we're writing under that assumption) and rebuilding from source bypasses this completely. That's fine. If someone spends an afternoon patching the jar, they were going to get it anyway.

**Deliverable:**
- Owner hosts a JSON file at a stable URL (GitHub Gist raw URL is the path of least resistance). Shape: `{"allowed":["<mojang-uuid>", ...], "revoked":["<uuid>"]}`. Undashed hex, lowercase. `revoked` is an optional explicit-deny list so "remove from allowed" and "actively revoked" aren't the same state (useful for "I'll let you back in later" moments).
- Whitelist URL is a build-time constant baked into `agent/src/main/java/com/aurex/agent/access/Whitelist.java`. Change it = rebuild. That's intentional — the URL is the trust root.
- **Check runs on `onServerJoin`, not premain.** Lunar lets users switch Minecraft accounts mid-session without a relaunch — a startup-only check would miss a post-launch account swap and keep the agent active on an unauthorized UUID. By gating at world-join we re-read the current UUID every time the user enters a world (lobby, sub-server hop, reconnect) and can flip dormant on/off as the active account changes. Cost: nothing — `onServerJoin` already exists (M10/M14) and runs off the render thread.
- `Whitelist.check(UUID currentPlayerUuid)` called from the top of `AgentImpl.onServerJoin`, before the existing config-reload path:
  1. Reads the current UUID from `Minecraft.thePlayer.getUniqueID()` (reflective; we're on bootstrap). This is the authoritative source — it's the UUID Hypixel actually sees.
  2. If verdict for this UUID is already cached in-memory for this session, use it — no refetch on every lobby hop.
  3. Otherwise fetch whitelist JSON (5s connect / 10s read). On success, updates both in-memory and `%APPDATA%\Aurex\whitelist-cache.json` with fetch timestamp.
  4. On network failure, falls back to the cached file if <7 days old; older than that → treated as "no verdict"; see below.
  5. Verdict logic: UUID in `revoked` → **deny**. UUID in `allowed` → **allow**. Neither → **deny** (default-deny; "no verdict" above also becomes deny).
- **Dormant flag is UUID-scoped and re-evaluated each world-join** — so the user who launches on an authorized main and switches to an unauthorized alt gets shut down on the next world-join (lobby re-enter = <1s in practice, since Lunar re-joins the hub). Switching back flips it back on without a relaunch.
- **Deny path:** agent logs `access: not whitelisted for uuid=<short>`, sends one red chat line on world-join (`§c[AX] This build is not authorized for your account — contact the owner`), and sets the dormant flag. All subsequent hooks (tab render, chat command parser, incoming-chat auto-arm) check the flag at their entrypoint and no-op. **The jar stays loaded** — we can't `System.exit` from inside Lunar's JVM without taking Lunar down — we just render nothing. One deny-line per UUID per session to avoid spam on lobby hops.
- **Race at world-load:** the first world-join fires before `Minecraft.thePlayer` is fully populated in some edge cases (reconnect-during-disconnect). If `thePlayer` is null or its UUID is zero, treat as "unknown → dormant but silent" (no chat line, no log spam) and retry on the next world-join tick. Should self-correct within one lobby hop.
- **Alt accounts** require separate enrollment: each alt's UUID DM'd to the owner and added to `allowed`. Documented in the install README.
- **Cache staleness policy:** <1 day old → silent use, no network retry. 1–7 days → use, plus one background refresh attempt per session. >7 days → treat as no-verdict (deny) and retry the fetch on each world-join until it succeeds.
- **Manual refresh:** new `AX-whitelist-refresh` chat command forces a fresh fetch + re-evaluate. Useful when the owner just added you and you don't want to wait for the cache to expire or re-lobby.
- `agent.log` breadcrumbs: one line per verdict change — `access: uuid=<short> verdict=<allow|deny> source=<network|cache|cache-stale>`. Same UUID on repeat joins is silent. Raw UUID in logs, not in chat.
- **No new UI inside Aurex** beyond the one deny chat line + the refresh command. No self-service — owner is the only gatekeeper.

**Out of scope / deferred:**
- Code signing / real license server.
- Encrypted jar payload / obfuscation.
- Self-service enrollment (the owner is the only gatekeeper).
- Any in-game UX beyond the one red line on deny.

**Success check:** launch Lunar on an account NOT on the whitelist, join a lobby → agent logs deny, red chat line appears once, tab is vanilla, AX-* commands no-op. Launch on an account ON the whitelist → unchanged from today. **Mid-session account switch test:** launch on allowed account → switch to unauthorized account in Lunar → join a lobby → deny fires on this world-join. Switch back → next world-join re-enables. Remove a UUID from the gist, run `AX-whitelist-refresh` on that account → deny fires within the same session without a relaunch.

---

## M19 — PowerShell installer

**Goal:** One-liner install for friends who don't want to touch Gradle, Java paths, or Lunar's settings JSON. The installer runs on Windows only (Lunar support target is Windows per CLAUDE.md) and does the setup that's currently a per-user README dance.

**Deliverable — `install.ps1` shipped alongside the release jar:**
1. **Pre-flight checks:**
   - Windows only; bails with a friendly error otherwise.
   - Detects Lunar at `%LOCALAPPDATA%\Programs\Lunar Client\` (or the per-user registry key Lunar sets on install). Bails with install instructions if missing.
   - Reads Lunar's accounts file (`%USERPROFILE%\.lunarclient\settings\game\accounts.json` — path confirmed at M18 implementation time) and checks every profile's UUID against the M18 whitelist URL. **Advisory, not blocking** — the gate itself runs at world-join (M18), so we don't need the installer to enforce. But we DO want to print: "your UUIDs: `<main>`, `<alt1>`, …; of these, `<main>` is authorized, `<alt1>` is not. DM the owner the UUIDs you want enrolled." Means users know exactly what to send instead of guessing or copy-pasting from Mojang.
2. **Jar deployment:**
   - Copies the jar from next to the script to `%APPDATA%\Aurex\aurex-agent.jar`. Overwrites cleanly on reinstall/update.
3. **Lunar JVM-args patch:**
   - Lunar stores user JVM args in a settings JSON under `%USERPROFILE%\.lunarclient\settings\` (exact file name needs confirming at implementation time — candidates include `settings.json`, `game/<version>.json`, or a top-level account file). Script reads the existing JSON, appends the `-javaagent:` line to whatever JVM-args field Lunar uses (string or array, depending on schema), preserving all other args. Deduplicates: if an existing `-javaagent:<anything>\aurex-agent.jar` is present, replaces it with the new path rather than appending a second one.
   - Writes the file back atomically (temp file + rename) so a crash mid-write doesn't brick Lunar's settings.
4. **Config scaffolding:**
   - Creates `%APPDATA%\Aurex\` if missing.
   - Prompts (with defaults) for `apiKey` (Hypixel) and `seraphApiKey`. Either can be skipped with Enter — sets that key to empty string; the agent runs fine without Seraph, and the user can set the Hypixel key later via `AX-hypixel <key>`.
   - Writes `config.json` with the keys, default `activeMode=bedwars`, and the rest of the defaults the agent would generate on first launch anyway.
   - Skips if `config.json` already exists — don't overwrite an existing install's keys.
5. **Completion output:**
   - Prints: installed jar path, patched settings file path, next steps ("launch Lunar 1.8.9, open tab in a Bedwars lobby"). Also prints the uninstall command.

**Uninstall mode — `install.ps1 -Uninstall`:** removes the `-javaagent:` line from Lunar's settings JSON, deletes `%APPDATA%\Aurex\aurex-agent.jar`. Leaves `config.json` and `modes/` in place (user may want to preserve their keys/setup across reinstalls; a `-Purge` flag removes those too).

**Release mechanics (out of scope but worth noting):**
- GitHub Release bundles `install.ps1` + `aurex-agent.jar`.
- One-line install becomes `irm <release-url>/install.ps1 | iex` once we're comfortable with the security posture (arbitrary remote-execute-from-shell). For 0.1-share it's probably "download the release zip, right-click install.ps1 → Run with PowerShell."

**Out of scope / deferred:**
- Mac / Linux installers.
- Auto-update — the jar doesn't check for new releases. Owner DMs "rerun the installer" when there's a new build.
- GUI installer.
- Registry uninstall entry (Add/Remove Programs).

**Success check:** fresh Windows user with Lunar already installed, never touched Aurex, runs `install.ps1`, provides their Hypixel key at the prompt, closes PowerShell, launches Lunar 1.8.9, queues Bedwars, opens tab → sees the Aurex stats table. `install.ps1 -Uninstall` cleanly removes the `-javaagent:` line and the jar; Lunar launches in vanilla mode next time.

---

## M20 — SkyWars + Duels modes (with per-variant Duels split)

**Goal:** Extend the column catalog past Bedwars so friends who queue SkyWars or Duels see stats. Duels splits per game variant (UHC / SW / Classic / Bow / etc.) because thresholds and relevant columns diverge sharply between variants — a 5.0 UHC duels WLR is absurd, a 5.0 classic WLR is Tuesday.

**Why this slot:** the mode-config system (M11/M12/M13) was built to make this additive. Every new mode is a new `<mode>.json` + POJO + parser + `formatCell` branches, no architectural changes. Landing it after the install gate + installer means a new friend's first experience covers all the modes they actually play, not just Bedwars.

**Mode detection stays manual.** Users run `AX-mode skywars` before queueing SkyWars. Locraw-based auto-detection is still post-V1 territory — interesting but a separate project.

**Deliverable — SkyWars:**
- New `SkyWarsStats` POJO + `HypixelClient` parse path for the `player.stats.SkyWars` subtree. Fields: `level` (derived from `skywars_experience`), `kills`, `deaths`, `kdr`, `wins`, `losses`, `wlr`, `winstreak` (top-level or per-mode?), optional `souls_gathered`.
- `Config.COL_SW_LEVEL`, `COL_SW_KDR`, `COL_SW_WLR`, `COL_SW_WINS`, `COL_SW_KILLS` (plus any others we settle on at implementation time).
- `ModeConfig.MODE_SKYWARS = "skywars"` + `MODES` entry with default `columns`, `colors` (tier ladders per column), `headers`, `alerts` thresholds. Ships as an auto-generated `modes/skywars.json` on first `AX-mode skywars`.
- `AgentImpl.formatCell` branches for each new column, plus the `getTabData` / `buildRawRow` paths: the existing `BedwarsStats` reference in `RawRow` generalizes to a `ModeStats` interface or a pair of nullable fields (`BedwarsStats` + `SkyWarsStats`); pick whichever is less invasive at implementation time.

**Deliverable — Duels:**
- `DuelsStats` POJO parses the `player.stats.Duels` subtree. That subtree is one blob per variant — `classic_duel_wins`, `classic_duel_kills`, `uhc_duel_wins`, `uhc_duel_kills`, `sw_duel_wins`, etc. — so one parser populates nested maps keyed by variant.
- Variants targeted for 0.1: **classic, uhc, sw (SkyWars duel), bow, sumo.** Blitz / MegaWalls / OP / boxing added later if anyone plays them.
- One mode file per variant: `modes/classic_duel.json`, `modes/uhc_duel.json`, `modes/sw_duel.json`, `modes/bow_duel.json`, `modes/sumo_duel.json`. Each references only the columns relevant to that variant (UHC duels care about HP / final-round damage; bow duels care about accuracy; sumo cares about… basically just WLR and kills).
- Shared base stats across all Duels variants: overall `coins`, `current_winstreak`, `best_winstreak`, `games_played`. These are `player.stats.Duels` top-level, not per-variant.
- `AX-mode uhc_duel` / `AX-mode classic_duel` / etc. as the switching UX. Names are long-ish; that's intentional — `AX-mode uhc` would be ambiguous if we ever add UHC the main mode.

**Shared infrastructure changes:**
- `Config.VALID_COLUMNS` expands with the new SW_* + DUELS_* columns.
- `AgentImpl.formatHypixelLines` (AX-check) needs a mode-aware branch — "hypixel: no Bedwars data" is wrong when the user is checking a SkyWars-only player.
- Threat report (M14) becomes per-mode — uhc_duel threshold ≠ bedwars threshold. `ModeConfig.alerts` already supports this.

**Out of scope / deferred:**
- Auto-mode-detection via locraw/scoreboard.
- Blitz, MegaWalls, UHC (main), Arena, TNT Games, Cops & Crims, Arcade.
- Per-map Duels stats (some variants track per-map winrates — ignore).
- Live-game scoreboard integration (kills this round, etc.) — separate project.

**Success check:** friend who plays SkyWars runs `AX-mode skywars`, queues, opens tab → sees level / KDR / WLR / wins / kills with the right color tiers. Friend who plays UHC duels runs `AX-mode uhc_duel`, queues → sees UHC-specific columns. `AX-check <name>` renders the block for whichever mode is active.

---

## Post-V1 (not scheduled)

- Game-mode detection (read Hypixel locraw / scoreboard → know if we're in Bedwars vs SkyWars vs lobby)
- Additional modes beyond M20's scope (Blitz, MegaWalls, UHC, Arena, Arcade, etc.)
- GUI config screen (vs JSON file)
- Auto-update check
- Live in-game scoreboard integration (round-by-round kills, damage, etc.)

---

## How we work

- **One milestone at a time.** No jumping ahead. Each milestone gets its own commits.
- **If a milestone turns out wrong, we re-plan before proceeding.** POC phase especially — if M1 or M2 reveals that Lunar blocks attachment, the whole architecture may shift.
- **User tests in-game after each milestone.** Nothing counts as done until the user has seen it work in Lunar themselves.
