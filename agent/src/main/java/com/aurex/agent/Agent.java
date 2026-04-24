package com.aurex.agent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.jar.JarFile;

/**
 * Aurex Java Agent entry points.
 *
 * When the loader calls vm.loadAgent("aurex-agent.jar") on Lunar's running
 * JVM, Lunar's JVM reads this jar's manifest, sees Agent-Class pointing here,
 * and invokes {@link #agentmain(String, Instrumentation)} — synchronously,
 * on a special JVM-internal thread.
 *
 * Whatever we do here runs with the same privileges as Lunar itself, inside
 * Lunar's JVM. From M2 onward we'll register ClassFileTransformers on the
 * Instrumentation handle; for M1 we just prove we got here.
 *
 * Keep these methods SHORT. vm.loadAgent blocks until agentmain returns —
 * a slow or stuck agentmain hangs the loader too.
 */
public final class Agent {

    /** Marker line prefix for everything we write. Makes grep easy. */
    private static final String TAG = "Aurex";

    /**
     * Standard prefix for every Aurex chat line. {@code §5} dark purple
     * brackets, {@code §l§d} bold pink {@code AX} inside, trailing {@code -> }
     * arrow. Callers append their own §-colored content after this — trailing
     * space already included.
     *
     * <p>Published on every classloader copy of Agent (app / bootstrap / MC)
     * so {@link AgentImpl} and the MC-loader tasks ({@link DisarmTask},
     * {@link ThreatReportTask}, etc.) can reference it directly.
     */
    public static final String PREFIX = "§5[§l§dAX§r§5] -> ";

    private Agent() {}

    /** Runtime attach — our primary path (loader calls loadAgent). */
    public static void agentmain(String args, Instrumentation inst) {
        log("hello from inside Lunar (agentmain)");
    }

    /**
     * Startup entry point. Runs in the **app** (system) classloader, because
     * that's where the JVM's -javaagent machinery lives.
     *
     * Two-stage init:
     * 1. Publish our jar to the bootstrap classloader so MC classes can resolve
     *    {@code com.aurex.agent.Agent} at INVOKESTATIC time (Lunar's MC loader
     *    doesn't delegate to app; it does delegate to bootstrap).
     * 2. Reflectively re-enter via {@link #start(Instrumentation)} loaded from
     *    bootstrap. Reason: once our jar is on bootstrap, any class reference
     *    in Agent/app (ASM, our transformer) exists in BOTH app and bootstrap,
     *    and the JVM throws a loader-constraint violation when the mismatched
     *    copies meet at a method signature. Verified 2026-04-20: calling
     *    {@code ClassReader.accept(ClassVisitor, int)} from app's transformer
     *    fails because ASM got resolved via bootstrap mid-call. Running the
     *    real setup code from bootstrap-loaded Agent keeps every reference
     *    (transformer, ASM, Agent itself) on a single consistent classloader.
     */
    public static void premain(String args, Instrumentation inst) {
        truncateLog();
        log("hello from inside Lunar (premain)");
        try {
            File self = new File(Agent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(self));
            log("appended to bootstrap classloader: " + self);

            // ClassLoader=null means bootstrap. This loads a second copy of
            // Agent, distinct from the one running this very method, and
            // invokes start() on the bootstrap copy.
            Class.forName("com.aurex.agent.Agent", true, null)
                    .getMethod("start", Instrumentation.class)
                    .invoke(null, inst);
        } catch (Throwable t) {
            log("premain init FAILED: " + t);
        }
    }

    /**
     * Second-stage init. Invoked reflectively from {@link #premain} after the
     * agent jar has been published to bootstrap. THIS method runs inside
     * bootstrap-loaded Agent, so everything it touches — TabOverlayTransformer,
     * ASM — also resolves via bootstrap, keeping the entire hook path on a
     * single classloader.
     */
    public static void start(Instrumentation inst) {
        log("Agent.start (bootstrap classloader)");
        openJavaLangForReflection(inst);
        preloadImpl();
        inst.addTransformer(new TabOverlayTransformer());
        inst.addTransformer(new ChatCommandTransformer());
        inst.addTransformer(new JoinGameTransformer());
        inst.addTransformer(new IncomingChatTransformer());
    }

    /**
     * Trigger {@link AgentImpl}'s static initializer now (on bootstrap), so the
     * API-key load + {@link com.aurex.agent.api.HypixelClient} construction happen
     * at premain time — not on the render thread the first time a tab opens.
     *
     * <p>We keep AgentImpl out of this class's imports on purpose (see
     * {@link AgentImpl}'s javadoc): Agent is {@code defineClass}'d into Lunar's
     * MC loader, which can't see {@code com.aurex.agent.api.*}. Class.forName
     * by name is loader-safe — the MC copy of Agent never actually links to
     * AgentImpl's bytecode, it just reflects into the bootstrap copy at call time.
     */
    private static void preloadImpl() {
        try {
            Class.forName("com.aurex.agent.AgentImpl", true, null);
        } catch (Throwable t) {
            log("preloadImpl failed: " + t);
        }
    }

    /**
     * Open {@code java.lang} from module {@code java.base} to our own module
     * so we can later reflect on {@code ClassLoader.defineClass} without
     * hitting {@code InaccessibleObjectException} on Java 9+.
     *
     * Why we need it: Lunar's MC classloader (IchorPipeline) doesn't
     * delegate to bootstrap for our package. A bootstrap-only {@code Agent}
     * is therefore invisible to patched MC bytecode at INVOKESTATIC time.
     * The workaround (see {@link TabOverlayTransformer#publishAgentInto})
     * is to call {@code defineClass} directly on Lunar's MC classloader —
     * which requires reflective access to a java.base internal method.
     *
     * Called reflectively because {@code Instrumentation.redefineModule}
     * is a Java 9+ API and we compile to Java 8 bytecode.
     */
    private static void openJavaLangForReflection(Instrumentation inst) {
        try {
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Method getModule = Class.class.getMethod("getModule");
            Object javaBase = getModule.invoke(ClassLoader.class);
            Object ourModule = getModule.invoke(Agent.class);

            Set<Object> ourSet = new HashSet<Object>();
            ourSet.add(ourModule);
            Map<String, Set<Object>> opens = new HashMap<String, Set<Object>>();
            opens.put("java.lang", ourSet);

            Method redefineModule = Instrumentation.class.getMethod("redefineModule",
                    moduleClass, Set.class, Map.class, Map.class, Set.class, Map.class);
            redefineModule.invoke(inst,
                    javaBase,
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    opens,
                    Collections.emptySet(),
                    Collections.emptyMap());
            log("opened java.lang to our module");
        } catch (Throwable t) {
            // Non-fatal: on Java 8 the reflective calls aren't needed, so
            // missing Module / redefineModule isn't a problem.
            log("openJavaLangForReflection failed (may be OK on JVM 8): " + t);
        }
    }

    /**
     * Called from inside GuiPlayerTabOverlay#renderPlayerlist (via ASM-injected
     * INVOKESTATIC at method entry). Throttled to once per second so the log
     * doesn't explode at 60+ fps.
     *
     * MUST stay ()V — the injected call passes no args and expects no return.
     * Also must never throw: an exception here would propagate out of the
     * render method and crash Lunar.
     */
    private static volatile long lastTabLog;

    public static void onTabRender() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastTabLog >= 1000L) {
                lastTabLog = now;
                log("tab rendered");
            }
        } catch (Throwable ignored) {
            // Swallow everything — this runs on Lunar's render thread.
        }
    }

    /**
     * Full-tab-replacement gate. Called from the HEAD of
     * {@code GuiPlayerTabOverlay#renderPlayerlist} immediately after
     * {@link #onTabRender()}. Return {@code true} to tell the injected bytecode
     * to {@code RETURN} — vanilla's body is skipped and we've drawn our own
     * tab. Return {@code false} to fall through to the vanilla renderer.
     *
     * <p>Gate order: if {@link #displayEnabled} is off we always return false
     * so AX-off users see unmodified vanilla tab. When on we hand off to
     * {@link TabRenderer#render(int)} which owns the actual drawing.
     *
     * <p>Must never throw — on error we return false so the user at least
     * keeps vanilla tab instead of losing it entirely to a crash.
     */
    public static boolean renderAurexTab(int width) {
        try {
            // M18: whitelist deny → fall through to vanilla tab.
            if (isWhitelistDormant()) return false;
            if (!displayEnabled) return false;
            return TabRenderer.render(width);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- M3.5: arm/disarm gate ------------------------------------------------

    /**
     * Persistent display gate. AX-on turns it on, AX-off turns it off — no
     * auto-disarm. Any M4+ feature that mutates what the user sees (tab, HUD,
     * chat injections) must check this before touching rendering.
     *
     * Separate from {@link #fetchArmed} so the 3s safety window only limits
     * outbound API traffic; once data is in the cache, it stays visible until
     * the user explicitly types AX-off.
     */
    static volatile boolean displayEnabled;

    /**
     * Fetch-window gate. AX-on flips this to true for {@link #ARM_MS}, then
     * {@link DisarmTask} clears it. Gates outbound network calls (Hypixel API
     * from M5 onward) so we can't accidentally spray requests by leaving the
     * overlay on.
     */
    static volatile boolean fetchArmed;

    /**
     * Epoch millis at which the currently-scheduled fetch-window close will
     * fire. Used as a "generation token": {@link DisarmTask} captures this
     * value at schedule time and no-ops if it doesn't match when the timer
     * fires. A second AX-on simply bumps {@code disarmAt}, scheduling a new
     * task, and the stale task sees the mismatch and returns.
     */
    static volatile long disarmAt;

    /** Single background Timer for all scheduled tasks. Daemon = doesn't block JVM shutdown. */
    private static final Timer timer = new Timer("Aurex-Timer", true);

    /** How long AX-on keeps the fetch window open before auto-closing. */
    private static final long ARM_MS = 3000L;

    /**
     * How long the game-start auto-arm keeps the fetch window open. Matches
     * {@link #ARM_MS} (manual AX-on) for consistency. Starts ~2s after the
     * final countdown tick — not at the countdown itself — so Hypixel's
     * name-masking in the pre-game lobby doesn't pollute the fetch.
     */
    private static final long AUTO_ARM_MS = 3000L;

    /**
     * Delay between "countdown reaches 0" and the auto-arm actually firing.
     * Hypixel masks player names during the pre-game lobby ("Player",
     * "ClassAssignment", etc.) — fetching them at countdown time would just
     * stash garbage. Waiting 2s past T=0 gives Bungee time to hand off to the
     * match sub-server and for the client to receive the real roster via
     * {@code S38PacketPlayerListItem}.
     */
    private static final long POST_START_DELAY_MS = 2000L;

    /**
     * How long after {@link #autoArmNow()} we wait before running the M14
     * threat report. The auto-arm kicks off fetches asynchronously; this delay
     * gives {@link com.aurex.agent.api.StatsCache} time to settle before we
     * snapshot it. Slightly longer than {@link #AUTO_ARM_MS} so in-flight
     * requests have a full fetch window + 1s to land.
     */
    static final long THREAT_REPORT_DELAY_MS = 4000L;

    /**
     * Generation token for the pending {@link AutoArmTask}. Countdown ticks
     * bump this at reschedule time; the scheduled task captures it, and
     * no-ops at fire time if it's been superseded. Mirrors the {@code disarmAt}
     * pattern {@link DisarmTask} uses.
     */
    static volatile long pendingAutoArmToken;

    /**
     * Minimum gap between consecutive auto-arms. Below this, duplicate chat
     * packets (network echo, Hypixel's own replay, etc.) can't double-fire the
     * arm. Above this, each real countdown tick (~1000ms apart) can re-arm.
     */
    private static final long AUTO_ARM_DEDUP_MS = 500L;

    /** Wall-clock of the last auto-arm fire; used only for the dedup check. */
    private static volatile long lastAutoArmMs;

    /**
     * Called from the HEAD of {@code EntityPlayerSP#sendChatMessage(String)}.
     * Return {@code true} to swallow the outgoing packet (our commands), {@code
     * false} to let it go to the server (normal chat).
     *
     * Must never throw — an exception here propagates out of the client's chat
     * send path. On error we return false so chat still works.
     */
    public static boolean onOutgoingChat(String message) {
        try {
            if (message == null) return false;
            String trimmed = message.trim();

            // M18: AX-whitelist-refresh is the ONE command that works even
            // when the agent is dormant — it's the recovery path for a user
            // who just got their UUID added to the allow-list and doesn't
            // want to relaunch Lunar. Swallow before the dormant gate.
            if (trimmed.equalsIgnoreCase("AX-whitelist-refresh")) {
                onAxWhitelistRefresh();
                return true;
            }

            // M18: dormant → let everything through to the server untouched.
            // Don't swallow AX typos; user might have deliberately dropped back
            // to vanilla chat and we don't want Aurex still eating their input.
            if (isWhitelistDormant()) return false;

            if (trimmed.equalsIgnoreCase("AX-on")) {
                arm();
                return true;
            }
            if (trimmed.equalsIgnoreCase("AX-off")) {
                disarm(true);
                return true;
            }
            if (trimmed.equalsIgnoreCase("AX-status")) {
                sendClientChat(PREFIX + "§edisplay=" + (displayEnabled ? "on" : "off")
                        + " fetch=" + (fetchArmed ? "armed" : "idle")
                        + " hypixel=" + (isHypixelEnabled() ? "on" : "off")
                        + " seraph=" + (isSeraphEnabled() ? "on" : "off")
                        + " urchin=" + (isUrchinEnabled() ? "on" : "off"));
                return true;
            }
            if (trimmed.equalsIgnoreCase("AX-help")) {
                sendHelp();
                return true;
            }
            // AX-hypixel <key> — rotate the Hypixel API key (M16). Bridged into
            // AgentImpl for the same reason as AX-seraph: SeraphCache/StatsCache
            // live on bootstrap. Handled BEFORE the typo guard; the prefix is
            // length 10.
            if (isAxHypixelCommand(trimmed)) {
                String rest = trimmed.length() > 10 ? trimmed.substring(10).trim() : "";
                onAxHypixel(rest);
                return true;
            }
            // AX-seraph <key> — rotate the Seraph API key. Bridged into AgentImpl
            // (where the SeraphCache lives) so the key never gets written to the
            // MC-loader copy of Agent. Must be handled BEFORE the typo guard and
            // before the AX-ignore prefix — "AX-seraph" is length 9, same as
            // "AX-ignore", and their 4th chars differ (s vs i) so no overlap.
            if (isAxSeraphCommand(trimmed)) {
                String rest = trimmed.length() > 9 ? trimmed.substring(9).trim() : "";
                onAxSeraph(rest);
                return true;
            }
            // AX-urchin <key> — rotate the Urchin API key (M17). Parallel to
            // AX-seraph. Length-9 prefix.
            if (isAxUrchinCommand(trimmed)) {
                String rest = trimmed.length() > 9 ? trimmed.substring(9).trim() : "";
                onAxUrchin(rest);
                return true;
            }
            // AX-check <name> — debug command: Mojang username -> UUID, then
            // dump Hypixel + Seraph results to client chat. Length-8 prefix.
            if (isAxCheckCommand(trimmed)) {
                String rest = trimmed.length() > 8 ? trimmed.substring(8).trim() : "";
                onAxCheck(rest);
                return true;
            }
            // AX-debugtab <name> — splice a synthetic tab row for 10s using
            // real Hypixel/Seraph/Urchin data for the named player. Length-11
            // prefix. Must be tested BEFORE the typo guard.
            if (isAxDebugTabCommand(trimmed)) {
                String rest = trimmed.length() > 11 ? trimmed.substring(11).trim() : "";
                onAxDebugTab(rest);
                return true;
            }
            // AX-mode [name] — list known modes, or switch active mode and hot-reload.
            // Handled BEFORE the typo guard so "AX-mode" alone (no arg) reaches the
            // list branch instead of being dismissed as unknown.
            if (isAxModeCommand(trimmed)) {
                String rest = trimmed.length() > 7 ? trimmed.substring(7).trim() : "";
                onAxMode(rest);
                return true;
            }
            // AX-removeignore is a longer prefix than AX-ignore — test it first so
            // "AX-removeignore Foo" doesn't match the AX-ignore branch with rest
            // = "removeignore Foo" (which would fail isValidUsername and confuse).
            if (isAxRemoveIgnoreCommand(trimmed)) {
                String rest = trimmed.length() > 15 ? trimmed.substring(15).trim() : "";
                onAxIgnoreBridge(rest, true);
                return true;
            }
            if (isAxIgnoreCommand(trimmed)) {
                String rest = trimmed.length() > 9 ? trimmed.substring(9).trim() : "";
                onAxIgnoreBridge(rest, false);
                return true;
            }
            // Typo guard: anything that LOOKS like a botched AX command —
            // starts with "ax" (case-insensitive), optional space, then dash or
            // underscore — gets swallowed and hinted instead of broadcasting
            // the typo to public chat. Catches "AX-onn", "ax-stats", "AX_on",
            // "ax -on", "AX _status", etc.
            if (looksLikeAxCommand(trimmed)) {
                sendClientChat(PREFIX + "§eunknown: \"" + trimmed
                        + "\" — try AX-on / AX-off / AX-status");
                log("swallowed unknown AX command: " + trimmed);
                return true;
            }
            return false;
        } catch (Throwable t) {
            log("onOutgoingChat failed: " + t);
            return false;
        }
    }

    /**
     * Called from the HEAD of {@code GuiNewChat#printChatMessage(IChatComponent)}
     * via {@link IncomingChatTransformer}. Observes every chat line that reaches
     * the client — pattern-matches Hypixel's countdown ("The game starts in N
     * seconds!") and auto-arms the fetch window so stats are cached before the
     * match begins.
     *
     * <p>Parameter is typed {@link Object} because {@link Agent} lives on the
     * MC classloader and must not compile-time reference
     * {@code net.minecraft.util.IChatComponent} (fine on MC loader but breaks
     * the bootstrap + app copies of this class — they don't see MC classes).
     * We reflect into {@code getUnformattedText()} at call time; any MC loader
     * servicing the call can resolve it.
     *
     * <p>Must never throw: runs on the client main thread on every chat packet
     * and for every {@code addChatMessage} call we ourselves make. An exception
     * here would break the entire chat pipeline. On error we silently bail.
     *
     * <p>Cheap-path first: plain {@code String.contains} filter before regex,
     * because 99.9% of chat lines aren't countdown lines and a failing regex on
     * every chat packet would waste CPU.
     */
    public static void onIncomingChat(Object chatComponent) {
        try {
            // M18: no auto-arm, no countdown matching, no side effects when dormant.
            if (isWhitelistDormant()) return;
            if (chatComponent == null) return;
            String text = extractChatText(chatComponent);
            if (text == null) return;
            int seconds = parseCountdownSeconds(text);
            if (seconds < 0) return;

            long now = System.currentTimeMillis();
            if (now - lastAutoArmMs < AUTO_ARM_DEDUP_MS) return;
            lastAutoArmMs = now;

            // Formula: delay = N*1000ms (time until countdown ends) +
            // POST_START_DELAY_MS (lag settle / Bungee handoff). Every tick
            // converges on the same wall-clock target — the generation-token
            // dance in AutoArmTask ensures only the latest fires.
            long delay = (long) seconds * 1000L + POST_START_DELAY_MS;
            long token = System.nanoTime();
            pendingAutoArmToken = token;
            timer.schedule(new AutoArmTask(token), delay);
            log("auto-arm scheduled: +" + delay + "ms (countdown " + seconds + "s) on: \"" + text + "\"");
        } catch (Throwable t) {
            // Deliberately no log — a broken chat pipeline log-spamming once
            // per message would fill the log fast. If this breaks, manual
            // AX-on still works.
        }
    }

    /**
     * Reflectively pull the unformatted text out of an {@code IChatComponent}.
     * We use {@code getUnformattedText()} (strips color/formatting) so the
     * regex doesn't have to deal with §-codes Hypixel may or may not inject.
     *
     * <p>Resolves the class via the component's own loader so we don't care
     * which classloader the caller handed us.
     */
    private static String extractChatText(Object chatComponent) {
        try {
            Method m = chatComponent.getClass().getMethod("getUnformattedText");
            Object result = m.invoke(chatComponent);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Parse Hypixel's Bedwars pre-game countdown line and return the number of
     * seconds remaining. Returns {@code -1} on no match.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "The game starts in 5 seconds!"} → {@code 5}</li>
     *   <li>{@code "The game starts in 1 second!"}  → {@code 1}</li>
     * </ul>
     *
     * <p>Done manually to avoid Pattern compilation per call — this runs on
     * every chat line so a tight loop over a short string is cheaper than
     * regex setup.
     */
    private static int parseCountdownSeconds(String text) {
        // Cheap-path: most chat lines don't contain "starts in".
        if (text.length() < 24 || text.indexOf("starts in") < 0) return -1;
        String s = text.trim();
        if (!s.startsWith("The game starts in ")) return -1;
        if (!s.endsWith("!")) return -1;
        // What's between "The game starts in " and "!" should be "N second(s)".
        String middle = s.substring(19, s.length() - 1).trim();
        int spaceIdx = middle.indexOf(' ');
        if (spaceIdx < 0) return -1;
        String numPart = middle.substring(0, spaceIdx);
        String unitPart = middle.substring(spaceIdx + 1);
        if (!unitPart.equals("second") && !unitPart.equals("seconds")) return -1;
        if (numPart.isEmpty()) return -1;
        int value = 0;
        for (int i = 0; i < numPart.length(); i++) {
            char c = numPart.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
            if (value > 60) return -1;  // sanity cap
        }
        return value;
    }

    /**
     * Shape-match a message against the "possibly-an-AX-command" pattern:
     * {@code ^[aA][xX] ?[-_]}. If true, the caller swallows the message and
     * prints a client-side hint — the goal is to keep typos like {@code ax_on}
     * or {@code AX -status} off public chat.
     */
    /**
     * Shape-match against {@code AX-mode}: a case-insensitive {@code "ax-mode"}
     * prefix, followed by either end-of-string or a single space. Tighter than
     * {@link #looksLikeAxCommand} so "AX-modex" doesn't accidentally route here.
     */
    private static boolean isAxModeCommand(String s) {
        return matchesAxPrefix(s, "ax-mode");
    }

    private static boolean isAxIgnoreCommand(String s) {
        return matchesAxPrefix(s, "ax-ignore");
    }

    private static boolean isAxRemoveIgnoreCommand(String s) {
        return matchesAxPrefix(s, "ax-removeignore");
    }

    private static boolean isAxSeraphCommand(String s) {
        return matchesAxPrefix(s, "ax-seraph");
    }

    private static boolean isAxUrchinCommand(String s) {
        return matchesAxPrefix(s, "ax-urchin");
    }

    private static boolean isAxHypixelCommand(String s) {
        return matchesAxPrefix(s, "ax-hypixel");
    }

    private static boolean isAxCheckCommand(String s) {
        return matchesAxPrefix(s, "ax-check");
    }

    private static boolean isAxDebugTabCommand(String s) {
        return matchesAxPrefix(s, "ax-debugtab");
    }

    /**
     * Dump the full AX-* command catalog into client chat. Runs entirely from
     * Agent (no AgentImpl bridge needed) since it's stateless text. Kept in
     * sync with the chat-commands section of CLAUDE.md by hand — small enough
     * that a file-driven loader would be overkill.
     */
    private static void sendHelp() {
        sendClientChat(PREFIX + "§acommands:");
        sendClientChat(PREFIX + "  §fAX-on §7/ §fAX-off §7/ §fAX-status §8— arm/disarm + status readout");
        sendClientChat(PREFIX + "  §fAX-help §8— this list");
        sendClientChat(PREFIX + "  §fAX-mode §7[§flist§7|§f<name>§7] §8— list modes or switch the active one");
        sendClientChat(PREFIX + "  §fAX-ignore <name> §7/ §fAX-removeignore <name> §8— manage threat-report ignore list");
        sendClientChat(PREFIX + "  §fAX-hypixel <key> §8— rotate Hypixel API key (hot-swap, no restart)");
        sendClientChat(PREFIX + "  §fAX-seraph <key> §8— rotate Seraph API key (hot-swap, no restart)");
        sendClientChat(PREFIX + "  §fAX-urchin <key> §8— rotate Urchin API key (hot-swap, no restart)");
        sendClientChat(PREFIX + "  §fAX-check <name> §8— debug: dump Hypixel + Seraph + Urchin for a player");
        sendClientChat(PREFIX + "  §fAX-debugtab <name> §8— splice a synthetic tab row for 10s (visual check)");
        sendClientChat(PREFIX + "  §fAX-whitelist-refresh §8— force-refetch the access whitelist and re-evaluate");
    }

    /**
     * Case-insensitive prefix match: {@code s} starts with {@code prefix} and is
     * either the same length or immediately followed by a space. Used by all
     * AX-{@code <word>} [arg] command dispatchers to ensure e.g. "AX-modex" doesn't
     * accidentally match "AX-mode".
     */
    private static boolean matchesAxPrefix(String s, String prefix) {
        int n = prefix.length();
        if (s.length() < n) return false;
        for (int i = 0; i < n; i++) {
            if (Character.toLowerCase(s.charAt(i)) != prefix.charAt(i)) return false;
        }
        return s.length() == n || s.charAt(n) == ' ';
    }

    /**
     * Hop into {@link AgentImpl#onAxMode(String)} — validate, write, hot-reload
     * the config, and announce in chat. Same classloader-bridge pattern as the
     * other Impl calls on this class.
     */
    private static void onAxMode(String rest) {
        try {
            Method m = implOnAxModeMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxMode", String.class);
                implOnAxModeMethod = m;
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxMode bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxModeMethod;

    /**
     * Bridge for {@code AX-ignore}/{@code AX-removeignore} into
     * {@link AgentImpl}. {@code removing=false} dispatches to
     * {@code AgentImpl.onAxIgnore}; {@code removing=true} to
     * {@code AgentImpl.onAxRemoveIgnore}. Same classloader-hop pattern as
     * {@link #onAxMode(String)} — reflective call into bootstrap so the MC
     * copy of Agent doesn't need a compile-time reference to AgentImpl.
     */
    private static void onAxIgnoreBridge(String rest, boolean removing) {
        try {
            Method m;
            if (removing) {
                m = implOnAxRemoveIgnoreMethod;
                if (m == null) {
                    m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                            .getMethod("onAxRemoveIgnore", String.class);
                    implOnAxRemoveIgnoreMethod = m;
                }
            } else {
                m = implOnAxIgnoreMethod;
                if (m == null) {
                    m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                            .getMethod("onAxIgnore", String.class);
                    implOnAxIgnoreMethod = m;
                }
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxIgnoreBridge(" + removing + ") failed: " + t);
        }
    }

    private static volatile Method implOnAxIgnoreMethod;
    private static volatile Method implOnAxRemoveIgnoreMethod;

    /**
     * Bridge for {@code AX-seraph <key>} into {@link AgentImpl}. Validates the
     * key argument isn't empty, persists via {@link com.aurex.agent.api.Config#writeSeraphApiKey},
     * reinitialises {@link AgentImpl}'s Seraph pipeline with the new key, and
     * confirms in chat. {@link AgentImpl#onAxSeraph(String)} log-scrubs the key
     * body so the raw value never lands in {@code agent.log}.
     */
    private static void onAxSeraph(String rest) {
        try {
            Method m = implOnAxSeraphMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxSeraph", String.class);
                implOnAxSeraphMethod = m;
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxSeraph bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxSeraphMethod;

    /**
     * Bridge for {@code AX-urchin <key>} into {@link AgentImpl}. Parallel to
     * {@link #onAxSeraph} — validates non-empty, persists via
     * {@link com.aurex.agent.api.Config#writeUrchinApiKey}, hot-swaps the
     * {@link com.aurex.agent.api.UrchinCache}. Log-scrubs the key body.
     */
    private static void onAxUrchin(String rest) {
        try {
            Method m = implOnAxUrchinMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxUrchin", String.class);
                implOnAxUrchinMethod = m;
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxUrchin bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxUrchinMethod;

    /**
     * Bridge for {@code AX-whitelist-refresh} into {@link AgentImpl} (M18).
     * Clears the session verdict cache and re-evaluates the current player's
     * UUID against a freshly-fetched snapshot — the in-game recovery path
     * when the owner just added you (or just revoked someone). The MC copy
     * of this class can't import {@link com.aurex.agent.access.Whitelist}
     * directly because IchorPipeline doesn't delegate for our packages, so
     * the bridge hops through bootstrap.
     */
    private static void onAxWhitelistRefresh() {
        try {
            Method m = implOnAxWhitelistRefreshMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxWhitelistRefresh");
                implOnAxWhitelistRefreshMethod = m;
            }
            m.invoke(null);
        } catch (Throwable t) {
            log("onAxWhitelistRefresh bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxWhitelistRefreshMethod;

    /**
     * Bridge for the M18 dormant flag. Hot-path — called from every
     * {@link #renderAurexTab}, {@link #onOutgoingChat}, {@link #onIncomingChat}
     * invocation, so the Method handle is cached in a volatile field after
     * first resolution (same pattern as every other bridge here).
     *
     * <p>Fails open on reflection error: returning {@code false} keeps Aurex
     * running normally if something is badly broken in bootstrap, because a
     * runtime bug in the gate is NOT the authoritative deny path — an actual
     * deny must come from {@link com.aurex.agent.access.Whitelist#check}
     * flipping the flag. Defense-in-depth: a broken reflection path locking
     * everyone out of their own tool would be worse than the missing gate.
     */
    private static boolean isWhitelistDormant() {
        try {
            Method m = whitelistIsDormantMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.access.Whitelist", true, null)
                        .getMethod("isDormant");
                whitelistIsDormantMethod = m;
            }
            Object res = m.invoke(null);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile Method whitelistIsDormantMethod;

    /**
     * Bridge for {@code AX-debugtab <name>} into {@link AgentImpl}. Mirrors
     * {@link #onAxCheck} — the impl side does the Mojang resolve + triple
     * fetch, then stores a time-boxed synthetic row that {@code getTabData}
     * splices in each frame. We keep the bridge here because AgentImpl lives
     * on bootstrap and this class lives on MC's loader.
     */
    private static void onAxDebugTab(String rest) {
        try {
            Method m = implOnAxDebugTabMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxDebugTab", String.class);
                implOnAxDebugTabMethod = m;
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxDebugTab bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxDebugTabMethod;

    /**
     * Bridge for {@code AX-hypixel <key>} into {@link AgentImpl}. Validates the
     * key argument isn't empty, persists via {@link com.aurex.agent.api.Config#writeApiKey},
     * rebuilds {@link AgentImpl}'s Hypixel pipeline in place, and fires a probe
     * that reports pass/fail/network in chat. {@link AgentImpl#onAxHypixel(String)}
     * log-scrubs the key body so the raw value never lands in {@code agent.log}.
     */
    private static void onAxHypixel(String rest) {
        try {
            Method m = implOnAxHypixelMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxHypixel", String.class);
                implOnAxHypixelMethod = m;
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxHypixel bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxHypixelMethod;

    /**
     * Read-only query for AX-status — "is Seraph wired up?" Returns true when
     * AgentImpl currently has a non-null {@code seraphCache} and no auth
     * failure. Bridges reflectively so the MC copy of Agent doesn't need a
     * compile-time reference; returns false on any reflection error to keep
     * the status line honest.
     */
    private static boolean isSeraphEnabled() {
        try {
            Method m = implSeraphStatusMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("isSeraphEnabled");
                implSeraphStatusMethod = m;
            }
            Object res = m.invoke(null);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile Method implSeraphStatusMethod;

    /**
     * Read-only query for AX-status — "is Urchin wired up?" Parallel to
     * {@link #isSeraphEnabled}. Returns false on any reflection error so the
     * status line stays honest.
     */
    private static boolean isUrchinEnabled() {
        try {
            Method m = implUrchinStatusMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("isUrchinEnabled");
                implUrchinStatusMethod = m;
            }
            Object res = m.invoke(null);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile Method implUrchinStatusMethod;

    /**
     * Read-only query for AX-status — "is Hypixel wired up?" Returns true when
     * AgentImpl currently has a non-null {@code statsCache} and no auth
     * failure. Bridges reflectively for the same reason as {@link #isSeraphEnabled};
     * returns false on any reflection error so the status line stays honest.
     */
    private static boolean isHypixelEnabled() {
        try {
            Method m = implHypixelStatusMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("isHypixelEnabled");
                implHypixelStatusMethod = m;
            }
            Object res = m.invoke(null);
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile Method implHypixelStatusMethod;

    /**
     * Bridge for {@code AX-check <name>} into {@link AgentImpl#onAxCheck}.
     * Same reflective pattern as the other Impl bridges — keeps the MC-loader
     * copy of Agent free of compile-time refs to bootstrap-only state.
     */
    private static void onAxCheck(String rest) {
        try {
            Method m = implOnAxCheckMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onAxCheck", String.class);
                implOnAxCheckMethod = m;
            }
            m.invoke(null, rest);
        } catch (Throwable t) {
            log("onAxCheck bridge failed: " + t);
        }
    }

    private static volatile Method implOnAxCheckMethod;

    private static boolean looksLikeAxCommand(String s) {
        if (s.length() < 3) return false;
        char c0 = Character.toLowerCase(s.charAt(0));
        char c1 = Character.toLowerCase(s.charAt(1));
        if (c0 != 'a' || c1 != 'x') return false;
        int i = 2;
        if (s.charAt(i) == ' ') i++;
        if (i >= s.length()) return false;
        char sep = s.charAt(i);
        return sep == '-' || sep == '_';
    }

    private static void arm() {
        long fireAt = System.currentTimeMillis() + ARM_MS;
        disarmAt = fireAt;
        displayEnabled = true;
        fetchArmed = true;
        log("AX-on (display on; fetch armed for " + ARM_MS + "ms)");
        sendClientChat(PREFIX + "§aon (fetch 3s)");
        // Timer.schedule(task, delay) — task.run() fires on the Timer's thread
        // after delay ms. DisarmTask checks disarmAt == fireAt before actually
        // disarming, so a later arm() simply invalidates this task.
        timer.schedule(new DisarmTask(fireAt, true), ARM_MS);
        kickoffFetches();
    }

    /**
     * Actually perform the auto-arm. Called from {@link AutoArmTask#run()} —
     * not directly from {@link #onIncomingChat(Object)}, because incoming-chat
     * only <i>schedules</i> the arm for ~2s after the game starts (see
     * {@link #POST_START_DELAY_MS}).
     *
     * <p>Same as {@link #arm()} but silent (no chat confirmation) and uses
     * {@link #AUTO_ARM_MS}. Flips {@link #displayEnabled} too, not just
     * {@link #fetchArmed}: if display were left off, the tab renderer would
     * stay gated off, which gates off the fetch kick-off path too. Auto-arming
     * both mirrors the manual {@code AX-on} UX.
     *
     * <p>Package-private so {@link AutoArmTask} can reach it.
     */
    static void autoArmNow() {
        long fireAt = System.currentTimeMillis() + AUTO_ARM_MS;
        disarmAt = fireAt;
        displayEnabled = true;
        fetchArmed = true;
        timer.schedule(new DisarmTask(fireAt, false), AUTO_ARM_MS);
        kickoffFetches();
        // AutoArmTask's token dance ensures this fires exactly once per
        // countdown sequence, so the threat report piggy-backs on it — no
        // extra dedup needed here.
        timer.schedule(new ThreatReportTask(), THREAT_REPORT_DELAY_MS);
        log("auto-arm fired (window " + AUTO_ARM_MS + "ms, threat report in "
                + THREAT_REPORT_DELAY_MS + "ms)");
    }

    /**
     * Eagerly pre-warm {@code StatsCache} for every player currently in the
     * client's player-info map. Called from {@link #arm()} and {@link #autoArm()}
     * so fetches fire immediately on arm, regardless of whether the user is
     * holding TAB.
     *
     * <p>Before this was added, fetch kick-off only happened inside
     * {@code AgentImpl.getTabData}, which only runs from {@code TabRenderer.render},
     * which only runs while {@code renderPlayerlist} is rendering — i.e., while
     * the user holds TAB. Arming without holding TAB during the window was a
     * silent no-op. Now: the arm triggers a fetch burst up front; once responses
     * land in cache, the first tab-render reads them instantly.
     *
     * <p>{@code NetHandlerPlayClient.playerInfoMap} is kept live by the client
     * independently of tab-render — the server pushes
     * {@code S38PacketPlayerListItem} updates as players join/leave/change, and
     * the handler mutates the map on the netty→main thread boundary. Reading
     * the map from the main thread (our call site) is safe.
     *
     * <p>Reflective path: {@code Minecraft.getMinecraft().getNetHandler()
     * .getPlayerInfoMap()} returns {@code Collection<NetworkPlayerInfo>}. We
     * convert to {@code Object[]} and hand off to bootstrap's {@code AgentImpl}
     * which already owns the v4-UUID filter + StatsCache kickoff pattern.
     *
     * <p>Silent no-op if {@code netHandler} is null (user at main menu / not
     * connected yet). Never throws — arm must not be able to crash the client.
     */
    private static void kickoffFetches() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = Agent.class.getClassLoader();

            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
            Object mc = mcClass.getMethod("getMinecraft").invoke(null);
            Object netHandler = mcClass.getMethod("getNetHandler").invoke(mc);
            if (netHandler == null) return;  // not in a world yet

            Object infos = netHandler.getClass().getMethod("getPlayerInfoMap").invoke(netHandler);
            if (!(infos instanceof java.util.Collection)) return;
            java.util.Collection<?> coll = (java.util.Collection<?>) infos;
            if (coll.isEmpty()) return;

            Object[] npis = coll.toArray();

            Method m = implPreWarmMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("preWarmFetches", Object[].class);
                implPreWarmMethod = m;
            }
            m.invoke(null, (Object) npis);
        } catch (Throwable t) {
            log("kickoffFetches failed: " + t);
        }
    }

    private static volatile Method implPreWarmMethod;

    /**
     * Bootstrap-hop to {@link AgentImpl#fireThreatReport()}. Called from
     * {@link ThreatReportTask} on the Timer thread after the game-start
     * countdown has completed and fetches have had time to settle.
     *
     * <p>Never throws — a failed report just means no chat line. Auto-arm
     * itself already fired successfully before we got here, so the user's
     * tab view is unaffected either way.
     */
    static void fireThreatReport() {
        try {
            Method m = implFireThreatReportMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("fireThreatReport");
                implFireThreatReportMethod = m;
            }
            m.invoke(null);
        } catch (Throwable t) {
            log("fireThreatReport bridge failed: " + t);
        }
    }

    private static volatile Method implFireThreatReportMethod;

    private static void disarm(boolean announce) {
        displayEnabled = false;
        fetchArmed = false;
        disarmAt = 0L;
        clearNickAlerts();
        log("AX-off (display + fetch off)");
        if (announce) sendClientChat(PREFIX + "§coff");
    }

    /**
     * Drain {@link AgentImpl}'s nick-alert dedup set so a subsequent AX-on
     * re-announces any nicks that are still in the lobby. Reflective hop
     * across the classloader split — same pattern as {@link #loadImpl()}.
     * Never throws: alert-reset is best-effort and failing to clear just
     * means the user sees each nick announced once per JVM, not per arm cycle.
     */
    private static void clearNickAlerts() {
        try {
            Method m = implClearNicksMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("clearNickAlerts");
                implClearNicksMethod = m;
            }
            m.invoke(null);
        } catch (Throwable t) {
            log("clearNickAlerts failed: " + t);
        }
    }

    private static volatile Method implClearNicksMethod;

    /**
     * Gate for rendering-side features (tab mutation, HUD, etc.). Persists
     * across the fetch window, only flips off on explicit AX-off.
     */
    public static boolean isDisplayEnabled() {
        return displayEnabled;
    }

    /**
     * Gate for outbound network calls. Only true during the 3s window after
     * AX-on so a stuck overlay can't keep hitting Hypixel's API.
     */
    public static boolean isFetchArmed() {
        return fetchArmed;
    }

    /**
     * Called from {@code GuiPlayerTabOverlay.getPlayerName} before each
     * {@code ARETURN}. Stack at call site is {@code [String, NetworkPlayerInfo]};
     * we pop both and push a (possibly) modified String — stack shape at ARETURN
     * is unchanged, so no stackmap edits are needed.
     *
     * <p>The real work (API-key / StatsCache / formatting) lives in
     * {@link AgentImpl#decorateInternal}. This method only exists on the MC
     * classloader copy of Agent, where we can't compile-time reference any
     * {@code com.aurex.agent.api.*} class — so we reflect across to the
     * bootstrap copy of AgentImpl once and cache the {@link Method} handle.
     *
     * <p>Gate order matters: we check {@code displayEnabled} first so a disarmed
     * overlay pays zero reflection cost per tab entry per frame. Only when the
     * overlay is on do we cross the classloader boundary.
     *
     * <p>Must never throw: runs on Lunar's render thread, once per tab entry,
     * every frame. On error we return the original name so tab still renders.
     */
    public static String decorateName(String originalName, Object networkPlayerInfo) {
        try {
            if (!displayEnabled || originalName == null) return originalName;
            Method m = implDecorateMethod;
            if (m == null) m = loadImpl();
            if (m == null) return originalName;
            return (String) m.invoke(null, originalName, networkPlayerInfo, fetchArmed);
        } catch (Throwable t) {
            return originalName;
        }
    }

    /** Cached {@link AgentImpl#decorateInternal} handle; null until first successful lookup. */
    private static volatile Method implDecorateMethod;

    private static Method loadImpl() {
        try {
            Method m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                    .getMethod("decorateInternal", String.class, Object.class, boolean.class);
            implDecorateMethod = m;
            return m;
        } catch (Throwable t) {
            log("AgentImpl.decorateInternal lookup failed: " + t);
            return null;
        }
    }

    /**
     * Reflective hop to {@code AgentImpl.getTabData}. Called by
     * {@link TabRenderer} once per tab frame (while AX-on) to get the
     * preformatted row data, the header array, and column IDs. Same
     * classloader bridge pattern as {@link #decorateName(String, Object)}.
     *
     * <p>{@code scoreboard} + {@code objective} are the MC objects from the
     * tab display slot — plumbed through so AgentImpl can read the health
     * column. Either may be {@code null} if the server hasn't set one.
     *
     * <p>Returns {@code Object[3] = {String[] colIds, String[] headers,
     * List<Object[]> rowEntries}} where each rowEntry is {@code {Object npi,
     * String[] cells}}. Returns {@code null} on any failure so the caller
     * can fall back to vanilla — never throws.
     */
    public static Object[] getTabData(Object[] npis, Object scoreboard, Object objective) {
        try {
            Method m = implGetTabDataMethod;
            if (m == null) m = loadGetTabData();
            if (m == null) return null;
            return (Object[]) m.invoke(null, npis, scoreboard, objective, fetchArmed);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Method implGetTabDataMethod;

    private static Method loadGetTabData() {
        try {
            Method m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                    .getMethod("getTabData", Object[].class, Object.class, Object.class, boolean.class);
            implGetTabDataMethod = m;
            return m;
        } catch (Throwable t) {
            log("AgentImpl.getTabData lookup failed: " + t);
            return null;
        }
    }

    /**
     * Called from RETURN sites of {@code NetHandlerPlayClient#handleJoinGame}
     * via {@link JoinGameTransformer}. Injected at RETURN (not HEAD) because
     * the method is what creates {@code mc.thePlayer} — the chat send path
     * needs thePlayer non-null.
     *
     * <p>Debounced at 500ms because BungeeCord-backed servers (Hypixel) fire
     * handleJoinGame multiple times on connect as the proxy hands the client
     * off to a sub-server. One announcement per real join is the UX we want.
     *
     * <p>Reloads config from disk and announces the result in chat. Reflective
     * hop to bootstrap-resident state. Never throws — world join must not be
     * blocked by Aurex.
     */
    public static void onServerJoin() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastJoinFireMs < JOIN_DEBOUNCE_MS) {
                return;
            }
            lastJoinFireMs = now;

            Method m = implOnServerJoinMethod;
            if (m == null) {
                m = Class.forName("com.aurex.agent.AgentImpl", true, null)
                        .getMethod("onServerJoin");
                implOnServerJoinMethod = m;
            }
            m.invoke(null);
        } catch (Throwable t) {
            log("onServerJoin bridge failed: " + t);
        }
    }

    private static volatile Method implOnServerJoinMethod;
    private static volatile long lastJoinFireMs;
    /** Window for collapsing Bungee proxy→sub-server double-fires. */
    private static final long JOIN_DEBOUNCE_MS = 500L;

    /**
     * Print a client-side chat line (not sent to the server). Used for AX
     * command confirmations and nick alerts.
     *
     * <p>Reflective because this code path ends up running in three
     * classloader copies (app / bootstrap / MC); only the MC copy can see
     * {@code net.minecraft.*} via its own loader. Callers from bootstrap
     * (e.g. {@link AgentImpl}) must pass an explicit MC-capable loader via
     * {@link #sendClientChat(String, ClassLoader)} — bootstrap's own search
     * won't resolve MC classes.
     *
     * <p>Public so {@link DisarmTask}, {@link AgentImpl}, and the
     * cross-package {@link com.aurex.agent.access.Whitelist} can call it.
     */
    public static void sendClientChat(String text) {
        sendClientChat(text, null);
    }

    /**
     * Same as {@link #sendClientChat(String)} but uses {@code mcLoader} to
     * resolve {@code net.minecraft.*} classes. Falls back to a chain of
     * candidate loaders if {@code null}, ending at this class's own loader.
     *
     * <p>Never throws — running on the render thread; a chat-send failure
     * must not bring down the frame.
     */
    public static void sendClientChat(String text, ClassLoader mcLoader) {
        try {
            ClassLoader cl = mcLoader;
            if (cl == null) cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = Agent.class.getClassLoader();

            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
            Method getMinecraft = mcClass.getMethod("getMinecraft");
            Object mc = getMinecraft.invoke(null);

            Field thePlayerField = mcClass.getField("thePlayer");
            Object thePlayer = thePlayerField.get(mc);
            if (thePlayer == null) {
                log("sendClientChat: thePlayer is null, skipping: " + text);
                return;
            }

            Class<?> componentClass = Class.forName("net.minecraft.util.ChatComponentText", true, cl);
            Object component = componentClass.getConstructor(String.class).newInstance(text);

            Class<?> iChatComponent = Class.forName("net.minecraft.util.IChatComponent", true, cl);
            Class<?> entityPlayer = Class.forName("net.minecraft.entity.player.EntityPlayer", true, cl);
            Method addChatMessage = entityPlayer.getMethod("addChatMessage", iChatComponent);
            addChatMessage.invoke(thePlayer, component);
        } catch (Throwable t) {
            log("sendClientChat failed: " + t);
        }
    }

    /**
     * Variant of {@link #sendClientChat(String, ClassLoader)} that attaches a
     * {@code SHOW_TEXT} hover event to individual segments. Each row of
     * {@code segments} is {@code {text, hoverTextOrNull}} — when the hover half
     * is {@code null}, the segment renders as plain text. Use this when you
     * want tooltips on part of a chat line (e.g. Seraph tag + its reason).
     *
     * <p>Built reflectively against {@code ChatComponentText}, {@code ChatStyle},
     * and {@code HoverEvent.Action.SHOW_TEXT}, so this stays loader-boundary
     * safe — only strings cross the bootstrap → MC-loader call.
     */
    static void sendClientChatWithHovers(String[][] segments, ClassLoader mcLoader) {
        if (segments == null || segments.length == 0) return;
        try {
            ClassLoader cl = mcLoader;
            if (cl == null) cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = Agent.class.getClassLoader();

            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
            Object mc = mcClass.getMethod("getMinecraft").invoke(null);
            Object thePlayer = mcClass.getField("thePlayer").get(mc);
            if (thePlayer == null) {
                log("sendClientChatWithHovers: thePlayer is null, skipping");
                return;
            }

            Class<?> compClass = Class.forName("net.minecraft.util.ChatComponentText", true, cl);
            Class<?> iComp = Class.forName("net.minecraft.util.IChatComponent", true, cl);
            Class<?> styleClass = Class.forName("net.minecraft.util.ChatStyle", true, cl);
            Class<?> hoverClass = Class.forName("net.minecraft.event.HoverEvent", true, cl);
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Class<? extends Enum> hoverAction = (Class<? extends Enum>) Class.forName(
                    "net.minecraft.event.HoverEvent$Action", true, cl);
            @SuppressWarnings("unchecked")
            Object showText = Enum.valueOf(hoverAction, "SHOW_TEXT");

            // Root is an empty text — all real content is appended as siblings.
            // Keeps the append loop uniform (root never has hover itself).
            Object root = compClass.getConstructor(String.class).newInstance("");
            Method appendSibling = iComp.getMethod("appendSibling", iComp);
            Method setStyle = iComp.getMethod("setChatStyle", styleClass);
            Method setHover = styleClass.getMethod("setChatHoverEvent", hoverClass);

            for (String[] seg : segments) {
                if (seg == null || seg.length == 0 || seg[0] == null) continue;
                Object piece = compClass.getConstructor(String.class).newInstance(seg[0]);
                String hoverText = seg.length > 1 ? seg[1] : null;
                if (hoverText != null && !hoverText.isEmpty()) {
                    Object hoverComp = compClass.getConstructor(String.class).newInstance(hoverText);
                    Object hoverEvt = hoverClass.getConstructor(hoverAction, iComp)
                            .newInstance(showText, hoverComp);
                    Object style = styleClass.getConstructor().newInstance();
                    setHover.invoke(style, hoverEvt);
                    setStyle.invoke(piece, style);
                }
                appendSibling.invoke(root, piece);
            }

            Class<?> entityPlayer = Class.forName("net.minecraft.entity.player.EntityPlayer", true, cl);
            entityPlayer.getMethod("addChatMessage", iComp).invoke(thePlayer, root);
        } catch (Throwable t) {
            log("sendClientChatWithHovers failed: " + t);
        }
    }

    /**
     * Append a timestamped line to %APPDATA%\Aurex\agent.log.
     *
     * We write to a file, not System.out, because Lunar swallows stdout —
     * println lines from inside Lunar's JVM don't show up anywhere visible.
     * A file on disk is the reliable way to see that our code ran.
     *
     * <p>Public (not package-private) because cross-package callers like
     * {@link com.aurex.agent.access.Whitelist} need it too — the API clients
     * under {@code com.aurex.agent.api.*} take an injected {@code
     * Consumer<String>}, but static-only helpers don't have a constructor to
     * plumb one through, and a dedicated logger setter per helper is more
     * ceremony than this one-keyword change.
     */
    public static void log(String message) {
        File file = logFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            // Can't create %APPDATA%\Aurex — bail silently. We deliberately
            // do NOT throw, because an exception from agentmain would be
            // caught by the JVM and surfaced as AgentInitializationException
            // in the loader, which is noisy for a best-effort log write.
            return;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        // try-with-resources — guarantees the writer is closed even on throw.
        // FileWriter(file, true) opens in append mode (like "a" in Python).
        try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
            out.println(timestamp + " " + TAG + ": " + message);
        } catch (IOException ignored) {
            // Same rationale as above — never let a log failure bubble up.
        }
    }

    /**
     * Truncate the log at the start of each JVM run so old launches don't
     * pile up. Called from {@link #premain} exactly once per JVM — the
     * bootstrap and MC-loader copies of Agent never run this, they just
     * append. Safe if the file doesn't exist yet.
     */
    private static void truncateLog() {
        File file = logFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        try (PrintWriter out = new PrintWriter(new FileWriter(file, false))) {
            // opening in non-append mode truncates; nothing to write.
        } catch (IOException ignored) {
            // Same as log(): never let a log-init failure bubble up.
        }
    }

    /** Resolve %APPDATA%\Aurex\agent.log. Falls back to user home on non-Windows. */
    private static File logFile() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) {
            appdata = System.getProperty("user.home");
        }
        return new File(new File(appdata, "Aurex"), "agent.log");
    }
}
