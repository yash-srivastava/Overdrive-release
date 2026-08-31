package com.overdrive.app.automation;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.action.VehicleControlAction;
import com.overdrive.app.automation.condition.Conditions;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.condition.TimeEvent;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.BaseValue;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.Messages;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Automations {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    static final String AUTOMATION_HOME_PROPERTY = "overdrive.automation.home";
    private static final File AUTOMATION_HOME = new File(System.getProperty(
            AUTOMATION_HOME_PROPERTY, ScratchPaths.path(".automations")));
    private static final File AUTOMATION_CONFIG = new File(AUTOMATION_HOME, "config.json");
    // Last-known-good backup + scratch file for the atomic write. loadFromFile falls back to .bak when
    // the live file is truncated/corrupt (e.g. daemon killed mid-write on ACC-off), so a torn write can
    // never silently wipe every configured automation.
    private static final File AUTOMATION_BACKUP = new File(AUTOMATION_HOME, "config.json.bak");
    private static final File AUTOMATION_TMP = new File(AUTOMATION_HOME, "config.json.tmp");
    // Serializes the read-snapshot-write sequence so two concurrent HTTP request threads can't interleave
    // their FileOutputStreams and produce a mangled file.
    private static final Object SAVE_LOCK = new Object();
    private static final Map<EventData, Value> state = new ConcurrentHashMap<>();
    // Expiration is a read/condition overlay: the raw value remains in state so a recovered
    // sensor can still compare against its last observation without manufacturing an edge.
    // All access to this map, and state mutations that add/remove an expiration, use STATE_LOCK.
    private static final Object STATE_LOCK = new Object();
    private static final Map<EventData, Long> stateExpiresAt = new java.util.HashMap<>();
    // Per key: the last value actually DELIVERED to trigger evaluation (stateChanged), as
    // opposed to merely stored. A silent seed stores without delivering; a fired transition
    // does both. This is what makes cross-publisher delivery exactly-once: an OBSERVED edge
    // (updateObservedEdge) fires only when its value has not already been delivered — so a
    // sampled snapshot winning the race to deliver the same transition suppresses the edge's
    // re-fire, a silent same-value seed does not, and a duplicate edge publish is a no-op
    // even if the caller's own dedup slips. All access under {@link #STATE_LOCK}; the
    // decision to fire and the delivery mark are committed atomically, so exactly one
    // publisher claims delivery of any given value.
    private static final Map<EventData, Value> stateDelivered = new java.util.HashMap<>();
    // Automation and nested-group condition evaluation only calls Map.get. Keep that read live
    // so a value that expires while no telemetry is arriving becomes unavailable immediately.
    private static final Map<EventData, Value> conditionState = new java.util.AbstractMap<EventData, Value>() {
        @Override
        public Value get(Object key) {
            return key instanceof EventData ? getStateValue((EventData) key) : null;
        }

        @Override
        public java.util.Set<Map.Entry<EventData, Value>> entrySet() {
            Map<EventData, Value> visible = new java.util.HashMap<>();
            synchronized (STATE_LOCK) {
                long now = System.currentTimeMillis();
                for (Map.Entry<EventData, Value> entry : state.entrySet()) {
                    if (!isStateExpired(entry.getKey(), now)) {
                        visible.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            return java.util.Collections.unmodifiableMap(visible).entrySet();
        }
    };
    // The schema object graph (~200 Label/EnumType/EventCondition/Action objects) is built lazily on
    // first use rather than at class-load. A daemon with zero configured automations touches Automations
    // on every telemetry snapshot (via BydEvent.isDisabled()) but never needs the schema, so this keeps
    // the "no automation configured => no extra compute" property honest — the graph only materializes
    // when an automation is loaded from disk or the schema API is hit.
    private static volatile Conditions conditions;
    private static volatile Actions actions;
    private static volatile Type delay;
    private static final Object SCHEMA_LOCK = new Object();
    private static final Map<String, Automation> automations = new ConcurrentHashMap<>();
    // O(1) enabled-automation count so isDisabled() (called ~30x per telemetry snapshot on the daemon
    // hot path) is a field read instead of a full stream scan of the map. Maintained under SAVE_LOCK
    // alongside every mutation. volatile for cross-thread visibility from the telemetry thread.
    private static volatile int enabledCount = 0;
    // Bumped whenever the automation feature crosses the enabled/disabled boundary. A publisher
    // samples this before and after its callback so a complete disable -> enable cycle cannot hide
    // between two identical isDisabled() reads and silently drop the observed vehicle state.
    private static volatile long enabledStateGeneration = 0L;
    // Memo for isEventReferenced, which active fast pollers call several times a second.
    // The answer only changes when the automation config changes, so cache per key and invalidate
    // on mutation by bumping configGeneration. Unreferenced pollers are cancelled completely.
    //
    // Each entry CARRIES the generation it was computed under, and a read ignores any entry from
    // an older one. A plain map + clear() is not enough: a walk that starts before a mutation can
    // finish after the clear and reinsert its stale answer, which would then be served forever —
    // a stale "false" silently parks a poller so the rule never fires. Stamping makes that
    // reinsertion harmless (the entry is rejected on read) instead of permanent.
    private static final Map<EventData, RefEntry> referenceCache = new ConcurrentHashMap<>();
    private static volatile int configGeneration = 0;
    private static volatile boolean pollersReady = false;

    /** A memoized {@link #isEventReferenced} answer plus the config generation it was computed under. */
    private static final class RefEntry {
        final int generation;
        final boolean referenced;

        RefEntry(int generation, boolean referenced) {
            this.generation = generation;
            this.referenced = referenced;
        }
    }

    private static void refreshConditionalPollers() {
        if (!pollersReady) return;
        // Every source is an optional integration boundary. A missing OEM class or a source
        // initializer failure must disable only that source, never poison Automations.<clinit>
        // and take down the complete API with NoClassDefFoundError.
        runIsolatedStartupStep("door subscription",
                () -> com.overdrive.app.automation.condition.DoorEvent.start());
        runIsolatedStartupStep("boot event",
                () -> com.overdrive.app.automation.condition.NetworkEvent.start());
        runIsolatedStartupStep("time poller", () -> TimeEvent.refresh());
        runIsolatedStartupStep("network poller",
                () -> com.overdrive.app.automation.condition.NetworkEvent.refresh());
        runIsolatedStartupStep("turn-signal poller",
                () -> com.overdrive.app.automation.condition.TurnSignalEvent.refresh());
        runIsolatedStartupStep("drive-mode poller",
                () -> com.overdrive.app.automation.condition.DriveModeEvent.refresh());
        runIsolatedStartupStep("blind-spot poller",
                () -> com.overdrive.app.automation.condition.BlindSpotEvent.refresh());
        runIsolatedStartupStep("seatbelt poller",
                () -> com.overdrive.app.automation.condition.SeatbeltEvent.refresh());
        runIsolatedStartupStep("dynamics poller",
                () -> com.overdrive.app.automation.condition.DynamicsEvent.refresh());
        runIsolatedStartupStep("energy-regen poller",
                () -> com.overdrive.app.automation.condition.EnergyRegenEvent.refresh());
        runIsolatedStartupStep("gear poller",
                () -> com.overdrive.app.automation.condition.GearEvent.refresh());
        runIsolatedStartupStep("climate poller",
                () -> com.overdrive.app.automation.condition.ClimateEvent.refresh());
        runIsolatedStartupStep("door fallback poller",
                () -> com.overdrive.app.automation.condition.DoorEvent.refresh());
    }

    /**
     * Run one optional startup integration without allowing it to poison this class.
     *
     * <p>Linkage errors are included intentionally: OEM framework classes vary by vehicle and
     * firmware, and a missing class must make only its signal unavailable. VM-fatal conditions
     * still propagate because continuing after them is not safe.
     */
    static void runIsolatedStartupStep(String name, Runnable step) {
        try {
            step.run();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            try {
                logger.error("Automation startup step '" + name + "' failed: "
                        + failure.getClass().getName() + ": " + failure.getMessage(), failure);
            } catch (Throwable ignored) {
                // Logging is diagnostic only; it must not recreate the initializer failure.
            }
        }
    }

    /**
     * Lazily build and return the conditions schema.
     */
    private static Conditions conditions() {
        Conditions c = conditions;
        if (c == null) {
            synchronized (SCHEMA_LOCK) {
                c = conditions;
                if (c == null) c = conditions = new Conditions();
            }
        }
        return c;
    }

    /**
     * Lazily build and return the actions schema.
     */
    private static Actions actions() {
        Actions a = actions;
        if (a == null) {
            synchronized (SCHEMA_LOCK) {
                a = actions;
                if (a == null) a = actions = new Actions();
            }
        }
        return a;
    }

    /**
     * Lazily build and return the delay type.
     */
    private static Type delay() {
        Type d = delay;
        if (d == null) {
            synchronized (SCHEMA_LOCK) {
                d = delay;
                if (d == null) d = delay =
                        new IntType(new Label("delay", "automation.delay"), 0, 86400);
            }
        }
        return d;
    }

    /**
     * Recompute the cached enabled-automation count. MUST be called under {@link #SAVE_LOCK} after any
     * mutation of the automations map or an automation's disabled flag.
     */
    private static void refreshEnabledCount() {
        boolean wasDisabled = enabledCount == 0;
        int n = 0;
        for (Automation a : automations.values()) if (!a.isDisabled()) n++;
        enabledCount = n;
        if (wasDisabled != (n == 0)) {
            enabledStateGeneration++;
        }
        // Invalidate the memo. The bump alone is what invalidates (entries stamped with the old
        // generation are ignored on read); the clear just reclaims the few stale entries so they
        // don't sit until the next lookup overwrites them. Always called under SAVE_LOCK, so the
        // non-atomic ++ has a single writer.
        configGeneration++;
        referenceCache.clear();
        seedReferencedVariables();
        refreshConditionalPollers();
    }

    /**
     * Invalidate the {@link #isEventReferenced} memo after an ACTION GROUP changed.
     *
     * <p>The reference walk expands an {@code actionGroup} into its group body, so a group edit can
     * change the answer for a key even though no automation was touched — and a stale cached
     * {@code false} silently parks that key's poller, leaving the signal null forever. Group
     * mutations do not affect the enabled count, so this bumps only the generation.
     */
    static void invalidateReferenceCacheForGroupChange() {
        synchronized (SAVE_LOCK) {
            configGeneration++;
            referenceCache.clear();
            seedReferencedVariables();
            refreshConditionalPollers();
        }
    }

    /**
     * Seed every user VARIABLE referenced by any automation to "" (empty) if it has no
     * value yet, so a first-run comparison behaves intuitively:
     * {@code Parking_Mode != true} is TRUE before the flag is ever set (empty ≠ "true"),
     * and {@code Parking_Mode == true} is FALSE. Without this, an unseen variable reads
     * as null and {@link AutomationCondition#compare} returns false for BOTH — so a
     * {@code != true} guard would never pass on the first run and the automation could
     * never start. Idempotent: only seeds a variable that isn't already in the state
     * (a real set via SetVariableAction always wins), and empty-string is a distinct,
     * stable value so it never re-fires. Called under {@link #SAVE_LOCK} after any load
     * or mutation, so newly-referenced variables get seeded as automations change.
     */
    private static void seedReferencedVariables() {
        for (Automation a : automations.values()) {
            // Triggers + conditions that reference a variable event.
            for (EventData key : a.getTriggers()) seedIfVariable(key);
            // getAllConditions (not getConditions) so a variable referenced only inside
            // a nested condition group is still seeded — else its "!= true" guard would
            // read null and never pass on first run.
            for (AutomationCondition c : a.getAllConditions()) seedIfVariable(c.getEventData());
            // Also seed variables a "Set Variable" action DEFINES, so a condition on a
            // variable only ever set (never triggered/conditioned) elsewhere still reads
            // empty rather than null before its first set.
            seedVariablesDefinedByActions(a.getActions());
            seedVariablesDefinedByActions(a.getElseActions());
        }
    }

    private static void seedIfVariable(EventData key) {
        if (key == null) return;
        if (!com.overdrive.app.automation.condition.BydEvent.VARIABLE_TYPE.equals(key.getType())) return;
        // putIfAbsent semantics: never clobber a real value already set.
        state.putIfAbsent(key, new StringValue(""));
    }

    private static void seedVariablesDefinedByActions(java.util.List<AutomationAction> actions) {
        // Recurse into nested children so every variable-writing action inside a loop/if/group
        // is seeded — otherwise its variable would read null (not "") on first run and a
        // "!= true" guard would never pass. forEachAction walks the whole tree.
        forEachAction(actions, action -> {
            if (!"setVariable".equals(action.getType()) && !"incrementVariable".equals(action.getType())
                    && !"computeVariable".equals(action.getType())
                    && !"captureVariable".equals(action.getType())) return;
            Object name = action.getVariables() == null ? null : action.getVariables().get("name");
            if (name == null) return;
            String n = name.toString().trim();
            if (n.isEmpty()) return;
            seedIfVariable(com.overdrive.app.automation.action.SetVariableAction.variableEvent(n));
        });
    }

    /**
     * Depth-first walk of an action list AND every nested child/else-child list,
     * applying {@code visitor} to each action. The single place that knows the action
     * tree shape, so every walker (variable seeding, manual-clip sizing, shell scans)
     * recurses consistently and a control-flow-nested action is never missed.
     */
    private static void forEachAction(java.util.List<AutomationAction> actions,
                                      java.util.function.Consumer<AutomationAction> visitor) {
        if (actions == null) return;
        for (AutomationAction action : actions) {
            if (action == null) continue;
            visitor.accept(action);
            forEachAction(action.getChildActions(), visitor);
            forEachAction(action.getElseChildActions(), visitor);
        }
    }

    private Automations() {}

    /**
     * Largest manual-replay total window (beforeSeconds + afterSeconds) across
     * every automation that may run automatically or explicitly. Consumed by
     * {@link com.overdrive.app.recording.ManualClipService#getConfiguredRetentionSeconds()}
     * so the pre-record ring is sized for automation-triggered replays too — a
     * clip bound only in an automation (never in Key Mapping) must still fit.
     *
     * <p>Cheap and side-effect-free: iterates the in-memory automation map (a fully
     * disabled automation contributes 0). Manual-only rules are included because a
     * key-mapped explicit run still needs its requested pre-record window. Bounded to
     * the manual-clip max so a hand-edited config can never request an oversized ring.
     */
    public static int getMaxManualClipRetentionSeconds() {
        int max = 0;
        for (Automation automation : automations.values()) {
            if (automation.isFullyDisabled()) continue;
            max = Math.max(max, maxManualClipRetention(automation.getActions()));
            max = Math.max(max, maxManualClipRetention(automation.getElseActions()));
        }
        return Math.min(com.overdrive.app.recording.ManualClipWindow.MAX_SECONDS, max);
    }

    private static int maxManualClipRetention(List<AutomationAction> actions) {
        // int[] so the lambda can accumulate. Recurse (forEachAction) so a manualClip
        // nested in a loop/if is counted too — otherwise the pre-record ring would be
        // undersized and that replay would silently fail to capture its window.
        final int[] max = {0};
        forEachAction(actions, action -> {
            if (!"manualClip".equals(action.getType())) return;
            Map<String, Object> variables = action.getVariables();
            if (variables == null) return;
            max[0] = Math.max(max[0],
                    intVar(variables, "beforeSeconds") + intVar(variables, "afterSeconds"));
        });
        return max[0];
    }

    private static int intVar(Map<String, Object> variables, String key) {
        Object value = variables.get(key);
        int seconds = value instanceof Number ? ((Number) value).intValue() : 0;
        return Math.max(0, seconds);
    }

    /**
     * Push the current manual-replay retention to the live encoder after an
     * automation mutation, so a newly-saved (or removed) replay action changes
     * the pre-record window without waiting for the next camera cold start —
     * mirroring the Key Mapping save path. Best-effort and never throws: sizing
     * also happens on encoder init, so a transient failure here self-heals.
     */
    private static void applyManualClipRetention() {
        try {
            com.overdrive.app.recording.ManualClipService.getInstance()
                    .reapplyLiveRetention();
        } catch (Throwable ignored) {
            // Retention is re-derived on the next encoder init regardless.
        }
    }

    /**
     * Get a condition schema with a specific key
     *
     * @param key The key for a condition
     * @return The condition schema for that key
     */
    public static EventCondition getCondition(String key) {
        return conditions().getCondition(key);
    }

    /**
     * Get an action schema with a specific key
     *
     * @param key The key for an action
     * @return The action schema for that key
     */
    public static Action getAction(String key) {
        return actions().getAction(key);
    }

    // Runtime re-entrancy guard for nested action execution (loops / if / action groups
    // all run their children back through runActionList). Bounds a cyclic/over-deep tree
    // that slipped past the parse-time MAX_ACTION_DEPTH (e.g. an action group calling
    // itself). Per-thread because actions run on the single AutomationQueue worker (and
    // the /test executor); each independent run starts at 0.
    // Must sit ABOVE the parse-time control-flow cap (Automation.MAX_ACTION_DEPTH=8) with
    // headroom, so a LEGAL max-depth automation that ALSO runs action groups (each an extra
    // runActionList re-entry) executes fully without falsely tripping this guard; it only
    // fires on a genuine runaway/cycle. 16 = 8 (static cap) + generous action-group headroom.
    private static final int MAX_RUN_DEPTH = 16;
    private static final ThreadLocal<Integer> RUN_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> ACTION_CHAIN_SUCCEEDED =
            ThreadLocal.withInitial(() -> true);
    // Manual/Test chains may legitimately mutate automation state (Set/Increment/
    // Compute/Capture Variable) while there are zero automatic rules. The global
    // disabled hot-path normally drops such writes before touching the state map.
    // This thread-local opens STORE permission only for the explicit action thread;
    // update() still suppresses trigger delivery while enabledCount == 0.
    private static final ThreadLocal<Boolean> EXPLICIT_RUN =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> SILENT_SEED =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<QueueActionCursor> QUEUE_ACTION_CURSOR = new ThreadLocal<>();
    // ── Per-execution ownership token ──────────────────────────────────────────
    // Identifies ONE automation execution across all of its attempts (a deferred
    // wait suspends and resumes the same cursor, so the token must live on the
    // cursor, not the thread). Ownership-aware endpoints (the camera-view
    // show/hide pair) read it via currentExecutionToken() — ApiAction's
    // automationApiRequest is a synchronous in-process call on this very thread,
    // so the thread-local is visible to the handler. Explicit Run-now / key-mapping
    // executions deliberately do NOT set a token: their API calls stay "ownerless",
    // which the camview hide path maps to the legacy global-close behaviour.
    private static final java.util.concurrent.atomic.AtomicLong EXECUTION_TOKEN_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final ThreadLocal<Long> CURRENT_EXECUTION_TOKEN = new ThreadLocal<>();

    /** The ownership token of the automation execution running on THIS thread, or
     *  null when the caller is not inside an automation execution (manual API call,
     *  key mapping, overlay tap, web UI). Never throws. */
    public static Long currentExecutionToken() {
        return CURRENT_EXECUTION_TOKEN.get();
    }
    private static final ThreadLocal<java.util.IdentityHashMap<AutomationAction, Integer>>
            QUEUE_ACTION_OCCURRENCES = new ThreadLocal<>();
    private static final ThreadLocal<Automation> ACTIVE_QUEUED_DEFINITION = new ThreadLocal<>();
    private static final ThreadLocal<String> ACTIVE_QUEUED_AUTOMATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> ACTIVE_QUEUED_DEFINITION_REVISION =
            new ThreadLocal<>();
    /** Retained/latest-state reconciliation may navigate control flow but execute only allowlisted
     * vehicle state setters. The flag is inherited by nested runActionList calls. */
    private static final ThreadLocal<Boolean> STATE_SETTER_ONLY =
            ThreadLocal.withInitial(() -> false);

    // Set by a Wait Until whose condition never became true, to STOP the rest of the chain.
    // Previously a timed-out wait just fell through and every following action ran anyway, so
    // "wait until gear = P → fold mirrors" folded the mirrors even though the car never went
    // into P — the wait looked like it did nothing. A timeout is now a failed precondition:
    // the remaining actions (at every nesting level) are skipped and the run ends.
    // Per-thread for the same reason as RUN_DEPTH, and cleared at the top of each run.
    private static final ThreadLocal<Boolean> CHAIN_ABORTED = ThreadLocal.withInitial(() -> false);

    /** Called by a wait action when its timeout elapsed without the condition being met. */
    public static void abortChain() { CHAIN_ABORTED.set(true); }

    /** Clear the abort flag — at the start of every independent action run. */
    public static void resetChain() { CHAIN_ABORTED.set(false); }

    /** Whether the current chain has been aborted by a timed-out wait. */
    public static boolean chainAborted() { return CHAIN_ABORTED.get(); }

    /**
     * Yield a top-level queued action without parking the singleton automation worker.
     * Nested flow actions keep their existing blocking semantics so loop/group replay
     * and wall-clock caps remain unchanged.
     */
    public static boolean deferQueuedAction(
            AutomationAction action, long delayMs, boolean actionCompleted) {
        QueueActionCursor cursor = QUEUE_ACTION_CURSOR.get();
        if (cursor == null || action == null || delayMs <= 0L || RUN_DEPTH.get() != 1) {
            return false;
        }
        cursor.deferredAction = action;
        cursor.deferredActionCompleted = actionCompleted;
        cursor.resumeAtNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delayMs);
        return true;
    }

    /**
     * Return one stable timeout deadline for the current top-level queued wait.
     * A non-queued or nested wait returns 0 and uses its legacy blocking path.
     */
    public static long queuedWaitDeadlineNanos(
            AutomationAction action, long timeoutMs) {
        QueueActionCursor cursor = QUEUE_ACTION_CURSOR.get();
        if (cursor == null || action == null || timeoutMs <= 0L || RUN_DEPTH.get() != 1) {
            return 0L;
        }
        if (cursor.pendingWaitAction != action) {
            cursor.pendingWaitAction = action;
            cursor.pendingWaitDeadlineNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }
        return cursor.pendingWaitDeadlineNanos;
    }

    /** Clear the persisted deadline after a queued wait succeeds or times out. */
    public static void clearQueuedWait(AutomationAction action) {
        QueueActionCursor cursor = QUEUE_ACTION_CURSOR.get();
        if (cursor != null && cursor.pendingWaitAction == action) {
            cursor.pendingWaitAction = null;
            cursor.pendingWaitDeadlineNanos = 0L;
        }
    }

    /**
     * Run a live sampler as a baseline observation. Values are stored, but transitions observed
     * during this call never run automation actions. Conditional pollers use this for their first
     * sample after a rule is enabled so stale state cannot make saving the rule look like a
     * physical vehicle edge.
     */
    public static void runSilentSeed(Runnable sampler) {
        if (sampler == null) return;
        boolean previous = SILENT_SEED.get();
        SILENT_SEED.set(true);
        try {
            sampler.run();
        } finally {
            if (previous) {
                SILENT_SEED.set(true);
            } else {
                SILENT_SEED.remove();
            }
        }
    }

    /**
     * Run a list of actions in order, re-entrantly: a control-flow action's
     * {@code trigger} calls back here with its child list, so loops / if-branches /
     * action groups all execute through one path with a shared depth guard. A flat list
     * runs at depth 1, identical to the original inline loop. Null/unknown-type elements
     * are skipped (defense-in-depth for a hand-edited config), and the depth guard stops
     * runaway nesting without killing the worker.
     */
    public static boolean runActionList(java.util.List<AutomationAction> actionList) {
        if (actionList == null || Thread.currentThread().isInterrupted()) return false;
        int depth = RUN_DEPTH.get();
        if (depth >= MAX_RUN_DEPTH) {
            logger.warn("Action nesting depth cap (" + MAX_RUN_DEPTH + ") hit — stopping to avoid runaway/cycle");
            ACTION_CHAIN_SUCCEEDED.set(false);
            return false;
        }
        // Depth 0 = the start of an independent run (the queue worker or /test). Clear any
        // abort left by a previous run on this same pooled/worker thread, so one timed-out
        // wait can never suppress the NEXT automation's actions.
        if (depth == 0) {
            resetChain();
            ACTION_CHAIN_SUCCEEDED.set(true);
        }
        RUN_DEPTH.set(depth + 1);
        boolean successful = true;
        try {
            for (AutomationAction automationAction : actionList) {
                if (!activeQueuedDefinitionIsCurrent()) {
                    ACTION_CHAIN_SUCCEEDED.set(false);
                    abortChain();
                    break;
                }
                // A timed-out wait aborts the rest of the chain, including the outer levels
                // it returns into (a wait inside an If/Loop body stops the whole run, not just
                // that body) — the precondition it was guarding never came true.
                if (chainAborted() || Thread.currentThread().isInterrupted()) break;
                if (automationAction == null) continue;
                Action action = getAction(automationAction.getType());
                if (action == null) continue;
                boolean stateSetterOnly = STATE_SETTER_ONLY.get();
                boolean stateSetter = action instanceof VehicleControlAction;
                boolean controlFlow = action.hasChildActions()
                        || "actionGroup".equals(automationAction.getType());
                if (stateSetterOnly && !stateSetter && !controlFlow) {
                    continue;
                }
                QueueActionCursor queueCursor = QUEUE_ACTION_CURSOR.get();
                java.util.IdentityHashMap<AutomationAction, Integer> occurrences =
                        QUEUE_ACTION_OCCURRENCES.get();
                int occurrence = 0;
                if (queueCursor != null && occurrences != null) {
                    occurrence = occurrences.getOrDefault(automationAction, 0) + 1;
                    occurrences.put(automationAction, occurrence);
                    if (occurrence <= queueCursor.completedOccurrences.getOrDefault(
                            automationAction, 0)) {
                        continue;
                    }
                }
                boolean actionSucceeded = stateSetterOnly && stateSetter
                        ? ((VehicleControlAction) action)
                                .triggerLatestStateSetterWithResult(automationAction)
                        : action.triggerWithResult(automationAction);
                successful &= actionSucceeded;
                if (!actionSucceeded) {
                    ACTION_CHAIN_SUCCEEDED.set(false);
                }
                if (queueCursor != null && occurrence != 0
                        && actionSucceeded
                        && ACTION_CHAIN_SUCCEEDED.get()
                        && !chainAborted()
                        && (!queueCursor.hasDeferredAction()
                                || queueCursor.deferredActionCompleted(automationAction))
                        && (!Thread.currentThread().isInterrupted() || !controlFlow)) {
                    queueCursor.completedOccurrences.merge(
                            automationAction, 1, Integer::sum);
                }
                if (queueCursor != null && queueCursor.hasDeferredAction()) break;
            }
        } finally {
            RUN_DEPTH.set(depth);
        }
        return successful && ACTION_CHAIN_SUCCEEDED.get() && !chainAborted()
                && !Thread.currentThread().isInterrupted();
    }

    /**
     * Check whether the delay is an allowed value
     *
     * @param seconds The number of seconds to delay the actions
     * @return true if it is valid, false otherwise
     */
    public static boolean isValidDelay(int seconds) {
        return delay().isValid(seconds);
    }

    /**
     * Whether automations are disabled.
     * Disabled when there are no automations or all of them are disabled. Backed by an O(1) cached
     * count (not a map scan) because this is called ~30x per telemetry snapshot on the daemon hot path.
     *
     * @return Whether the automation feature is enabled
     */
    public static boolean isDisabled() {
        return enabledCount == 0;
    }

    /** Package-local generation used by {@link AutomationQueue} to detect hidden disable cycles. */
    static long enabledStateGeneration() {
        return enabledStateGeneration;
    }

    /** Package-local generation used to replay a publication across any config mutation. */
    static int configGeneration() {
        return configGeneration;
    }

    /**
     * Whether an automation with this id currently exists.
     *
     * @param id The id to look up
     * @return true if an automation is stored under this id
     */
    public static boolean exists(String id) {
        return id != null && automations.containsKey(id);
    }

    /**
     * Whether any ENABLED automation references the given event — as a TRIGGER or as a
     * CONDITION. Lets a high-cadence event source (e.g. the turn-signal fast poll) gate
     * its expensive per-tick work on "is anyone actually listening for this?", so it
     * stays a true no-op until a relevant automation exists — the same
     * cost-when-disabled bar the rest of the subsystem holds, but per-event rather than
     * global. Conditions are included because a turn signal can gate a DIFFERENT
     * trigger (e.g. "when speed &gt; 60 AND left indicator on"); if we polled only for
     * triggers, that condition would evaluate against a stale/unseeded turn state.
     *
     * <p>Memoized per key, because this IS a hot path while relevant rules are enabled: the fast
     * pollers can call it several times a second, and the walk below is
     * O(enabled x (conditions + whole action tree)) with per-node string scans. The memo is
     * cleared on every config mutation via {@link #refreshEnabledCount}, so a rule added or
     * disabled mid-session takes effect on the next tick.
     *
     * @param key the event to test
     * @return true if at least one enabled automation references it (trigger or condition)
     */
    public static boolean isEventReferenced(EventData key) {
        if (key == null || enabledCount == 0) return false;
        // Read the generation FIRST, then the entry: an entry stamped with this generation was
        // necessarily computed against the current config. Entries from an older generation are
        // ignored (and overwritten below), which is what makes a stale reinsertion by a slow
        // concurrent walk harmless rather than permanent.
        int gen = configGeneration;
        RefEntry cached = referenceCache.get(key);
        if (cached != null && cached.generation == gen) return cached.referenced;
        boolean referenced = computeEventReferenced(key);
        referenceCache.put(key, new RefEntry(gen, referenced));
        return referenced;
    }

    /**
     * The uncached walk behind {@link #isEventReferenced}. Kept as the single source of truth
     * for "what counts as a reference" — see Invariant 2b in
     * docs/AUTOMATION-PUBLISH-INVARIANTS.md. Extend THIS when adding a reference syntax.
     */
    private static boolean computeEventReferenced(EventData key) {
        for (Automation a : automations.values()) {
            if (a.isDisabled()) continue;
            if (a.isTriggered(key)) return true;
            // getAllConditions so an event used only inside a nested group still marks
            // this event as "referenced" — otherwise its fast-poll (e.g. turn signal)
            // would stay parked and the group condition would read stale state.
            for (AutomationCondition c : a.getAllConditions()) {
                if (key.equals(c.getEventData())) return true;
                // A condition's dynamic RIGHT-HAND SIDE can name a signal too
                // (${signal:TYPE[:k=v,…]} — AutomationCondition.resolveDynamic reads it from
                // this same state map at compare time). Without this, a key referenced ONLY as
                // an RHS keeps its self-gated poller parked, resolveDynamic reads null and the
                // condition silently evaluates false forever — e.g.
                // "temperature > ${signal:acSetpoint}". Mirrors the action-side "value" scan
                // below, including the allocation-free pre-filter.
                if (conditionValueReferences(c, key)) return true;
            }
            // ACTION-side references too. The flow actions (if/else, wait-until,
            // wait-until-signal, loop) test a signal from their own variables, NOT via a
            // trigger or condition — so without this a self-gated signal's poller stays
            // parked and the action reads a permanently-null state: an "if seatbelt ==
            // unbuckled" silently never holds, and a wait-until burns its whole timeout.
            // Walks nested child/else lists because these actions nest.
            if (actionsReference(a.getActions(), key)) return true;
            if (actionsReference(a.getElseActions(), key)) return true;
        }
        return false;
    }

    /**
     * The composite "either indicator" address (WaitUntilStateAction's sentinel). It is not a real
     * signal id, so it resolves to no key — the reference scan special-cases it to TURN_LEFT and
     * TURN_RIGHT, which is what it actually reads.
     */
    private static final String TURN_ANY_ID = "turnAny";

    /**
     * Does any action in this list (or nested beneath it) name {@code key} as an operand?
     *
     * <p>An action's operands are plain variable values, so this asks each value whether it
     * ADDRESSES the key — covering both a left-hand signal address ({@code speed:units=kmph},
     * and the legacy flat ids) and a dynamic right-hand {@code ${signal:…}} token, since both
     * make the action depend on that signal being polled.
     *
     * <p>Depth-bounded by the same constant the action runner uses, so a malformed/deep tree
     * can't make this walk unbounded on a scheduler thread.
     */
    private static boolean actionsReference(List<AutomationAction> actions, EventData key) {
        return actionsReference(actions, key, MAX_RUN_DEPTH);
    }

    /**
     * Does this condition's stored RHS name {@code key} as a dynamic signal reference?
     *
     * <p>Only a {@code ${signal:…}} token can; a plain constant (int, enum word) never does, and
     * a {@code ${var:…}} token addresses a user variable, not a vehicle signal. Same
     * conservative pre-filter as the action scan so this stays allocation-free on the common
     * path — the fast pollers call {@link #isEventReferenced} several times a second.
     */
    private static boolean conditionValueReferences(AutomationCondition c, EventData key) {
        if (c == null) return false;
        Object v = c.getValue();
        if (!(v instanceof String)) return false;
        String s = ((String) v).trim();
        // Only a dynamic reference can be an address; a plain constant never is. Deliberately
        // does NOT require the "signal" kind here — resolveSignalAddress trims inside the braces
        // and also accepts ${var:…} (→ a variable key, which simply won't equal a vehicle-signal
        // key). Pre-filtering on the exact "${signal:" prefix would reject "${ signal:x}", which
        // the resolver accepts — leaving the poller parked for a token that DOES resolve at
        // compare time. Matching the resolver's tolerance keeps the gate and the read in step.
        if (!s.startsWith("${") || !s.endsWith("}")) return false;
        if (!mightAddress(s, key.getType())) return false;
        return key.equals(AutomationCondition.resolveSignalAddress(s));
    }

    /**
     * Could this stored variable value possibly address a signal of {@code type}? A pure
     * allocation-free pre-filter for {@link #actionsReference} — see the note there.
     *
     * <p>Conservative by construction: it may say "maybe" for a value that turns out not to
     * match (the caller then does the real parse), but it never says "no" to a value that
     * would. A real address is {@code type}, {@code type:…} or {@code ${signal:type…}}, so the
     * type must appear in the string; a legacy alias doesn't contain its target type, so those
     * are admitted by name.
     */
    private static boolean mightAddress(String value, String type) {
        if (value.isEmpty() || type == null || type.isEmpty()) return false;
        if (value.contains(type)) return true;
        return AutomationCondition.isLegacySignalId(value.trim());
    }

    /** The token that unambiguously names a live signal inside interpolated free text. */
    private static final String SIGNAL_TOKEN = "${signal:";

    /**
     * Does any variable of this action embed a {@code ${signal:…}} token naming {@code key}?
     *
     * <p>Covers the interpolated free-text fields ({@code message}, {@code topic},
     * {@code payload}, API body values) that {@link TextInterpolator} resolves from the shared
     * state map. Scans every variable, but matches only the literal {@code ${signal:} prefix, so
     * a free-text word that merely coincides with a legacy signal alias can never wake a poller.
     *
     * <p>Allocation-free until a token is actually present: the {@code indexOf} pre-checks reject
     * ordinary text before anything is parsed, which matters because the fast pollers call
     * {@link #isEventReferenced} several times a second.
     */
    private static boolean anyValueEmbedsSignal(Map<String, Object> vars, EventData key) {
        if (vars == null || vars.isEmpty()) return false;
        String type = key.getType();
        if (type == null || type.isEmpty()) return false;
        for (Object v : vars.values()) {
            if (!(v instanceof String)) continue;
            String s = (String) v;
            // Both pre-checks are pure scans. A token for THIS key must contain the marker and
            // the type name; anything else can't match, so we never parse ordinary prose.
            int from = s.indexOf(SIGNAL_TOKEN);
            if (from < 0 || !s.contains(type)) continue;
            while (from >= 0) {
                int end = s.indexOf('}', from);
                if (end < 0) break;                       // unterminated token — nothing to match
                if (key.equals(AutomationCondition.resolveSignalAddress(s.substring(from, end + 1)))) {
                    return true;
                }
                from = s.indexOf(SIGNAL_TOKEN, end + 1);  // several tokens can share one string
            }
        }
        return false;
    }

    private static boolean actionsReference(List<AutomationAction> actions, EventData key, int depthLeft) {
        return actionsReference(actions, key, depthLeft, new java.util.HashSet<>());
    }

    private static boolean actionsReference(List<AutomationAction> actions, EventData key,
                                           int depthLeft, java.util.Set<String> visitedGroups) {
        if (actions == null || actions.isEmpty()) return false;
        // FAIL OPEN when the budget runs out. Returning "not referenced" here would park the key's
        // poller, so the signal reads null forever and every condition on it silently evaluates
        // false — a dead rule. Claiming "referenced" instead costs at most one unnecessary HAL poll.
        // Unreachable for childActions (parse caps those at MAX_ACTION_DEPTH < MAX_RUN_DEPTH), but
        // group chains are parsed independently and are not bounded, so this is a real path.
        if (depthLeft <= 0) {
            logger.warn("Signal reference scan hit the depth cap for " + key.getType()
                    + " — treating it as referenced so its poller keeps running");
            return true;
        }
        for (AutomationAction a : actions) {
            if (a == null) continue;
            Map<String, Object> vars = a.getVariables();
            if (vars == null) continue;
            // Keep these two field checks explicit. A static String[] declared below the class
            // initializer used to be null while refreshConditionalPollers() ran, so the enhanced
            // for-loop threw "Attempt to get length of null array" and every fast poller failed
            // to start after daemon boot.
            if (actionFieldReferences(vars, "event", key)
                    || actionFieldReferences(vars, "value", key)) return true;
            // FREE-TEXT fields (notification message, MQTT topic/payload, API body) are
            // interpolated by TextInterpolator, which resolves an EMBEDDED ${signal:…} token out
            // of this same state map. Those fields are not the explicit event/value addresses, so
            // without this an owned key referenced only from message text would keep its poller
            // parked and interpolate to the literal placeholder. Unlike the bare-address case
            // above this scans every variable — safe here because we match only the unambiguous
            // "${signal:" token, never a loose word that happens to be a legacy alias.
            if (anyValueEmbedsSignal(vars, key)) return true;
            // An actionGroup is call-by-reference: its body lives in ActionGroups, not in this
            // tree, so without expanding it a signal referenced ONLY inside a group keeps its
            // self-gated poller parked — the key then reads null forever and the group's
            // "if <signal> …" silently never holds.
            //
            // Groups are NOT depth-bounded at parse time the way childActions are (each group is
            // parsed independently), and a cycle A→B→A is only broken at run time by
            // ActionGroupAction's per-thread stack, which this walk does not have. So carry a
            // visited-id set: it terminates a cycle, and re-entering a group already on the path
            // adds no new references. If the walk still cannot finish, fail OPEN (below) — a gate
            // that fails closed silently parks the poller and kills the rule outright.
            if ("actionGroup".equals(a.getType())) {
                Object gidObj = vars.get("groupId");
                String gid = gidObj == null ? null : gidObj.toString().trim();
                if (gid != null && !gid.isEmpty() && visitedGroups.add(gid)) {
                    boolean found = actionsReference(
                            ActionGroups.getActions(gid), key, depthLeft - 1, visitedGroups);
                    visitedGroups.remove(gid);
                    if (found) return true;
                }
            }
            if (actionsReference(a.getChildActions(), key, depthLeft - 1, visitedGroups)) return true;
            if (actionsReference(a.getElseChildActions(), key, depthLeft - 1, visitedGroups)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one of the only two action fields that may hold a signal address references
     * {@code key}. Keeping this as direct field access avoids static-initialization ordering
     * entirely while preserving the allocation-free fast path.
     */
    private static boolean actionFieldReferences(
            Map<String, Object> vars, String field, EventData key) {
        Object v = vars.get(field);
        if (!(v instanceof String)) return false;
        String s = (String) v;
        // The "turnAny" sentinel is a COMPOSITE address: WaitUntilStateAction reads both
        // TURN_LEFT and TURN_RIGHT for it, but the string resolves to neither, so the
        // resolver below cannot see it. Both keys are FAST_POLL_OWNED, so missing this
        // parks their only publisher and the wait reads null on both sides forever.
        if (TURN_ANY_ID.equals(s.trim())
                && (com.overdrive.app.automation.condition.BydEvent.TURN_LEFT.equals(key)
                    || com.overdrive.app.automation.condition.BydEvent.TURN_RIGHT.equals(key))) {
            return true;
        }
        // Cheap reject before allocating. This runs on the fast pollers (the dynamics
        // poll calls isEventReferenced 3x every 250ms), and parsing an address builds a
        // fresh EventData plus a HashMap for the attributed case — avoid that for the
        // overwhelmingly common "this value can't name this key" case.
        return mightAddress(s, key.getType())
                && key.equals(AutomationCondition.resolveSignalAddress(s));
    }

    /**
     * Create or update an automation
     * Will use a UUID for new automations
     *
     * @param id         The id of an existing automation or null if a new automation is needed
     * @param automation The automation to add to the map
     * @return true if the change was also PERSISTED; false when the in-memory map was updated but
     *         the write failed (full filesystem, read-only mount), so a caller can report the
     *         failure instead of confirming a save that will vanish at the next boot
     */
    public static boolean updateAutomation(String id, Automation automation) {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        final String automationId = id;
        AutomationQueue.runConfigurationMutation(() -> {
            synchronized (SAVE_LOCK) {
                automations.put(automationId, automation);
                refreshEnabledCount();
            }
        });
        boolean persisted = saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info("Updated automation: " + id + (persisted ? "" : " (NOT PERSISTED)"));
        return persisted;
    }

    /**
     * Create or update an automation from a JSON representation
     *
     * @param id   The id for this automation or null if a new automation is needed
     * @param json The JSON representation of this automation
     * @return true if successfully created/updated, false when the JSON is invalid. NOTE: this
     *         reports REGISTRATION, not durability — a caller that must distinguish a failed
     *         write should use {@link #updateAutomation(String, Automation)}, whose result is
     *         the persistence outcome.
     */
    public static boolean updateAutomation(String id, JSONObject json) {
        Automation automation = Automation.fromJson(json);
        if (automation == null) return false;
        updateAutomation(id, automation);
        return true;
    }

    /**
     * Bulk-import automations from an exported map (id → automation JSON), the exact
     * shape {@link #toJson} produces. Each entry is validated via {@link Automation#fromJson}
     * (same gate as a single create), so a malformed entry is skipped, never persisted.
     *
     * @param json    the exported {id: automation} map
     * @param replace true = replace the whole set (clear first); false = merge (add/overwrite by id)
     * @return the number of automations successfully imported (validated + stored)
     */
    public static int importAutomations(JSONObject json, boolean replace) {
        if (json == null) return 0;
        // Validate ALL entries first so a partial/garbage file can't half-wipe the set:
        // we only clear (replace mode) once we know we have a valid parse to install.
        java.util.LinkedHashMap<String, Automation> parsed = new java.util.LinkedHashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Automation a = Automation.fromJson(json.optJSONObject(key));
            if (a != null) {
                // Mint a fresh id for a blank/duplicate-on-merge key so an import can't
                // collide with or silently overwrite an unrelated existing automation.
                String id = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
                parsed.put(id, a);
            }
        }
        if (parsed.isEmpty()) return 0;
        AutomationQueue.runConfigurationMutation(() -> {
            synchronized (SAVE_LOCK) {
                if (replace) automations.clear();
                automations.putAll(parsed);
                refreshEnabledCount();
            }
        });
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info("Imported " + parsed.size() + " automations (replace=" + replace + ")");
        return parsed.size();
    }

    /**
     * Delete an automation with a specific id
     * Only persists and re-evaluates the worker state when a mapping actually existed, so a delete of
     * an unknown id is a true no-op (no needless file write / worker churn) and the caller can report
     * a 404 instead of a misleading success.
     *
     * @param id The id of the automation to delete
     * @return true if an automation was actually removed, false if no mapping existed for this id
     */
    public static boolean deleteAutomation(String id) {
        final boolean[] removed = {false};
        AutomationQueue.runConfigurationMutation(() -> {
            synchronized (SAVE_LOCK) {
                removed[0] = automations.remove(id) != null;
                if (removed[0]) refreshEnabledCount();
            }
        });
        if (!removed[0]) return false;
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info("Removed automation: " + id);
        return true;
    }

    /**
     * Disable an automation
     *
     * @param id       The id of the automation to disable
     * @param disabled true if it should be disabled, false otherwise
     * @return true if successfully disabled, false otherwise
     */
    public static boolean disableAutomation(String id, boolean disabled) {
        final boolean[] updated = {false};
        AutomationQueue.runConfigurationMutation(() -> {
            synchronized (SAVE_LOCK) {
                Automation automation = automations.get(id);
                if (automation == null) return;
                automation.setDisabled(disabled);
                refreshEnabledCount();
                updated[0] = true;
            }
        });
        if (!updated[0]) return false;
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info((disabled ? "Disabled" : "Enabled") + " automation: " + id);
        return true;
    }

    /**
     * Set an automation's execution mode without changing its rule definition.
     * Automatic participates in event monitoring; manual participates only in
     * explicit Run now / key-mapping calls; disabled participates in neither.
     *
     * @return true when the id exists and the mode was applied
     */
    public static boolean setAutomationMode(String id, String mode) {
        if (!Automation.isValidMode(mode)) return false;
        final boolean[] updated = {false};
        AutomationQueue.runConfigurationMutation(() -> {
            synchronized (SAVE_LOCK) {
                Automation automation = automations.get(id);
                if (automation == null) return;
                automation.setMode(mode);
                refreshEnabledCount();
                updated[0] = true;
            }
        });
        if (!updated[0]) return false;
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info("Set automation mode to " + mode + ": " + id);
        return true;
    }

    /** Whether the id currently names a manual-only automation. */
    public static boolean isManualOnly(String id) {
        Automation automation = automations.get(id);
        return automation != null && automation.isManualOnly();
    }

    /**
     * Flip an automation's enabled state (for the AUTOMATION_CONTROL "toggle" action).
     * Returns false if the id is unknown.
     */
    public static boolean toggleAutomation(String id) {
        Automation automation = automations.get(id);
        if (automation == null) return false;
        return disableAutomation(id, !automation.isDisabled());
    }

    /**
     * Whether an automation with this id exists (for the automation-control action's
     * unknown-target guard).
     */
    public static boolean automationExists(String id) {
        return automations.containsKey(id);
    }

    /**
     * Lightweight [{id, name}] list for the automation-control target picker. {@code name}
     * is the user-given name, or a short generated fallback ("Automation <8-char id>")
     * when unnamed, so the picker is never blank. Excludes {@code selfId} (an automation
     * shouldn't target itself in the picker) when provided.
     */
    public static JSONArray listForPicker(String selfId) {
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, Automation> e : automations.entrySet()) {
                if (selfId != null && selfId.equals(e.getKey())) continue;
                String nm = e.getValue().getName();
                if (nm.isEmpty()) {
                    String k = e.getKey();
                    nm = "Automation " + (k.length() > 8 ? k.substring(0, 8) : k);
                }
                arr.put(new JSONObject().put("id", e.getKey()).put("name", nm));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    /**
     * The schema containing allowed values and descriptions for an automation
     *
     * @return The JSON schema for an automation
     */
    public static JSONArray schemaJson() {
        JSONArray json = conditions().toJson();

        try {
            JSONObject delayJson = delay().toJson();
            delayJson.put(
                    "description", Messages.get("automation.delay_description"));
            json.put(delayJson);
            json.put(actions().toJson());
            // Optional "else" branch — same action catalog, required 0. Emitted as its
            // own schema section so the existing schema-driven form renders it with no
            // bespoke UI. Automations without an else branch simply leave it empty.
            json.put(actions().elseToJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * The JSON for all the stored automations
     * Can be stored to load later
     *
     * @return JSON for all the stored automations
     */
    public static JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                json.put(automation.getKey(), automation.getValue().toJson());
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Bounded, read-only rule definitions plus current condition values for an
     * explicit assistant diagnosis. Action parameters are intentionally omitted.
     */
    public static JSONObject diagnosticContext(String query) {
        return AutomationDiagnostics.build(automations, conditionState, query);
    }

    /**
     * Persist the automations to a file.
     * <p>
     * Durable + concurrency-safe: the whole snapshot-then-write runs under {@link #SAVE_LOCK} so
     * concurrent API-thread saves can't interleave, and the bytes are written to a scratch
     * {@code .tmp} file then atomically {@code renameTo}'d over the live file (rename is atomic on the
     * same filesystem). Before promoting the scratch file, the current good live file is copied to a
     * {@code .bak} last-known-good. A crash therefore leaves at most a stale-but-valid live file or a
     * recoverable {@code .bak}; it can never leave a half-written live file that wipes all automations.
     */
    public static boolean saveToFile() {
        synchronized (SAVE_LOCK) {
            if (!AUTOMATION_HOME.exists()) AUTOMATION_HOME.mkdirs();
            // Snapshot to bytes under the lock so the persisted content is internally consistent.
            byte[] bytes = toJson().toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(AUTOMATION_TMP)) {
                fos.write(bytes);
                fos.getFD().sync();
            } catch (IOException e) {
                logger.error("Failed to write automations scratch file");
                return false;
            }
            // Promote the existing good file to the backup before replacing it, so a failure while the
            // live file is momentarily gone still leaves a recoverable copy. Only a file that PARSES
            // may become the backup: the live file is the thing suspected of being corrupt (that is
            // why .bak exists), and copying it blind destroys the last known good — after which a
            // second interruption loses every automation.
            if (AUTOMATION_CONFIG.exists() && isParseable(AUTOMATION_CONFIG)) {
                copyFile(AUTOMATION_CONFIG, AUTOMATION_BACKUP);
            }
            if (!AUTOMATION_TMP.renameTo(AUTOMATION_CONFIG)) {
                logger.error("Failed to promote automations scratch file to live config");
                return false;
            }
            logger.info("Saved " + automations.size() + " Automations to " + AUTOMATION_CONFIG);
            return true;
        }
    }

    /** Whether a file holds parseable JSON, so it is fit to become the last-known-good backup. */
    private static boolean isParseable(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            logger.warn("Live automation config is unparseable — keeping the existing backup");
            return false;
        }
    }

    /**
     * Copy a file's bytes. Best-effort; failures are logged but not fatal (the backup is a safety net,
     * not the source of truth).
     */
    private static void copyFile(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (IOException e) {
            logger.error("Failed to back up automations config");
        }
    }

    /**
     * Load persisted automations from the file, falling back to the last-known-good backup when the
     * live file is missing or corrupt. Runs under {@link #SAVE_LOCK} so it can't observe a live file
     * mid-rename.
     */
    public static void loadFromFile() {
        synchronized (SAVE_LOCK) {
            if (tryLoadFrom(AUTOMATION_CONFIG)) return;
            // Live file missing/corrupt — recover from the backup rather than silently starting empty.
            if (AUTOMATION_BACKUP.exists()) {
                if (tryLoadFrom(AUTOMATION_BACKUP)) {
                    // Repair the live file NOW. Leaving it corrupt keeps the system one
                    // interruption away from total loss, and until it is repaired every save
                    // must decline to refresh the backup, so the .bak ages indefinitely.
                    copyFile(AUTOMATION_BACKUP, AUTOMATION_CONFIG);
                    logger.info("Recovered automations from backup after live config was "
                            + "unreadable; restored the live config from it");
                    return;
                }
                logger.error("Both live and backup automation configs were unreadable");
            }
        }
    }

    /**
     * Attempt to load automations from a specific file.
     *
     * @param file The file to read
     * @return true if the file existed and parsed (automations populated), false if missing/corrupt
     */
    private static boolean tryLoadFrom(File file) {
        if (!file.exists()) return false;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            // Build into a scratch map first so a parse failure part-way can't leave the live map
            // half-populated on top of what was already loaded.
            Map<String, Automation> loaded = new java.util.HashMap<>();
            Iterator<String> keys = json.keys();
            int rejected = 0;
            while (keys.hasNext()) {
                String key = keys.next();
                Automation automation = Automation.fromJson(json.optJSONObject(key));
                if (automation != null) {
                    loaded.put(key, automation);
                } else {
                    // NAME the rejection. A rejected automation is dropped from the live
                    // map, and the next stats save rewrites the file without it — so a
                    // config written by a NEWER build (an action id or enum option this
                    // build lacks) loses that automation permanently. Silent before, which
                    // made it undiagnosable after the fact.
                    rejected++;
                    JSONObject bad = json.optJSONObject(key);
                    logger.warn("Rejected automation '" + key + "'"
                        + (bad != null ? " (name=" + bad.optString("name", "?") + ")" : "")
                        + " — unknown action/trigger/condition or invalid value."
                        + " It will be dropped from automations.json on the next save.");
                }
            }
            if (rejected > 0) {
                logger.warn("Dropped " + rejected + " automation(s) on load from " + file
                    + " — back up automations.json before this is overwritten.");
            }
            // Replace, don't merge: the file is the source of truth. Merging would resurrect an
            // automation deleted since the last load if this is ever wired to a runtime reload
            // (config-restore / OTA); replacement keeps the in-memory map exactly matching the file.
            automations.keySet().retainAll(loaded.keySet());
            automations.putAll(loaded);
            refreshEnabledCount();
            logger.info("Loaded " + loaded.size() + " Automations from " + file);
            return true;
        } catch (Exception e) {
            logger.error("Failed to load automations from " + file);
            return false;
        }
    }

    /**
     * Method to call when an event caused a value in the state to change (the new value has already been
     * committed to the state map by {@link #update}).
     * Will check all automations which contain this event as a trigger.
     * If the previous value is unknown, the event will not be triggered as the value may not have changed.
     * For this reason, events should fire at least once at startup to fill unknown values in the state.
     *
     * @param key      The event key
     * @param oldValue The value of the event before this change
     */
    private static void stateChanged(EventData key, Value oldValue) {
        // Don't trigger events when we don't know the previous value
        if (oldValue != null) {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                Automation a = automation.getValue();
                if (!a.isDisabled() && a.isTriggered(key)) {
                    // Enqueue when the conditions are met, OR when they aren't but an
                    // else branch exists — the else branch must fire after the same
                    // delay. The final decision (primary vs else) is re-made at fire
                    // time in triggerActions, so a condition that flips during the
                    // delay window is honoured. Only when conditions currently fail
                    // AND there is no else branch do we remove any pending item.
                    if (a.conditionsMet(conditionState) || a.hasElseActions()) {
                        logger.info("Adding automation to queue: " + automation.getKey());
                        AutomationQueue.addToQueue(automation.getKey(), a.getDelay());
                    } else {
                        logger.info("Removing automation from queue: " + automation.getKey());
                        AutomationQueue.removeFromQueue(automation.getKey());
                    }
                }
            }
        }
    }

    /**
     * Read the current value of an event from the live automation state, or null if
     * the event has never fired since boot. Exposed for the {@code waitUntil} action,
     * which polls a signal's current value while running (on the single automation
     * worker thread) — it must see the SAME committed state the trigger/condition
     * evaluation sees, so it reads this shared map rather than a private copy.
     *
     * @param key the event to read
     * @return the current {@link Value}, or null if unseen or expired
     */
    /**
     * Whether {@code value} is exactly what was last DELIVERED to triggers for {@code key} — i.e.
     * the user's rules have already run for it and nothing else has been delivered since.
     *
     * <p>Exists so an edge publisher can make a REPLAY idempotent without keeping its own shadow
     * copy of delivery state. A private latch cannot work here: the snapshot path can deliver a
     * different value in between (a grace-window yield delivering {@code off} after an {@code on}
     * edge), which strands the shadow copy and swallows the next genuine edge — the engine's mark
     * is the only thing that sees both publishers (audit 2026-08).
     *
     * @return true when the key's last delivered value equals {@code value}
     */
    public static boolean isLastDelivered(EventData key, String value) {
        if (key == null || value == null) return false;
        synchronized (STATE_LOCK) {
            Value delivered = stateDelivered.get(key);
            return delivered != null
                    && !Boolean.TRUE.equals(delivered.compare(new StringValue(value), "neq"));
        }
    }

    public static Value getStateValue(EventData key) {
        if (key == null) return null;
        synchronized (STATE_LOCK) {
            if (isStateExpired(key, System.currentTimeMillis())) return null;
            return state.get(key);
        }
    }

    // Wall-clock deadline (0 = never) until which the snapshot path force-stores its values so the
    // editor can show live readings. Set by the /api/automations/state endpoint, which the editor
    // polls only while a signal picker is on screen; it lapses on its own so a closed editor
    // costs nothing.
    private static volatile long editorSeedUntilMs = 0L;

    /** How long one editor poll keeps the seed window open — a few poll periods of slack. */
    private static final long EDITOR_SEED_WINDOW_MS = 15_000L;

    /**
     * Open (or extend) the seed window. Called by the live-state endpoint before it reads, so the
     * next telemetry snapshot stores its values even with no automation enabled.
     */
    public static void markEditorSeedActive() {
        editorSeedUntilMs = System.currentTimeMillis() + EDITOR_SEED_WINDOW_MS;
    }

    /**
     * Whether the editor is currently asking for live values. Publishers consult this to decide
     * whether to force-store while {@link #isDisabled()} — see {@code BydEvent.bydEvent}. It is
     * ONLY a store permission: firing still obeys Invariant 0, so seeding can never run a rule.
     */
    public static boolean editorSeedActive() {
        long until = editorSeedUntilMs;
        return until != 0L && System.currentTimeMillis() < until;
    }

    /**
     * A READ-ONLY snapshot of the live signal state, for the editor's "what does this read right
     * now?" hints. Keyed by the same {@code ${signal:…}} address the UI already emits — bare
     * {@code type} when the event has no variables, {@code type:k=v,…} when it does — so the
     * client can look a signal up by the exact token it stores, with no key-shape translation.
     *
     * <p>Expired entries are OMITTED rather than reported stale: an expiring signal that has aged
     * out reads null for conditions (see {@link #getStateValue}), so showing its last number would
     * tell the user a rule will match when it cannot.
     *
     * <p>Values are emitted with their natural JSON type (int stays a number, string stays a
     * string) so the UI can format without parsing. Variable keys are sorted so the token is
     * stable across calls.
     */
    public static JSONObject stateSnapshotJson() {
        JSONObject json = new JSONObject();
        long now = System.currentTimeMillis();
        synchronized (STATE_LOCK) {
            for (Map.Entry<EventData, Value> e : state.entrySet()) {
                EventData key = e.getKey();
                if (key == null || e.getValue() == null) continue;
                if (isStateExpired(key, now)) continue;
                Object raw = (e.getValue() instanceof BaseValue)
                        ? ((BaseValue<?>) e.getValue()).getValue()
                        : e.getValue().toString();
                if (raw == null) continue;
                try {
                    json.put(signalAddress(key), raw);
                } catch (Exception ignored) {
                    // JSONObject.put only throws on a null key, which signalAddress can't return.
                }
            }
        }
        return json;
    }

    /** The {@code ${signal:…}} inner address for an event key: {@code type} or {@code type:k=v,…}. */
    private static String signalAddress(EventData key) {
        Map<String, String> vars = key.getVariables();
        if (vars == null || vars.isEmpty()) return key.getType();
        StringBuilder sb = new StringBuilder(key.getType()).append(':');
        boolean first = true;
        for (String k : new java.util.TreeSet<>(vars.keySet())) {
            if (!first) sb.append(',');
            sb.append(k).append('=').append(vars.get(k));
            first = false;
        }
        return sb.toString();
    }

    /** Must be called with {@link #STATE_LOCK} held. */
    private static boolean isStateExpired(EventData key, long now) {
        Long expiresAt = stateExpiresAt.get(key);
        return expiresAt != null && now > expiresAt;
    }

    /**
     * Update the value in the state with a new value
     * Uses the Not Equal To comparator to see if the value has changed.
     * The commit is atomic on the state map: only the single thread that actually transitions the stored
     * value proceeds to evaluate automations, so two telemetry callback threads observing the same old
     * value for one logical change cannot both fire (which would double-run a vehicle-control action).
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Value value) {
        update(key, value, false);
    }

    /**
     * Core state update.
     *
     * @param forceStore when true, the value is STORED into the state map even while no
     *     automation is enabled ({@link #isDisabled()}). This is used ONLY for externally
     *     relayed signals (btState / btDeviceName / callState — see
     *     {@link #publishExternalEvent}): the app-process relay dedups on its side and stops
     *     re-sending once it has pushed a value, so if the daemon dropped that value while
     *     disabled, a CONDITION evaluated after the user later enables their first rule (or
     *     after a daemon-only restart clears this map) would read {@code null} instead of the
     *     real connection state. Forcing the store keeps the map truthful; triggers are still
     *     only fired when enabled (below), and since triggers are edge-based, seeding a value
     *     while disabled can never retroactively misfire a rule. Telemetry updates pass
     *     {@code false} so the hot-path {@link #isDisabled()} short-circuit is preserved.
     */
    public static void update(EventData key, Value value, boolean forceStore) {
        update(key, value, forceStore, null);
    }

    /**
     * Publish an integer state whose visibility expires at an absolute wall-clock time.
     * Expiration hides the value from conditions and state readers without deleting the raw
     * observation, so a later changed reading still produces the normal transition.
     */
    public static void updateExpiring(EventData key, Integer value, long expiresAtMs) {
        if (value == null) return;
        update(key, new IntValue(value), false, expiresAtMs);
    }

    /**
     * Hide a previously stored state without deleting its transition history. A later valid
     * publication clears this expiration normally, so capability discovery cannot manufacture
     * a trigger edge merely by changing from unknown to supported.
     */
    public static void expireState(EventData key) {
        if (key == null) return;
        synchronized (STATE_LOCK) {
            if (state.containsKey(key)) stateExpiresAt.put(key, 0L);
        }
    }

    /**
     * Publish a value for an OBSERVED transition — the caller is an edge handler that
     * witnessed the state change happen (e.g. the ACC IPC edge), not a poller sampling
     * current state. Identical to {@link #update(EventData, String)} except in two cases
     * that only exist right after a daemon start:
     *
     * <ul>
     *   <li>the key is UNSEEDED — a sampled first value is a seed and stays silent, but an
     *       observed first value is a real transition and fires;</li>
     *   <li>the key was already seeded with the SAME value by a racing snapshot — a sampled
     *       repeat dedups, but the observed edge still fires (the snapshot merely beat the
     *       edge handler to the map; the transition still happened).</li>
     * </ul>
     *
     * Without this, whether a boot-time edge fired depended on a startup race between the
     * telemetry seed and the edge handler (2026-08-09 field log: ACC ON at 15:20 fired the
     * power rules because a junk zero-snapshot seeded "off" first; the identical ACC ON at
     * 16:19 was silent because the probe won the race and the edge became a seed).
     *
     * <p><b>Exactly-once across publishers:</b> the fire decision is atomic with the store
     * (see {@code stateDelivered}), so an observed edge fires only when its value has not
     * already been DELIVERED to triggers — a sampled transition that beat this handler to the
     * same value suppresses the re-fire, a silent same-value seed does not, and a duplicate
     * edge publish (a heartbeat that slipped the caller's dedup) is a no-op. A publisher that
     * merely samples state (pollers, snapshots, relays that re-push current state) must keep
     * using {@link #update(EventData, String)} — routing a sampler through here would turn
     * its first post-restart sample into a fire.
     *
     * <p>Stores even while no automation is enabled (same rationale as forceStore in
     * {@link #update(EventData, Value, boolean)}): the edge will not be re-delivered, so the
     * map must stay truthful for conditions evaluated after the user enables a rule. Trigger
     * evaluation remains gated on enabled-ness.
     */
    public static void updateObservedEdge(EventData key, String value) {
        update(key, new StringValue(value), true, null, true);
    }

    /**
     * Re-state a value that an observed edge ALREADY delivered for this same physical event:
     * store it (so conditions and the editor read the precise level) without running triggers,
     * and — critically — WITHOUT moving the delivery mark off the edge's value.
     *
     * <p>Needed because the two {@code power} publishers have different vocabulary widths. The ACC
     * edge says only {@code off}/{@code on}; the bodywork snapshot also reports {@code acc}. While
     * an edge latch is live the snapshot keeps re-publishing the edge's own word, and letting that
     * take the ordinary path is harmless on its own — but once a genuine {@code acc} has been
     * delivered in between (a driver sitting in accessory mode), the later re-publish of
     * {@code off} is a real transition against a mark that now reads {@code acc}, so
     * "when power turns off" ran a SECOND time for one key turn. Keeping the mark pinned to the
     * edge's value is what makes that idempotent.
     *
     * <p>Not a general-purpose entry point: it is correct only when a DIFFERENT publisher is
     * guaranteed to have already delivered this exact value. Routing an ordinary signal through
     * here would silence its real edges.
     */
    public static void updateEdgeRestatement(EventData key, String value) {
        update(key, new StringValue(value), true, null, false, true);
    }

    /**
     * Atomic raw-state and expiration-overlay update. A null expiration makes the value
     * non-expiring and clears any prior overlay even when the raw value is unchanged.
     */
    private static void update(EventData key, Value value, boolean forceStore, Long expiresAtMs) {
        update(key, value, forceStore, expiresAtMs, false, false);
    }

    private static void update(EventData key, Value value, boolean forceStore, Long expiresAtMs,
                               boolean observedEdge) {
        update(key, value, forceStore, expiresAtMs, observedEdge, false);
    }

    private static void update(EventData key, Value value, boolean forceStore, Long expiresAtMs,
                               boolean observedEdge, boolean edgeRestatement) {
        if (key == null || value == null) return;
        boolean disabled = isDisabled();
        boolean silentSeed = SILENT_SEED.get();
        boolean forceLatestStateReplay = AutomationQueue.forceLatestStateReplay();
        // Hot path: a telemetry update with nothing listening does no work at all.
        //
        // ...EXCEPT while the editor is asking for live values. This early return is THE reason
        // every signal in the automation editor read "not reported yet on this car" whenever no
        // automation was enabled — which is exactly the state a user is in while building their
        // FIRST rule. 71 of the 94 publish sites across the daemon call plain update(), so gating
        // here (rather than teaching each caller to force-store) is the only complete fix.
        //
        // This is a STORE permission only, never a firing one: `disabled` still forces
        // `fire = false` below, so seeding can never run an action. Guarded by
        // AutomationSeedInvariantTest.
        if (disabled && !forceStore && !forceLatestStateReplay
                && !editorSeedActive() && !EXPLICIT_RUN.get() && !silentSeed) return;

        // Atomic commit: store the value AND decide delivery under one lock, so exactly one
        // publisher can claim delivery of any given value (see stateDelivered). Only the
        // stateChanged call itself runs outside the lock — it enqueues into AutomationQueue,
        // whose own monitor must never nest inside STATE_LOCK.
        Value[] previous = new Value[1];
        boolean fire;
        Value oldForEdge;
        synchronized (STATE_LOCK) {
            Value committed = state.compute(key, (k, current) -> {
                previous[0] = current;
                if (current == null || Boolean.TRUE.equals(current.compare(value, "neq"))) {
                    return value; // transition — store the new value
                }
                return current; // unchanged — leave as-is
            });
            if (expiresAtMs == null) {
                stateExpiresAt.remove(key);
            } else {
                stateExpiresAt.put(key, expiresAtMs);
            }
            // We transitioned iff the stored value is now the new value AND it differs from what
            // was there. The delivery rules:
            //
            // SEED INVARIANT (do not weaken): a SAMPLED first value (previous == null) is a
            // startup seed, not an edge — stored, marked undelivered, and silent. Firing seeds
            // made every signal's first publish after a daemon start run trigger evaluation,
            // which fired a burst of unrelated automations (WiFi/Bluetooth/gear/…) on every car
            // power-on (v37 field reports).
            //
            // OBSERVED EDGE (updateObservedEdge): the caller witnessed the transition, so it
            // fires even when it is the first value, or when a racing sampler already SEEDED the
            // same value — but NOT when this exact value was already DELIVERED (a sampled
            // transition beat the edge handler to it, or a duplicate edge publish slipped the
            // caller's dedup). That makes delivery exactly-once per logical edge across both
            // publishers, in every ordering.
            //
            // RETAINED REPLAY (forceLatestStateReplay) has NO independent firing power — it only
            // widens the disabled-store guard above so a replayed publication can seed the map.
            // A replay re-RUNS its original publish calls, and only those that are OBSERVED
            // edges (publishPowerEdge → updateObservedEdge) may fire without a transition; a
            // replayed SAMPLED publication (lock is poll-derived) seeds/dedups exactly like the
            // original would have. Letting a replay fire sampled values violated Invariant 0:
            // the queue retains a publication on any config change mid-publication, so CREATING
            // a rule could replay a seeded, never-delivered lock value straight into the new
            // rule — actuating it the moment it was saved, with no lock event having happened.
            // The delivered-value dedup then makes observed-edge delivery exactly-once: a
            // replay of an edge that never reached triggers (published while disabled) fires,
            // a replay of an already-delivered edge is a no-op instead of double-running the
            // user's actions.
            boolean transitioned = committed == value && previous[0] != value;
            // Whether this exact value is ALREADY the one delivered to triggers. Checked for BOTH
            // publisher kinds, and for transitions as well as repeats — not just for a repeat of
            // an observed edge as it used to be.
            //
            // Why a TRANSITION must consult it too: a publisher can move the stored value away
            // and back within one physical event (`power` settles off→acc→off as the key
            // rotates), which makes the return leg look like a fresh transition even though
            // triggers already ran for that value. Keying delivery off the VALUE rather than off
            // "did the stored value move" is what makes it exactly-once per logical value in every
            // ordering — including a broker-retained replay, a heartbeat, and a racing sampler.
            //
            // This cannot mute a toggling signal: the mark holds only the LAST delivered value, so
            // any alternation (open→closed→open, d→n→d) clears and re-arms it on each leg.
            //
            // The net effect versus the previous rule, verified exhaustively over every sequence of
            // (value × sampled/edge × enabled/disabled) up to length 4: delivery is IDENTICAL except
            // that a CONSECUTIVE repeat of the same delivered value is now collapsed to one. No
            // distinct value is ever lost. That collapse is the fix — a rule cannot meaningfully
            // act on "power became off" twice with no other value delivered in between.
            Value delivered = stateDelivered.get(key);
            boolean alreadyDelivered = delivered != null
                    && !Boolean.TRUE.equals(delivered.compare(value, "neq"));
            if (disabled || silentSeed) {
                fire = false; // store-only (forceStore/replay seeding); nothing enabled to run
            } else if (edgeRestatement) {
                // The edge already delivered this exact value for this event — store the level,
                // run nothing, and leave the mark pinned (see updateEdgeRestatement).
                fire = false;
            } else if (alreadyDelivered) {
                fire = false; // exactly-once: triggers have already seen this value
            } else if (transitioned) {
                fire = previous[0] != null || observedEdge;
            } else if (observedEdge) {
                fire = true; // repeat publish of a value never delivered (e.g. seeded silently)
            } else {
                fire = false;
            }
            if (fire) {
                stateDelivered.put(key, value);
            } else if (edgeRestatement) {
                // PRESERVE the existing mark rather than writing one. The point of a restatement is
                // that the stored value moves back to the edge's value without disturbing delivery
                // bookkeeping — so a later re-publish of it cannot read as a fresh transition and
                // re-run the rule.
                //
                // Deliberately NOT `put(key, value)`: that would ASSERT a delivery, and the edge
                // may never have delivered this value (it was published while automations were
                // disabled, or the feature was enabled only afterwards). Claiming it would suppress
                // the next genuine edge of that value for good. The map must never claim an
                // undelivered value — the same rule the branch below enforces. Falling through
                // without touching stateDelivered keeps whatever the truth already was.
            } else if (transitioned && !alreadyDelivered) {
                // The stored value moved to something genuinely UNdelivered (a seed, or a
                // disabled store): clear the stale mark so it can't suppress a later edge of a
                // different value, and so the map never claims an undelivered value.
                //
                // A move BACK to the already-delivered value is excluded: clearing there would
                // re-arm the very duplicate the check above exists to stop.
                stateDelivered.remove(key);
            }
            oldForEdge = previous[0] != null ? previous[0] : value;
        }
        if (fire) {
            stateChanged(key, oldForEdge);
        }
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, String value) {
        update(key, new StringValue(value));
    }

    /** String update that also seeds the state map while disabled (external relays only). */
    public static void update(EventData key, String value, boolean forceStore) {
        update(key, new StringValue(value), forceStore);
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Integer value) {
        update(key, new IntValue(value));
    }

    /** Integer update that also seeds the state map while disabled (editor live-value seeding). */
    public static void update(EventData key, Integer value, boolean forceStore) {
        update(key, new IntValue(value), forceStore);
    }

    /**
     * Publish an EXTERNAL event relayed from the app process (signals the daemon can't
     * observe itself). WHITELISTED: only the keys below are honoured, and each is
     * mapped to its curated {@link BydEvent} EventData with a validated value — so the
     * app→daemon bridge can never inject arbitrary automation state.
     *
     * @param event the external event key (e.g. "callState")
     * @param value the string value (validated per event)
     * @return true if the event was recognised and published
     */
    public static boolean publishExternalEvent(String event, String value) {
        if (event == null) return false;
        switch (event) {
            case "callState":
                // Only accept the three real telephony states; anything else is dropped
                // rather than published (no spurious edge on a garbled relay). forceStore:
                // the relay dedups on its side, so seed the map even while disabled (see
                // update(..., forceStore)) — otherwise a condition read after the user
                // enables their first call-state rule would see null.
                if ("idle".equals(value) || "ringing".equals(value) || "offhook".equals(value)) {
                    update(com.overdrive.app.automation.condition.BydEvent.CALL_STATE, value, true);
                    return true;
                }
                return false;
            case "btState":
                // Bluetooth connection edge, relayed from the app-process
                // BluetoothStateMonitor (the daemon can't reliably read BT from UID 2000).
                // Only the two real states; anything else is dropped (no spurious edge).
                // forceStore so the map is seeded even before the first BT rule is enabled
                // and survives a daemon-only restart (the relay won't re-send otherwise).
                if ("on".equals(value) || "off".equals(value)) {
                    update(com.overdrive.app.automation.condition.BydEvent.BT_STATE, value, true);
                    return true;
                }
                return false;
            case "btDeviceName":
                // Connected-device friendly name from the same relay ("" when nothing is
                // connected). Free-text (bounded) — a name can be any string, so the only
                // validation is a length cap; null is coerced to "" so a name-match
                // condition sees a stable value rather than "unseen". forceStore: same
                // rationale as btState.
                if (value == null) value = "";
                if (value.length() > 64) value = value.substring(0, 64);
                update(com.overdrive.app.automation.condition.BydEvent.BT_DEVICE_NAME, value, true);
                return true;
            default:
                return false;
        }
    }

    /**
     * Publish an INBOUND MQTT message as an automation signal. The daemon's MQTT
     * subscriber calls this when a broker message lands on {@code <base>/automation/<channel>}
     * (see MqttPublisherService), letting an external system (Home Assistant, Node-RED, …)
     * trigger an OverDrive automation. Distinct from {@link #publishExternalEvent}: the
     * channel is caller-defined, so this is its own guarded seam rather than a fixed
     * whitelist. The channel is validated (bounded, safe charset) so a malformed/hostile
     * topic can't inject arbitrary automation state; the value is bounded in length. The
     * value flows through {@link #update} (level-triggered: fires on a value transition),
     * so publishing distinct values (or toggling) drives repeated triggers.
     *
     * @param channel the channel segment from the topic (validated)
     * @param value   the message payload as a string (bounded)
     * @return true if accepted and published, false if the channel/value was rejected
     */
    public static boolean publishMqttTrigger(String channel, String value) {
        if (!isValidMqttChannel(channel)) return false;
        if (value == null) value = "";
        if (value.length() > 256) value = value.substring(0, 256);
        // Bound distinct-channel growth: update() inserts a permanent state entry per
        // channel, and the channel is external (broker-writable). A chatty/hostile broker
        // publishing to <base>/automation/<random-N> would otherwise grow the state map
        // unbounded. Cap the number of DISTINCT channels we'll ever seed; once at the cap,
        // only already-known channels continue to publish (a real automation watches a
        // fixed, small set of channels, so this never limits legitimate use). The set only
        // grows, mirroring update()'s own permanence — no pruning needed for a ≤cap set.
        if (mqttChannelsSeen.size() >= MAX_MQTT_CHANNELS && !mqttChannelsSeen.contains(channel)) {
            return false;
        }
        mqttChannelsSeen.add(channel);
        // OBSERVED-EDGE semantics: a broker message physically ARRIVED — this is a witnessed
        // event, not a sampled state. Routing it through the sampled path made the FIRST
        // message on a channel after a daemon restart a silent seed, so an MQTT-triggered
        // automation missed exactly one message per restart per channel. Paho delivers each
        // message once (per QoS contract), and the delivered-value dedup keeps a broker
        // RETAINED message replayed on reconnect from re-firing the same payload.
        updateObservedEdge(com.overdrive.app.automation.condition.BydEvent.mqttTrigger(channel),
                value);
        return true;
    }

    // Distinct inbound-MQTT channels ever seeded, capped so a broker can't grow the state
    // map without bound (see publishMqttTrigger). A concurrent set — publishMqttTrigger runs
    // on the Paho callback thread.
    private static final int MAX_MQTT_CHANNELS = 64;
    private static final java.util.Set<String> mqttChannelsSeen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A safe channel id: non-empty, &le;64 chars, [A-Za-z0-9._-] only (a single MQTT
     *  topic segment). Rejects wildcards/slashes so it maps to exactly one channel. */
    private static boolean isValidMqttChannel(String channel) {
        if (channel == null || channel.isEmpty() || channel.length() > 64) return false;
        for (int i = 0; i < channel.length(); i++) {
            char c = channel.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Cursor state for resumable automatic queue execution. Explicit Run now/keymap
     * execution intentionally does not use this cursor; it runs on the separate
     * serialized explicit worker.
     */
    static final class QueueActionCursor {
        private Automation automation;
        private long automationRevision;
        private java.util.List<AutomationAction> actions;
        private final java.util.IdentityHashMap<AutomationAction, Integer>
                completedOccurrences = new java.util.IdentityHashMap<>();
        private int nextAction;
        private boolean recorded;
        private AutomationAction deferredAction;
        private boolean deferredActionCompleted;
        private long resumeAtNanos;
        private AutomationAction pendingWaitAction;
        private long pendingWaitDeadlineNanos;
        // Ownership token for this execution. Assigned once (first attempt) and
        // restored into CURRENT_EXECUTION_TOKEN on every resumed attempt, so a
        // show fired before a deferred wait and a hide fired after it carry the
        // SAME token. 0 = not yet assigned.
        private long executionToken;

        boolean hasStarted() {
            return actions != null;
        }

        void beginAttempt() {
            deferredAction = null;
            deferredActionCompleted = false;
            resumeAtNanos = 0L;
        }

        boolean hasDeferredAction() {
            return deferredAction != null && resumeAtNanos != 0L;
        }

        boolean deferredActionCompleted(AutomationAction action) {
            return deferredAction == action && deferredActionCompleted;
        }

        long resumeAtNanos() {
            return resumeAtNanos;
        }
    }

    /**
     * Re-evaluate the current branch but execute only allowlisted idempotent vehicle state setters.
     * This is the sole retained/reconciliation replay path; notifications, API calls, shell
     * commands, variable mutations, pauses, and other arbitrary prefixes are skipped.
     */
    static boolean triggerQueuedStateSetters(String id) {
        Automation automation = automations.get(id);
        if (automation == null || automation.isDisabled()) return true;
        long automationRevision = automation.enabledStateRevision();
        boolean met = automation.conditionsMet(conditionState);
        java.util.List<AutomationAction> selected = met
                ? automation.getActions()
                : automation.hasElseActions()
                        ? automation.getElseActions()
                        : java.util.Collections.emptyList();
        if (!ownsEnabledDefinition(id, automation, automationRevision)) return true;
        boolean previous = STATE_SETTER_ONLY.get();
        Automation previousDefinition = ACTIVE_QUEUED_DEFINITION.get();
        String previousAutomationId = ACTIVE_QUEUED_AUTOMATION_ID.get();
        Long previousDefinitionRevision =
                ACTIVE_QUEUED_DEFINITION_REVISION.get();
        STATE_SETTER_ONLY.set(true);
        ACTIVE_QUEUED_DEFINITION.set(automation);
        ACTIVE_QUEUED_AUTOMATION_ID.set(id);
        ACTIVE_QUEUED_DEFINITION_REVISION.set(automationRevision);
        boolean successful = true;
        try {
            resetChain();
            for (AutomationAction automationAction : selected) {
                if (Thread.currentThread().isInterrupted()) return false;
                if (!ownsEnabledDefinition(
                        id, automation, automationRevision)) return true;
                try {
                    successful &= runActionList(
                            java.util.Collections.singletonList(automationAction));
                } catch (Throwable failure) {
                    logger.error("State-setter reconciliation threw: " + id);
                    successful = false;
                }
                if (!ownsEnabledDefinition(
                        id, automation, automationRevision)) return true;
                if (chainAborted()) break;
            }
            return successful && !Thread.currentThread().isInterrupted();
        } finally {
            STATE_SETTER_ONLY.set(previous);
            if (previousDefinition == null) {
                ACTIVE_QUEUED_DEFINITION.remove();
            } else {
                ACTIVE_QUEUED_DEFINITION.set(previousDefinition);
            }
            if (previousAutomationId == null) {
                ACTIVE_QUEUED_AUTOMATION_ID.remove();
            } else {
                ACTIVE_QUEUED_AUTOMATION_ID.set(previousAutomationId);
            }
            if (previousDefinitionRevision == null) {
                ACTIVE_QUEUED_DEFINITION_REVISION.remove();
            } else {
                ACTIVE_QUEUED_DEFINITION_REVISION.set(
                        previousDefinitionRevision);
            }
        }
    }

    static Object activeQueuedDefinitionIdentity() {
        return ACTIVE_QUEUED_DEFINITION.get();
    }

    static long activeQueuedDefinitionRevision() {
        Long revision = ACTIVE_QUEUED_DEFINITION_REVISION.get();
        return revision == null ? -1L : revision.longValue();
    }

    static boolean ownsEnabledDefinition(
            String id, Object expected, long expectedRevision) {
        if (id == null || !(expected instanceof Automation)) return false;
        Automation current = automations.get(id);
        return current == expected
                && !current.isDisabled()
                && current.enabledStateRevision() == expectedRevision;
    }

    private static boolean activeQueuedDefinitionIsCurrent() {
        Automation expected = ACTIVE_QUEUED_DEFINITION.get();
        if (expected == null) return true;
        Long expectedRevision = ACTIVE_QUEUED_DEFINITION_REVISION.get();
        return expectedRevision != null
                && ownsEnabledDefinition(
                        ACTIVE_QUEUED_AUTOMATION_ID.get(),
                        expected,
                        expectedRevision.longValue());
    }

    private static boolean ownsQueuedDefinition(String id, QueueActionCursor cursor) {
        return cursor != null
                && ownsEnabledDefinition(
                        id, cursor.automation, cursor.automationRevision);
    }

    /**
     * Queue-only resumable execution. A cancellation advances past every action that returned,
     * then reports incomplete so the queue can re-drive only the unvisited suffix.
     */
    static boolean triggerQueuedActions(String id, QueueActionCursor cursor) {
        if (cursor == null) return triggerActions(id, true);
        if (cursor.actions == null) {
            Automation automation = automations.get(id);
            if (automation == null) return true;
            if (automation.isDisabled()) {
                logger.info("Skipping disabled automation actions: " + id);
                return true;
            }
            long automationRevision = automation.enabledStateRevision();
            boolean met = automation.conditionsMet(conditionState);
            boolean runElse = !met && automation.hasElseActions();
            cursor.automation = automation;
            cursor.automationRevision = automationRevision;
            cursor.actions = met
                    ? automation.getActions()
                    : runElse ? automation.getElseActions() : java.util.Collections.emptyList();
            if (!ownsQueuedDefinition(id, cursor)) return true;
            if (!cursor.actions.isEmpty()) {
                logger.info("Triggering automation " + (runElse ? "else-" : "")
                        + "actions: " + id);
                automation.recordTriggered(System.currentTimeMillis());
                cursor.recorded = true;
            }
        } else if (!ownsQueuedDefinition(id, cursor)) {
            // Disable, deletion, or replacement cancels a partially executed old definition.
            return true;
        }

        cursor.beginAttempt();
        resetChain();
        // Ownership token: assign once per execution, restore on every attempt so
        // deferred-wait resumptions keep the same identity (see field comment).
        if (cursor.executionToken == 0L) {
            cursor.executionToken = EXECUTION_TOKEN_SEQ.incrementAndGet();
        }
        CURRENT_EXECUTION_TOKEN.set(cursor.executionToken);
        QUEUE_ACTION_CURSOR.set(cursor);
        QUEUE_ACTION_OCCURRENCES.set(new java.util.IdentityHashMap<>());
        ACTIVE_QUEUED_DEFINITION.set(cursor.automation);
        ACTIVE_QUEUED_AUTOMATION_ID.set(id);
        ACTIVE_QUEUED_DEFINITION_REVISION.set(cursor.automationRevision);
        try {
            while (cursor.nextAction < cursor.actions.size()) {
                if (Thread.currentThread().isInterrupted()) return false;
                if (!ownsQueuedDefinition(id, cursor)) return true;
                AutomationAction automationAction = cursor.actions.get(cursor.nextAction);
                Action action = automationAction == null
                        ? null : getAction(automationAction.getType());
                if (action == null) {
                    cursor.nextAction++;
                    continue;
                }
                int completedBefore =
                        cursor.completedOccurrences.getOrDefault(automationAction, 0);
                boolean actionSucceeded;
                try {
                    actionSucceeded = runActionList(
                            java.util.Collections.singletonList(automationAction));
                } catch (Throwable t) {
                    // The chain stops here and the item is treated as done (retrying would re-run a
                    // throwing action forever). Name the action and the actions that will therefore
                    // NEVER run: the old message gave only the automation id, so a half-applied
                    // chain — doors unlocked, tailgate never opened — looked like a clean run.
                    int skipped = cursor.actions.size() - cursor.nextAction - 1;
                    logger.error("Automation " + id + " aborted: action #"
                            + (cursor.nextAction + 1) + " (" + automationAction.getType()
                            + ") threw " + t.getClass().getSimpleName() + ": " + t.getMessage()
                            + (skipped > 0
                                    ? " — the remaining " + skipped + " action(s) were NOT run"
                                    : " — it was the last action"), t);
                    return true;
                }
                boolean actionBoundaryCompleted =
                        cursor.completedOccurrences.getOrDefault(automationAction, 0)
                                > completedBefore;
                if (!ownsQueuedDefinition(id, cursor)) return true;
                if (chainAborted()) {
                    cursor.nextAction = cursor.actions.size();
                    break;
                }
                if (cursor.hasDeferredAction()) {
                    if (actionBoundaryCompleted
                            && !action.hasChildActions()
                            && !"actionGroup".equals(automationAction.getType())) {
                        cursor.nextAction++;
                    }
                    return false;
                }
                if (Thread.currentThread().isInterrupted()) {
                    // A control-flow action may have returned with an unvisited nested suffix.
                    // Re-enter it; runActionList will skip its completed child occurrences.
                    if (actionBoundaryCompleted
                            && !action.hasChildActions()
                            && !"actionGroup".equals(automationAction.getType())) {
                        cursor.nextAction++;
                    }
                    return false;
                }
                if (!actionSucceeded) {
                    // A REFUSED action is not an aborted chain. Vehicle-control and HTTP-backed
                    // actions report routine failures honestly: unsupported hardware, a driving
                    // safety block, an endpoint refusal, rate limiting, or an unreachable vehicle.
                    //
                    // Returning here left the cursor parked and treated the item as done, so every
                    // LATER action in the chain was silently dropped — a rule whose first step was
                    // an unsupported control never sent its notification or ran its other steps,
                    // with nothing logged. It also diverged from the /test and non-queue paths,
                    // which run the whole list (runActionList only records the failure), so the
                    // same automation behaved differently under Test than in production.
                    //
                    // Advance past the refused action and keep going: that preserves the old
                    // whole-chain behaviour AND still can't hot-loop, because the cursor moves.
                    // ACTION_CHAIN_SUCCEEDED is already false, so the run is still reported failed.
                    int remaining = cursor.actions.size() - cursor.nextAction - 1;
                    logger.warn("Automation " + id + " action #" + (cursor.nextAction + 1)
                            + " (" + automationAction.getType() + ") did not succeed"
                            + (remaining > 0
                                    ? " — continuing with the remaining " + remaining + " action(s)"
                                    : " — it was the last action"));
                    cursor.nextAction++;
                    continue;
                }
                cursor.nextAction++;
            }
        } finally {
            ACTIVE_QUEUED_DEFINITION_REVISION.remove();
            ACTIVE_QUEUED_AUTOMATION_ID.remove();
            ACTIVE_QUEUED_DEFINITION.remove();
            QUEUE_ACTION_OCCURRENCES.remove();
            QUEUE_ACTION_CURSOR.remove();
            CURRENT_EXECUTION_TOKEN.remove();
        }
        if (cursor.recorded
                && (cursor.automation.getTriggerCount() % STATS_PERSIST_EVERY) == 0) {
            saveToFile();
        }
        return true;
    }

    /**
     * Automatic/queue execution entry point. Manual-only automations remain blocked
     * here exactly like disabled ones, so event delivery can never execute them.
     */
    public static boolean triggerActions(String id, boolean checkConditions) {
        return triggerActionsInternal(id, checkConditions, checkConditions, false);
    }

    /**
     * Explicit user execution entry point for Run now and manual-only key mappings.
     * Runs the primary branch without evaluating conditions, because the user action
     * itself is the trigger. Fully disabled rules remain inert.
     *
     * @param recordStats true for a real manual invocation (key mapping), false for Test
     */
    public static boolean triggerExplicitActions(String id, boolean recordStats) {
        return triggerActionsInternal(id, false, recordStats, true);
    }

    private static boolean triggerActionsInternal(
            String id, boolean checkConditions, boolean recordStats, boolean explicit) {
        Automation automation = automations.get(id);
        if (automation == null) return false;

        // Automatic work accepts only automatic mode. Explicit work additionally
        // accepts manual-only mode, but never a fully disabled automation.
        boolean blocked = explicit ? automation.isFullyDisabled() : automation.isDisabled();
        if (blocked) {
            logger.info("Skipping " + (explicit ? "fully disabled" : "non-automatic")
                    + " automation actions: " + id);
            return true;
        }

        // Decide which branch to run. The /test path (checkConditions=false) always
        // runs the PRIMARY actions so "test" exercises the happy path. The queue
        // worker (checkConditions=true) runs the primary branch when conditions are
        // met, otherwise the else branch (if any). Conditions are re-checked HERE, at
        // fire time, so a value that changed during the delay window is honoured.
        boolean met = !checkConditions || automation.conditionsMet(conditionState);
        boolean runElse = checkConditions && !met && automation.hasElseActions();
        if (met || runElse) {
            logger.info("Triggering automation " + (runElse ? "else-" : "") + "actions: " + id);
            // Guard the action run at the shared choke point that BOTH callers flow through: the
            // autonomous queue worker (AutomationQueue.java:135-139) already wraps its call in
            // catch(Throwable), but the /test endpoint (AutomationApiHandler.testAutomation) does not.
            // A misbehaving or null action element (reachable via the unchecked
            // actions.add(action.fromJson(...)) in Automation.fromJson) would otherwise let an NPE/Error
            // propagate out of the test call, up through handle() to HttpServer's catch(Exception) which
            // only logs and closes the socket in its finally block — leaving the client with no HTTP
            // response at all. Swallowing it here (mirroring the queue worker) means the caller still
            // gets a proper response and the daemon stays up. We return true (the automation EXISTS) so
            // the failure is never misreported as a 404 by callers that map false -> 404.
            // Record the fire (real triggers only — not the /test path). Bumps the
            // in-memory count + timestamp for the list "last fired / N times"; the value
            // is persisted lazily (below) rather than on every fire to avoid a disk
            // write per trigger on a hot automation.
            if (recordStats) automation.recordTriggered(System.currentTimeMillis());
            boolean previousExplicit = EXPLICIT_RUN.get();
            if (explicit) EXPLICIT_RUN.set(true);
            // Ownership token for the cursor-less automatic path (the resumable
            // cursor path assigns its own on the cursor). One call here is one
            // complete execution — no deferral survives it — so a fresh token per
            // call is correct. Explicit Run-now/key-mapping runs stay ownerless
            // (null) by design: their camview hide keeps global-close semantics.
            // Save/restore rather than remove, so a nested trigger can't clobber
            // an outer execution's token.
            Long previousToken = CURRENT_EXECUTION_TOKEN.get();
            if (!explicit) {
                CURRENT_EXECUTION_TOKEN.set(EXECUTION_TOKEN_SEQ.incrementAndGet());
            }
            try {
                if (runElse) automation.triggerElseActions();
                else automation.triggerActions();
            } catch (Throwable t) {
                logger.error("Automation action threw while triggering: " + id);
            } finally {
                EXPLICIT_RUN.set(previousExplicit);
                if (!explicit) {
                    if (previousToken != null) CURRENT_EXECUTION_TOKEN.set(previousToken);
                    else CURRENT_EXECUTION_TOKEN.remove();
                }
            }
            // Persist the bumped stats opportunistically: only every STATS_PERSIST_EVERY
            // fires (per automation) so a busy rule doesn't hammer the disk, while the
            // count still survives a restart with at most a few lost increments.
            if (recordStats && (automation.getTriggerCount() % STATS_PERSIST_EVERY) == 0) {
                saveToFile();
            }
        }
        return true;
    }

    // Persist run-stats to disk every N fires per automation (not every fire — that
    // would be a disk write per trigger on a hot rule). A restart loses at most N-1
    // increments of the display counter, which is acceptable for a cosmetic stat.
    private static final long STATS_PERSIST_EVERY = 10L;
    private static final Object STARTUP_ORDER_SENTINEL = new Object();
    private static final boolean startupSawInitializedStaticFields;

    static boolean startupSawInitializedStaticFieldsForTest() {
        return startupSawInitializedStaticFields;
    }

    /*
     * Keep this as the final static field/initializer in the class.
     *
     * Saved automations are loaded during class initialization, and their reference walk touches
     * helpers throughout this class. This initializer previously lived near the top, before later
     * non-constant static fields had run. ADDRESS_FIELDS was therefore still null when the startup
     * turn/gear reference checks executed, every conditional poller declined to start, and a
     * disable/enable mutation appeared to fix the rules because it refreshed them after <clinit>.
     *
     * Running startup last makes the ordering guarantee structural: future static helper state
     * declared above is initialized before any saved rule is scanned or any poller is scheduled.
     */
    static {
        startupSawInitializedStaticFields = STARTUP_ORDER_SENTINEL != null
                && RUN_DEPTH != null
                && mqttChannelsSeen != null;
        if (!startupSawInitializedStaticFields) {
            throw new ExceptionInInitializerError(
                    "Automation startup ran before static helper fields were initialized");
        }

        runIsolatedStartupStep("saved automation load", Automations::loadFromFile);
        runIsolatedStartupStep("action-group load", ActionGroups::loadFromFile);

        // NOTE: btState / btDeviceName are NOT polled here. Bluetooth can't be read
        // reliably from the daemon (UID 2000) — Bluetooth is only readable from a normal
        // app process, not the shell-UID daemon. The app-process BluetoothStateMonitor
        // watches ACL connect/disconnect and relays those events to the daemon via
        // Automations.publishExternalEvent, exactly like callState.

        // Door callbacks are event-driven and stay subscribed. Every periodic source below is
        // configuration-driven: no enabled reference means no scheduled task and no parked
        // thread waking just to discover that it has nothing to read.
        pollersReady = true;
        refreshConditionalPollers();
    }
}
