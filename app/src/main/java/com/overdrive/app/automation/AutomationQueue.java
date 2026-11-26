package com.overdrive.app.automation;

import com.overdrive.app.logging.DaemonLogger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AutomationQueue {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");

    /** Stable identities for state streams that require replay across disable/shutdown races. */
    public enum LatestStateStream {
        POWER,
        LOCK
    }

    private static class DelayedAutomation implements Delayed {
        private final String id;
        private final String queueKey;
        private long startTime;
        private final long sequence;
        private final Automations.QueueActionCursor actionCursor;
        private final java.util.List<StatefulCompensation> compensations;
        private int nextCompensation;
        private final boolean stateSetterOnly;
        private final java.util.EnumMap<CompensableStateSetter, StatefulCompensation>
                completedStatefulCompensations =
                new java.util.EnumMap<>(CompensableStateSetter.class);
        private final boolean urgent;
        private final java.util.EnumMap<LatestStateStream, LatestStateTag>
                latestStatePublicationGenerations =
                new java.util.EnumMap<>(LatestStateStream.class);
        // One run of the same automation triggered while this started chain was pending or
        // running; enqueued when this chain finishes. Guarded by lock.
        private DelayedAutomation heldRetrigger;

        /**
         * A queue with a delay for items
         * The delay is stored using System.nanoTime to avoid issues with timezones
         *
         * @param id    The id of an automation which will be run
         * @param delay The time in seconds to delay that actions of the automation
         */
        public DelayedAutomation(
                String id, int delay,
                LatestStateStream latestStatePublicationKey,
                long latestStatePublicationGeneration,
                long latestStatePublicationSequence,
                boolean urgent) {
            this(
                    id, id, delay,
                    latestStatePublicationKey,
                    latestStatePublicationGeneration,
                    latestStatePublicationSequence,
                    urgent);
        }

        private DelayedAutomation(
                String id, String queueKey, int delay,
                LatestStateStream latestStatePublicationKey,
                long latestStatePublicationGeneration,
                long latestStatePublicationSequence,
                boolean urgent) {
            this.id = id;
            this.queueKey = queueKey;
            this.startTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay);
            this.sequence = QUEUE_SEQUENCE.incrementAndGet();
            this.actionCursor = new Automations.QueueActionCursor();
            this.compensations = java.util.Collections.emptyList();
            this.stateSetterOnly = false;
            this.urgent = urgent;
            markLatestStatePublication(
                    latestStatePublicationKey,
                    latestStatePublicationGeneration,
                    latestStatePublicationSequence);
        }

        private DelayedAutomation(
                String id, java.util.Collection<StatefulCompensation> compensations,
                LatestStateStream latestStatePublicationKey,
                long latestStatePublicationGeneration,
                long latestStatePublicationSequence) {
            this.id = id;
            this.queueKey = "stateful-compensation:"
                    + COMPENSATION_SEQUENCE.incrementAndGet();
            this.startTime = System.nanoTime();
            this.sequence = QUEUE_SEQUENCE.incrementAndGet();
            this.actionCursor = null;
            this.compensations = new java.util.ArrayList<>(compensations);
            this.stateSetterOnly = false;
            this.urgent = true;
            markLatestStatePublication(
                    latestStatePublicationKey,
                    latestStatePublicationGeneration,
                    latestStatePublicationSequence);
        }

        private DelayedAutomation(
                String id, String queueKey,
                LatestStateStream latestStatePublicationKey,
                long latestStatePublicationGeneration,
                long latestStatePublicationSequence) {
            this.id = id;
            this.queueKey = queueKey;
            this.startTime = System.nanoTime();
            this.sequence = QUEUE_SEQUENCE.incrementAndGet();
            this.actionCursor = null;
            this.compensations = java.util.Collections.emptyList();
            this.stateSetterOnly = true;
            this.urgent = true;
            markLatestStatePublication(
                    latestStatePublicationKey,
                    latestStatePublicationGeneration,
                    latestStatePublicationSequence);
        }

        private DelayedAutomation(
                DelayedAutomation source,
                LatestStateStream latestStatePublicationKey,
                long latestStatePublicationGeneration,
                long latestStatePublicationSequence) {
            this.id = source.id;
            this.queueKey = source.queueKey;
            this.startTime = System.nanoTime();
            this.sequence = QUEUE_SEQUENCE.incrementAndGet();
            this.actionCursor = source.actionCursor;
            this.compensations = source.compensations;
            this.nextCompensation = source.nextCompensation;
            this.stateSetterOnly = source.stateSetterOnly;
            this.urgent = true;
            this.completedStatefulCompensations.putAll(
                    source.completedStatefulCompensations);
            this.latestStatePublicationGenerations.putAll(
                    source.latestStatePublicationGenerations);
            markLatestStatePublication(
                    latestStatePublicationKey,
                    latestStatePublicationGeneration,
                    latestStatePublicationSequence);
        }

        void markLatestStatePublication(
                LatestStateStream key, long generation, long publicationSequence) {
            if (key == null || generation == 0L) return;
            LatestStateTag existing = latestStatePublicationGenerations.get(key);
            if (existing == null || generation > existing.generation) {
                latestStatePublicationGenerations.put(
                        key, new LatestStateTag(generation, publicationSequence));
            }
        }

        boolean belongsToLatestStatePublication(LatestStateStream key, long generation) {
            LatestStateTag itemTag = latestStatePublicationGenerations.get(key);
            return itemTag != null && itemTag.generation == generation;
        }

        /**
         * The stored id for an automation
         *
         * @return The stored id for an automation
         */
        public String getId() {
            return id;
        }

        String getQueueKey() {
            return queueKey;
        }

        boolean isAutomation() {
            return actionCursor != null;
        }

        boolean isAutomationWork() {
            return actionCursor != null || stateSetterOnly;
        }

        boolean isStateSetterOnly() {
            return stateSetterOnly;
        }

        boolean hasStartedCursor() {
            return actionCursor != null && actionCursor.hasStarted();
        }

        java.util.List<StatefulCompensation> remainingCompensations() {
            if (nextCompensation >= compensations.size()) {
                return java.util.Collections.emptyList();
            }
            return new java.util.ArrayList<>(
                    compensations.subList(nextCompensation, compensations.size()));
        }

        /**
         * Override the delay method to check the time left until this item can be actioned
         *
         * @param unit the time unit
         * @return The time left for this item
         */
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(startTime - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        /**
         * Override the compareTo method so this queue can be sorted
         *
         * @param o the object to be compared.
         * @return An integer representing whether this item should be before or after the other item
         */
        @Override
        public int compareTo(Delayed o) {
            if (o instanceof DelayedAutomation) {
                DelayedAutomation other = (DelayedAutomation) o;
                int deadlineOrder = Long.compare(startTime, other.startTime);
                return deadlineOrder != 0
                        ? deadlineOrder
                        : Long.compare(sequence, other.sequence);
            }
            return Long.compare(
                    getDelay(TimeUnit.NANOSECONDS),
                    o.getDelay(TimeUnit.NANOSECONDS));
        }
    }

    /** Normal user work never consumes the urgent reconciliation reserve. */
    private static final int MAX_QUEUE_ITEMS = 1_024;
    private static final int MAX_URGENT_RECONCILIATION_ITEMS = 32;
    private static final int MAX_QUEUE_ITEMS_WITH_URGENT_RESERVE =
            MAX_QUEUE_ITEMS + MAX_URGENT_RECONCILIATION_ITEMS;
    private static final int MAX_CONSECUTIVE_URGENT_CLAIMS = 32;
    private static final int MAX_SHUTDOWN_DRAIN_RUNNERS = 4;
    private static final java.util.concurrent.atomic.AtomicLong QUEUE_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COMPENSATION_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();
    private static int consecutiveUrgentClaims;
    /** Orders successful allowlisted setter boundaries across automation IDs. Guarded by lock. */
    private static long stateSetterCompletionSequence;
    private static final class LatestStateTag {
        final long generation;
        final long sequence;

        LatestStateTag(long generation, long sequence) {
            this.generation = generation;
            this.sequence = sequence;
        }
    }
    // A DelayQueue which is thread safe and will return items after a delay
    private static final DelayQueue<DelayedAutomation> automationQueue = new DelayQueue<>();
    // Reconciliation is always ready now. Keeping it out of DelayQueue prevents a future urgent
    // deadline from ever becoming the head and blocking already-due ordinary work.
    private static final java.util.ArrayDeque<DelayedAutomation> urgentReadyQueue =
            new java.util.ArrayDeque<>();
    // A set to store the currently queued items for an O(1) lookup to see if an item already exists
    private static final Set<String> queueItems = ConcurrentHashMap.newKeySet();
    // Locking discipline: every mutation of worker/queue/queueItems and every due-item claim is
    // performed while holding this monitor. The worker waits with Object.wait(), which releases the
    // monitor, so dequeue and in-flight ownership can be atomic without blocking producers.
    private static final Object lock = new Object();
    private static final ThreadLocal<DelayedAutomation> executingItem = new ThreadLocal<>();
    // volatile so the un-synchronized reads inside the worker loop (and any future readers) observe
    // the latest reference; all writes still happen under `lock`.
    private static volatile Thread automationWorker = null;
    private static final class InFlightAction {
        final DelayedAutomation item;

        InFlightAction(DelayedAutomation item) {
            this.item = item;
        }
    }

    /** Actions already claimed by the normal worker and executing outside {@link #lock}. */
    private static final java.util.IdentityHashMap<Thread, InFlightAction> inFlightActions =
            new java.util.IdentityHashMap<>();
    /** Prevents a replacement worker from starting while canceled executors are still draining. */
    private static int workerRestartSuspensions;
    /**
     * Executors detached after the hard cancellation bound. They cannot claim more work, are
     * daemon threads, and are retained by identity until they actually exit. A new shutdown drain
     * is refused while this set is non-empty, bounding detached work to one generation while the
     * normal queue remains available to the latest vehicle state.
     */
    private static final java.util.IdentityHashMap<Thread, CancellationBarrier> isolatedExecutors =
            new java.util.IdentityHashMap<>();
    /**
     * When each isolated executor was detached ({@link System#nanoTime}), so
     * {@link #reapDeadIsolatedExecutors} can tell a thread that is merely slow from one that will
     * never return. Entries are added and removed in lockstep with {@link #isolatedExecutors}.
     */
    private static final java.util.IdentityHashMap<Thread, Long> isolatedExecutorSinceNanos =
            new java.util.IdentityHashMap<>();
    /**
     * How long an isolated executor may keep blocking shutdown before it is abandoned. Sized well
     * past the longest legitimate blocking action (a waitUntil's 600s ceiling) so a real chain is
     * never cut short, while still bounding the wedged-HAL case to something finite.
     */
    private static final long ISOLATED_EXECUTOR_MAX_MS = 900_000L;

    private static final long[] WORKER_RETRY_DELAYS_MS = {100L, 500L, 2_000L, 5_000L};
    private static final long WORKER_DISABLE_JOIN_MS = 500L;
    private static Thread workerRetryThread;
    private static int workerRetryAttempt;
    private static boolean workerRetryFallbackPosted;
    private static final java.util.EnumMap<LatestStateStream, Long>
            latestStatePublicationGenerations =
            new java.util.EnumMap<>(LatestStateStream.class);
    private static final class LatestStatePublication {
        final LatestStateStream key;
        final long generation;
        final long sequence;
        final long enabledStateGeneration;
        final int automationConfigGeneration;
        final boolean retainedReplay;
        final boolean serviceModePausedAtStart;
        final long serviceModePauseGeneration;

        LatestStatePublication(
                LatestStateStream key, long generation, long sequence,
                long enabledStateGeneration, int automationConfigGeneration,
                boolean retainedReplay) {
            this.serviceModePausedAtStart = Automations.isServiceModeDisabled();
            this.serviceModePauseGeneration = Automations.serviceModePauseGeneration();
            this.key = key;
            this.generation = generation;
            this.sequence = sequence;
            this.enabledStateGeneration = enabledStateGeneration;
            this.automationConfigGeneration = automationConfigGeneration;
            this.retainedReplay = retainedReplay;
        }
    }
    private static final ThreadLocal<LatestStatePublication> latestStatePublication =
            new ThreadLocal<>();
    private static final java.util.EnumMap<LatestStateStream, Set<Long>>
            openLatestStatePublications =
            new java.util.EnumMap<>(LatestStateStream.class);
    private static final Set<CancellationBarrier> pendingReplacementReplays =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final class LatestStateReconciliation {
        Runnable publication;
        final long generation;
        final long sequence;
        final java.util.LinkedHashSet<String> actions = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashMap<
                String, java.util.EnumMap<CompensableStateSetter, StatefulCompensation>>
                actionCompensations = new java.util.LinkedHashMap<>();
        boolean publicationReplayed;

        LatestStateReconciliation(Runnable publication, long generation, long sequence) {
            this.publication = publication;
            this.generation = generation;
            this.sequence = sequence;
        }
    }
    private static final java.util.EnumMap<LatestStateStream, LatestStateReconciliation>
            disabledLatestStateReconciliations =
            new java.util.EnumMap<>(LatestStateStream.class);
    private static long latestStateReconciliationSequence;
    /**
     * Holds replacement execution until state publications dropped while all automations were
     * disabled have been force-published through the serialized queue.
     */
    private static boolean enableReconciliationPending;

    /**
     * Identity-scoped terminal gate for a parked-shutdown drain.
     * All access to the generation and its runner set is protected by {@link #lock}.
     */
    private static final class ShutdownDrainGeneration {
        private final Set<Thread> runners = new java.util.HashSet<>();
        private final java.util.ArrayDeque<DelayedAutomation> pending =
                new java.util.ArrayDeque<>();
        private final java.util.IdentityHashMap<Thread, DelayedAutomation> claims =
                new java.util.IdentityHashMap<>();
        private boolean drainActive;
    }

    private static ShutdownDrainGeneration shutdownDrainGeneration;

    private static final class CancellationBarrier {
        final ShutdownDrainGeneration generation;
        final Thread normalWorker;
        Runnable lateCallback;
        boolean lateCallbackArmed;
        boolean finished;
        boolean lateCallbackInvoked;
        int isolatedExecutorCount;
        int apiWaiters;
        final java.util.EnumSet<CompensableStateSetter> isolatedSetterAxes =
                java.util.EnumSet.noneOf(CompensableStateSetter.class);
        final java.util.EnumMap<LatestStateStream, ReplacementPublication>
                replacementPublications =
                new java.util.EnumMap<>(LatestStateStream.class);

        CancellationBarrier(ShutdownDrainGeneration generation, Thread normalWorker) {
            this.generation = generation;
            this.normalWorker = normalWorker;
        }
    }

    private static final class ReplacementPublication {
        long generation;
        long sequence;
        final java.util.LinkedHashSet<String> actions = new java.util.LinkedHashSet<>();
        final java.util.EnumMap<
                CompensableStateSetter, java.util.ArrayList<DesiredStateSetter>>
                desiredSetters = new java.util.EnumMap<>(CompensableStateSetter.class);
    }

    private static final class DesiredStateSetter {
        final StatefulCompensation compensation;
        final long completionSequence;

        DesiredStateSetter(
                StatefulCompensation compensation, long completionSequence) {
            this.compensation = compensation;
            this.completionSequence = completionSequence;
        }
    }

    private static final class SelectedDesiredStateSetter {
        final LatestStateStream stream;
        final ReplacementPublication publication;
        final DesiredStateSetter desired;

        SelectedDesiredStateSetter(
                LatestStateStream stream,
                ReplacementPublication publication,
                DesiredStateSetter desired) {
            this.stream = stream;
            this.publication = publication;
            this.desired = desired;
        }
    }

    private static CancellationBarrier cancellationBarrier;

    public enum ShutdownDrainCancelResult {
        NO_DRAIN,
        QUIESCED,
        TIMED_OUT
    }

    public enum ShutdownDrainStatus {
        QUIESCED,
        TIMED_OUT,
        BLOCKED,
        DISABLED
    }

    public static final class ShutdownDrainResult {
        public final ShutdownDrainStatus status;
        public final int completed;

        ShutdownDrainResult(ShutdownDrainStatus status, int completed) {
            this.status = status;
            this.completed = completed;
        }

        public boolean mayCommitShutdown() {
            return status == ShutdownDrainStatus.QUIESCED
                    || status == ShutdownDrainStatus.DISABLED;
        }
    }

    @FunctionalInterface
    public interface ShutdownCommit {
        boolean commit();
    }

    /** Complete allowlist of vehicle controls that are safe to replay as latest-state repair. */
    public enum CompensableStateSetter {
        OPERATION_MODE,
        ENERGY_MODE,
        WIRELESS_CHARGING_GLOBAL,
        WIRELESS_CHARGING_LEFT,
        WIRELESS_CHARGING_RIGHT
    }

    @FunctionalInterface
    public interface CompensationCommand {
        boolean run();
    }

    private static final class StatefulCompensation {
        final CompensableStateSetter setter;
        final CompensationCommand command;
        final String automationId;
        final Object automationIdentity;
        final long automationRevision;

        StatefulCompensation(
                CompensableStateSetter setter,
                CompensationCommand command,
                String automationId,
                Object automationIdentity,
                long automationRevision) {
            this.setter = setter;
            this.command = command;
            this.automationId = automationId;
            this.automationIdentity = automationIdentity;
            this.automationRevision = automationRevision;
        }

        boolean isStillOwned() {
            return automationId != null
                    && automationIdentity != null
                    && Automations.ownsEnabledDefinition(
                            automationId,
                            automationIdentity,
                            automationRevision);
        }
    }

    /** Opaque boundary token returned before an allowlisted state setter is invoked. */
    public static final class LatestStateCompensationToken {
        private final DelayedAutomation item;
        private final StatefulCompensation compensation;
        private boolean completed;

        private LatestStateCompensationToken(
                DelayedAutomation item, StatefulCompensation compensation) {
            this.item = item;
            this.compensation = compensation;
        }
    }

    @FunctionalInterface
    public interface LatestStatePublicationCommit {
        void publish();
    }

    /**
     * Runs while the queue publication lock is held. Implementations may take an owner lock,
     * validate a lease, and invoke the commit before releasing that owner lock.
     */
    @FunctionalInterface
    public interface LatestStatePublicationGuard {
        boolean publishIfCurrent(LatestStatePublicationCommit commit);
    }

    private AutomationQueue() {}

    /** Serialize a latest-state observation, its state commit, and synchronous queue admission. */
    public static void runLatestStatePublication(
            LatestStateStream stream, Runnable publication) {
        runLatestStatePublication(stream, publication, 0L, 0L);
    }

    /**
     * Validate an external owner lease and publish as one queue-locked operation. The queue lock is
     * acquired before the guard runs, so a rejected guard cannot allocate or supersede a queue
     * generation. The guard must invoke the supplied commit while its owner lock is still held.
     */
    public static boolean runLatestStatePublicationGuarded(
            LatestStateStream stream,
            LatestStatePublicationGuard guard,
            Runnable publication) {
        if (stream == null || guard == null || publication == null) return false;
        final java.util.concurrent.atomic.AtomicBoolean committed =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<java.util.List<String>> replay =
                new java.util.concurrent.atomic.AtomicReference<>(
                        java.util.Collections.emptyList());
        final boolean accepted;
        synchronized (lock) {
            accepted = guard.publishIfCurrent(() -> {
                if (!committed.compareAndSet(false, true)) return;
                replay.set(runLatestStatePublicationLocked(
                        stream, publication, 0L, 0L));
            });
        }
        logQueuedReplacementReplays(replay.get());
        return accepted && committed.get();
    }

    /**
     * Run a publication either as a new observation or as a replay of a retained generation.
     * A retained replay keeps its original generation so fallback action IDs and newly generated
     * queue work describe the same observation.
     */
    private static void runLatestStatePublication(
            LatestStateStream stream, Runnable publication,
            long retainedGeneration, long retainedSequence) {
        if (stream == null || publication == null) return;
        java.util.List<String> replay;
        synchronized (lock) {
            replay = runLatestStatePublicationLocked(
                    stream, publication, retainedGeneration, retainedSequence);
        }
        logQueuedReplacementReplays(replay);
    }

    /** Caller holds {@link #lock}. */
    private static java.util.List<String> runLatestStatePublicationLocked(
            LatestStateStream stream, Runnable publication,
            long retainedGeneration, long retainedSequence) {
        java.util.List<String> replay = java.util.Collections.emptyList();
        LatestStatePublication previous = latestStatePublication.get();
        LatestStatePublication current = previous;
        boolean ownsPublication = previous == null || previous.key != stream;
        boolean retainedReplay = retainedGeneration != 0L;
        if (ownsPublication) {
            Long previousGeneration = latestStatePublicationGenerations.get(stream);
            final long generation;
            final long sequence;
            if (retainedReplay) {
                if (previousGeneration != null
                        && previousGeneration > retainedGeneration) {
                    return replay;
                }
                generation = retainedGeneration;
                sequence = retainedSequence;
                if (previousGeneration == null
                        || previousGeneration < retainedGeneration) {
                    latestStatePublicationGenerations.put(stream, retainedGeneration);
                }
            } else {
                generation = previousGeneration == null
                        ? 1L : previousGeneration + 1L;
                latestStatePublicationGenerations.put(stream, generation);
                sequence = ++latestStateReconciliationSequence;
            }
            current = new LatestStatePublication(
                    stream, generation, sequence,
                    Automations.enabledStateGeneration(),
                    Automations.configGeneration(),
                    retainedReplay);
            openLatestStatePublications
                    .computeIfAbsent(stream, ignored -> new java.util.HashSet<>())
                    .add(generation);
            if (Automations.isDisabled()) {
                retainDisabledLatestStateReconciliationLocked(
                        stream, publication, generation, sequence);
            } else if (!retainedReplay) {
                discardSupersededDisabledReconciliationLocked(stream, generation);
            }
        }
        latestStatePublication.set(current);
        try {
            publication.run();
        } finally {
            if (ownsPublication) {
                if (previous == null) {
                    latestStatePublication.remove();
                } else {
                    latestStatePublication.set(previous);
                }
                Set<Long> open = openLatestStatePublications.get(current.key);
                if (open != null) {
                    open.remove(current.generation);
                    if (open.isEmpty()) {
                        openLatestStatePublications.remove(current.key);
                    }
                }
                boolean enabledStateChanged =
                        current.enabledStateGeneration
                                != Automations.enabledStateGeneration();
                boolean configChanged =
                        current.automationConfigGeneration
                                != Automations.configGeneration();
                // An event observed during or across Service Mode must not be
                // retained for post-End replay, even if End raced this callback.
                boolean crossedServiceMode = current.serviceModePausedAtStart
                        || current.serviceModePauseGeneration
                                != Automations.serviceModePauseGeneration();
                if (!crossedServiceMode
                        && (Automations.isDisabled() || enabledStateChanged || configChanged)) {
                    retainDisabledLatestStateReconciliationLocked(
                            current.key, publication,
                            current.generation, current.sequence);
                }
                if (!Automations.isDisabled()
                        && !retainedReplay
                        && enableReconciliationPending) {
                    forceDisabledLatestStateReconciliationsLocked();
                }
                replay = enqueueReadyReplacementReplaysLocked();
                lock.notifyAll();
            } else {
                latestStatePublication.set(previous);
            }
        }
        return replay;
    }

    /** Package-local: unchanged state is an edge during a retained publication replay. */
    static boolean forceLatestStateReplay() {
        LatestStatePublication publication = latestStatePublication.get();
        return publication != null && publication.retainedReplay;
    }

    /** Serialize runtime automation-map mutations with latest-state publication admission. */
    static void runConfigurationMutation(Runnable mutation) {
        if (mutation == null) return;
        synchronized (lock) {
            mutation.run();
            dropSupersededChainsLocked();
        }
    }

    /**
     * Caller holds {@link #lock}. A yielded chain whose definition was just replaced,
     * disabled or deleted can only end at its next attempt; end it now so a run held
     * behind it is not delayed by the old pause.
     */
    private static void dropSupersededChainsLocked() {
        java.util.ArrayList<DelayedAutomation> superseded = new java.util.ArrayList<>();
        automationQueue.removeIf(item -> {
            boolean drop = item.isAutomation()
                    && item.latestStatePublicationGenerations.isEmpty()
                    && Automations.queuedCursorSuperseded(item.getId(), item.actionCursor);
            if (drop) superseded.add(item);
            return drop;
        });
        for (DelayedAutomation chain : superseded) {
            queueItems.remove(chain.getQueueKey());
            releaseHeldRetriggerLocked(chain);
        }
        if (!superseded.isEmpty()) lock.notifyAll();
    }

    /**
     * Atomically apply the state mutation belonging to the current publication if it is still the
     * newest observation for its stream. Publication callbacks are deliberately limited to
     * in-process state mutation and queue admission because the outer callback holds {@link #lock}.
     *
     * @return false when a newer publication superseded this callback before its state commit
     */
    public static boolean runLatestStateMutation(
            LatestStateStream stream, Runnable mutation) {
        if (stream == null || mutation == null) return false;
        synchronized (lock) {
            LatestStatePublication publication = latestStatePublication.get();
            if (publication != null && publication.key == stream) {
                Long latestGeneration = latestStatePublicationGenerations.get(stream);
                if (latestGeneration != null
                        && latestGeneration.longValue() != publication.generation) {
                    return false;
                }
            }
            mutation.run();
            return true;
        }
    }

    /**
     * Capture an idempotent state-setting command executed by the current automation item.
     * The token is not published until the setter reports successful completion.
     */
    public static LatestStateCompensationToken registerLatestStateCompensation(
            CompensableStateSetter setter, CompensationCommand command) {
        if (setter == null || command == null) return null;
        DelayedAutomation item = executingItem.get();
        if (item == null || item.latestStatePublicationGenerations.isEmpty()) return null;
        Object automationIdentity = Automations.activeQueuedDefinitionIdentity();
        long automationRevision = Automations.activeQueuedDefinitionRevision();
        if (automationIdentity == null || automationRevision < 0L) return null;
        return new LatestStateCompensationToken(
                item,
                new StatefulCompensation(
                        setter, command, item.getId(),
                        automationIdentity, automationRevision));
    }

    /**
     * Close a setter boundary. Only a router-confirmed write becomes compensation material; this
     * records the boundary even when the enclosing action chain is interrupted immediately after it.
     */
    public static void completeLatestStateCompensation(
            LatestStateCompensationToken token, boolean successful) {
        if (token == null) return;
        java.util.List<String> replay = java.util.Collections.emptyList();
        synchronized (lock) {
            if (token.completed) return;
            token.completed = true;
            if (!successful
                    || token.item != executingItem.get()) {
                return;
            }
            boolean latestDefinition =
                    token.compensation.isStillOwned();
            if (latestDefinition) {
                token.item.completedStatefulCompensations.put(
                        token.compensation.setter, token.compensation);
            }
            recordStateSetterCompletionLocked(
                    Thread.currentThread(), token.item,
                    token.compensation, latestDefinition);
            replay = enqueueReadyReplacementReplaysLocked();
            lock.notifyAll();
        }
        logQueuedReplacementReplays(replay);
    }

    /** Caller holds {@link #lock}. */
    private static void enqueueItemLocked(DelayedAutomation item) {
        if (item.urgent) {
            urgentReadyQueue.addLast(item);
        } else {
            automationQueue.add(item);
        }
    }

    /** Caller holds {@link #lock}. */
    private static DelayedAutomation pollDueItemLocked() {
        DelayedAutomation item;
        if (!urgentReadyQueue.isEmpty()
                && (consecutiveUrgentClaims < MAX_CONSECUTIVE_URGENT_CLAIMS
                || automationQueue.peek() == null
                || automationQueue.peek().getDelay(TimeUnit.NANOSECONDS) > 0L)) {
            item = urgentReadyQueue.pollFirst();
            consecutiveUrgentClaims++;
            return item;
        }
        item = automationQueue.poll();
        if (item != null) {
            consecutiveUrgentClaims = 0;
            return item;
        }
        if (!urgentReadyQueue.isEmpty()) {
            item = urgentReadyQueue.pollFirst();
            consecutiveUrgentClaims++;
        }
        return item;
    }

    /** Caller holds {@link #lock}. */
    private static int queuedUrgentItemCountLocked() {
        int count = urgentReadyQueue.size();
        for (ShutdownDrainGeneration generation : activeDrainGenerationsLocked()) {
            for (DelayedAutomation item : generation.pending) {
                if (item.urgent) count++;
            }
        }
        return count;
    }

    /** Caller holds {@link #lock}. */
    private static boolean hasQueueCapacityLocked(boolean urgent) {
        if (urgent) {
            return queueItems.size() < MAX_QUEUE_ITEMS_WITH_URGENT_RESERVE
                    && queuedUrgentItemCountLocked()
                    < MAX_URGENT_RECONCILIATION_ITEMS;
        }
        return queueItems.size() < MAX_QUEUE_ITEMS;
    }

    /** Caller holds {@link #lock}. */
    private static void discardSupersededDisabledReconciliationLocked(
            LatestStateStream publicationKey, long generation) {
        LatestStateReconciliation retained =
                disabledLatestStateReconciliations.get(publicationKey);
        if (retained != null && retained.generation < generation) {
            disabledLatestStateReconciliations.remove(publicationKey);
        }
        enableReconciliationPending = !disabledLatestStateReconciliations.isEmpty();
    }

    /** Caller holds {@link #lock}. */
    private static void retainDisabledLatestStateReconciliationLocked(
            LatestStateStream publicationKey, Runnable publication,
            long generation, long sequence) {
        if (Automations.isServiceModeDisabled()) return;
        LatestStateReconciliation retained =
                disabledLatestStateReconciliations.get(publicationKey);
        if (retained != null) {
            if (retained.generation > generation) {
                return;
            }
            if (retained.generation == generation) {
                if (publication != null) {
                    retained.publication = publication;
                    retained.publicationReplayed = false;
                }
                enableReconciliationPending = true;
                return;
            }
        }
        disabledLatestStateReconciliations.put(
                publicationKey,
                new LatestStateReconciliation(publication, generation, sequence));
        enableReconciliationPending = true;
    }

    /** Preserve a latest-state action whose normal queue admission could not complete. */
    private static void retainLatestStateActionLocked(
            String id, LatestStatePublication publication) {
        if (id == null || publication == null) return;
        Long latestGeneration =
                latestStatePublicationGenerations.get(publication.key);
        if (latestGeneration != null
                && latestGeneration > publication.generation) {
            return;
        }
        retainDisabledLatestStateReconciliationLocked(
                publication.key, null,
                publication.generation, publication.sequence);
        LatestStateReconciliation retained =
                disabledLatestStateReconciliations.get(publication.key);
        if (retained != null
                && retained.generation == publication.generation) {
            retained.actions.add(id);
            enableReconciliationPending = true;
        }
    }

    /** Preserve a tagged item before disabled-state teardown drops or isolates its owner. */
    private static void retainLatestStateReconciliationLocked(DelayedAutomation item) {
        if (Automations.isServiceModeDisabled()) return;
        if (item == null || item.latestStatePublicationGenerations.isEmpty()) return;
        for (java.util.Map.Entry<LatestStateStream, LatestStateTag> entry :
                item.latestStatePublicationGenerations.entrySet()) {
            LatestStateTag tag = entry.getValue();
            LatestStateReconciliation retained =
                    disabledLatestStateReconciliations.get(entry.getKey());
            if (retained == null || tag.generation > retained.generation) {
                retained = new LatestStateReconciliation(
                        null, tag.generation, tag.sequence);
                disabledLatestStateReconciliations.put(entry.getKey(), retained);
            }
            if (tag.generation == retained.generation) {
                if (item.isAutomationWork()) {
                    retained.actions.add(item.getId());
                }
                mergeCompensations(
                        retained.actionCompensations,
                        item.remainingCompensations());
                enableReconciliationPending = true;
            }
        }
    }

    private static void mergeCompensations(
            java.util.Map<
                    String, java.util.EnumMap<CompensableStateSetter, StatefulCompensation>>
                    destination,
            java.util.Collection<StatefulCompensation> compensations) {
        if (compensations == null || compensations.isEmpty()) return;
        for (StatefulCompensation compensation : compensations) {
            if (compensation == null || compensation.automationId == null) continue;
            destination.computeIfAbsent(
                            compensation.automationId,
                            ignored -> new java.util.EnumMap<>(
                                    CompensableStateSetter.class))
                    .put(compensation.setter, compensation);
        }
    }

    /** All drain generations that can still own pending or claimed work. Caller holds the lock. */
    private static Set<ShutdownDrainGeneration> activeDrainGenerationsLocked() {
        Set<ShutdownDrainGeneration> generations =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (shutdownDrainGeneration != null) {
            generations.add(shutdownDrainGeneration);
        }
        if (cancellationBarrier != null) {
            generations.add(cancellationBarrier.generation);
        }
        for (CancellationBarrier barrier : isolatedExecutors.values()) {
            generations.add(barrier.generation);
        }
        for (CancellationBarrier barrier : pendingReplacementReplays) {
            generations.add(barrier.generation);
        }
        return generations;
    }

    /** Preserve tagged work from every queue ownership state before disabled teardown. */
    private static void retainLatestStateReconciliationsLocked() {
        for (DelayedAutomation item : automationQueue) {
            retainLatestStateReconciliationLocked(item);
        }
        for (DelayedAutomation item : urgentReadyQueue) {
            retainLatestStateReconciliationLocked(item);
        }
        for (InFlightAction action : inFlightActions.values()) {
            retainLatestStateReconciliationLocked(action.item);
        }
        for (ShutdownDrainGeneration generation : activeDrainGenerationsLocked()) {
            for (DelayedAutomation item : generation.pending) {
                retainLatestStateReconciliationLocked(item);
            }
            for (DelayedAutomation item : generation.claims.values()) {
                retainLatestStateReconciliationLocked(item);
            }
        }
    }

    /**
     * Force the newest disabled-state publication for each independent stream before a replacement
     * worker can execute. Caller holds {@link #lock}; Java monitors are reentrant, so publication
     * admission remains serialized through {@link #addToQueue(String, int)}.
     */
    private static void forceDisabledLatestStateReconciliationsLocked() {
        if (Automations.isDisabled()) return;
        if (disabledLatestStateReconciliations.isEmpty()) {
            enableReconciliationPending = false;
            return;
        }

        java.util.ArrayList<java.util.Map.Entry<
                LatestStateStream, LatestStateReconciliation>> retained =
                new java.util.ArrayList<>(disabledLatestStateReconciliations.entrySet());
        retained.sort(java.util.Comparator.comparingLong(
                entry -> entry.getValue().sequence));
        for (java.util.Map.Entry<LatestStateStream, LatestStateReconciliation> entry :
                retained) {
            LatestStateReconciliation current =
                    disabledLatestStateReconciliations.get(entry.getKey());
            if (current != entry.getValue()) continue;
            Long latestGeneration =
                    latestStatePublicationGenerations.get(entry.getKey());
            if (latestGeneration != null
                    && latestGeneration > current.generation) {
                disabledLatestStateReconciliations.remove(entry.getKey());
                continue;
            }
            if (isLatestStatePublicationOpenLocked(
                    entry.getKey(), current.generation)) {
                continue;
            }
            try {
                if (current.publication != null && !current.publicationReplayed) {
                    runLatestStatePublication(
                            entry.getKey(), current.publication,
                            current.generation, current.sequence);
                    if (Automations.isDisabled()) {
                        current.publicationReplayed = false;
                        continue;
                    }
                    current.publicationReplayed = true;
                }
                enqueueDisabledLatestStateReconciliationLocked(
                        entry.getKey(), current);
                if (disabledLatestStateReconciliations.get(entry.getKey()) == current) {
                    if (current.actions.isEmpty()
                            && current.actionCompensations.isEmpty()
                            && (current.publication == null
                            || current.publicationReplayed)) {
                        disabledLatestStateReconciliations.remove(entry.getKey());
                    }
                }
            } catch (Throwable failure) {
                logger.error("Disabled-state automation reconciliation failed; retaining stream");
            }
        }
        enableReconciliationPending = !disabledLatestStateReconciliations.isEmpty();
        ensureWorker();
    }

    /** Re-admit a retained queued publication through normal queue/replay ownership. */
    private static void enqueueDisabledLatestStateReconciliationLocked(
            LatestStateStream publicationKey, LatestStateReconciliation reconciliation) {
        java.util.Iterator<java.util.Map.Entry<
                String, java.util.EnumMap<CompensableStateSetter, StatefulCompensation>>>
                compensationActions =
                reconciliation.actionCompensations.entrySet().iterator();
        while (compensationActions.hasNext()) {
            java.util.Map.Entry<
                    String, java.util.EnumMap<CompensableStateSetter, StatefulCompensation>>
                    entry = compensationActions.next();
            entry.getValue().values().removeIf(
                    compensation -> !compensation.isStillOwned());
            if (entry.getValue().isEmpty()) {
                compensationActions.remove();
                continue;
            }
            if (!hasQueueCapacityLocked(true)) {
                logger.error("Automation queue urgent reserve reached; retaining compensation: "
                        + entry.getKey());
                return;
            }
            DelayedAutomation item = new DelayedAutomation(
                    entry.getKey(), entry.getValue().values(),
                    publicationKey,
                    reconciliation.generation,
                    reconciliation.sequence);
            enqueueItemLocked(item);
            queueItems.add(item.getQueueKey());
            compensationActions.remove();
        }

        java.util.Iterator<String> actions = reconciliation.actions.iterator();
        while (actions.hasNext()) {
            String id = actions.next();
            boolean admitted = enqueueStateSetterReconciliationLocked(
                    id, publicationKey,
                    reconciliation.generation,
                    reconciliation.sequence);
            if (admitted) {
                recordReplacementAdmissionLocked(
                        id, publicationKey,
                        reconciliation.generation,
                        reconciliation.sequence);
                actions.remove();
            }
        }
    }

    /** Caller holds {@link #lock}. */
    private static boolean enqueueStateSetterReconciliationLocked(
            String id, LatestStateStream publicationKey,
            long publicationGeneration, long publicationSequence) {
        if (id == null || publicationKey == null || publicationGeneration == 0L) return false;
        for (DelayedAutomation item : urgentReadyQueue) {
            if (item.isStateSetterOnly()
                    && id.equals(item.getId())
                    && item.belongsToLatestStatePublication(
                            publicationKey, publicationGeneration)) {
                return true;
            }
        }
        for (InFlightAction action : inFlightActions.values()) {
            DelayedAutomation item = action.item;
            if (item.isStateSetterOnly()
                    && id.equals(item.getId())
                    && item.belongsToLatestStatePublication(
                            publicationKey, publicationGeneration)) {
                return true;
            }
        }
        for (ShutdownDrainGeneration generation : activeDrainGenerationsLocked()) {
            for (DelayedAutomation item : generation.pending) {
                if (item.isStateSetterOnly()
                        && id.equals(item.getId())
                        && item.belongsToLatestStatePublication(
                                publicationKey, publicationGeneration)) {
                    return true;
                }
            }
            for (DelayedAutomation item : generation.claims.values()) {
                if (item.isStateSetterOnly()
                        && id.equals(item.getId())
                        && item.belongsToLatestStatePublication(
                                publicationKey, publicationGeneration)) {
                    return true;
                }
            }
        }
        String queueKey = "latest-state:" + publicationKey.name() + ":"
                + publicationGeneration + ":" + id;
        if (queueItems.contains(queueKey)) return true;
        if (!hasQueueCapacityLocked(true)) {
            logger.error("Automation queue urgent reserve reached; retaining reconciliation: "
                    + id);
            return false;
        }
        DelayedAutomation item = new DelayedAutomation(
                id, queueKey, publicationKey,
                publicationGeneration, publicationSequence);
        enqueueItemLocked(item);
        queueItems.add(queueKey);
        return true;
    }

    /** Attach a retained generation to matching work regardless of its current owner. */
    private static boolean markExistingPublicationWorkLocked(
            String id, LatestStateStream publicationKey,
            long publicationGeneration, long publicationSequence,
            boolean urgent) {
        boolean found = false;
        java.util.ArrayList<DelayedAutomation> promote =
                urgent ? new java.util.ArrayList<>() : null;
        for (DelayedAutomation item : automationQueue) {
            if (item.isAutomation()
                    && !item.hasStartedCursor()
                    && id.equals(item.getId())) {
                item.markLatestStatePublication(
                        publicationKey, publicationGeneration, publicationSequence);
                if (urgent) promote.add(item);
                found = true;
            }
        }
        if (promote != null) {
            for (DelayedAutomation item : promote) {
                if (automationQueue.remove(item)) {
                    DelayedAutomation promoted = new DelayedAutomation(
                            item,
                            publicationKey, publicationGeneration,
                            publicationSequence);
                    urgentReadyQueue.addLast(promoted);
                }
            }
        }
        for (DelayedAutomation item : urgentReadyQueue) {
            if (item.isAutomation()
                    && !item.hasStartedCursor()
                    && id.equals(item.getId())) {
                item.markLatestStatePublication(
                        publicationKey, publicationGeneration, publicationSequence);
                found = true;
            }
        }
        for (ShutdownDrainGeneration generation : activeDrainGenerationsLocked()) {
            for (DelayedAutomation item : generation.pending) {
                if (item.isAutomation()
                        && !item.hasStartedCursor()
                        && id.equals(item.getId())) {
                    item.markLatestStatePublication(
                            publicationKey, publicationGeneration, publicationSequence);
                    found = true;
                }
            }
        }
        return found;
    }

    /** Drop deferred edges when a temporary Service Mode pause begins.
     * Unlike disabled-all, this pause must never replay events after End. */
    static void discardServiceModeReplay() {
        synchronized (lock) {
            disabledLatestStateReconciliations.clear();
            enableReconciliationPending = false;
        }
    }

    /**
     * Reconcile queue workers with the current enabled state.
     * All worker lifecycle decisions are serialized on the queue lock.
     */
    public static void checkWorkerState() {
        Thread disabledWorker = null;
        java.util.List<String> replay = java.util.Collections.emptyList();
        synchronized (lock) {
            if (Automations.isServiceModeDisabled()) {
                // Like disable-all, but nothing is retained for replay after End.
                disabledWorker = stopAndClearWorkerLocked();
                disabledLatestStateReconciliations.clear();
                enableReconciliationPending = false;
                workerRetryAttempt = 0;
                lock.notifyAll();
            } else if (Automations.isDisabled()) {
                retainLatestStateReconciliationsLocked();
                disabledWorker = stopAndClearWorkerLocked();
                enableReconciliationPending =
                        !disabledLatestStateReconciliations.isEmpty();
                workerRetryAttempt = 0;
                lock.notifyAll();
            } else {
                forceDisabledLatestStateReconciliationsLocked();
                replay = enqueueReadyReplacementReplaysLocked();
                ensureWorker();
            }
        }
        logQueuedReplacementReplays(replay);
        if (disabledWorker == null) return;

        try {
            disabledWorker.join(WORKER_DISABLE_JOIN_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            if (disabledWorker.isAlive()) {
                logger.warn("Automation disable exceeded " + WORKER_DISABLE_JOIN_MS
                        + "ms; isolated stale worker generation");
            }
            if (!Automations.isDisabled() && !Automations.isServiceModeDisabled()) {
                forceDisabledLatestStateReconciliationsLocked();
                replay = enqueueReadyReplacementReplaysLocked();
                ensureWorker();
                if (automationWorker == null && hasWorkerWorkLocked()) {
                    scheduleWorkerRetryLocked();
                }
            }
            lock.notifyAll();
        }
        logQueuedReplacementReplays(replay);
    }

    /** Stop the current worker and clear queued work; caller holds lock. */
    private static Thread stopAndClearWorkerLocked() {
        Thread disabledWorker = automationWorker;
        if (disabledWorker != null) {
            if (disabledWorker.isAlive()) {
                CancellationBarrier isolation = new CancellationBarrier(
                        new ShutdownDrainGeneration(), disabledWorker);
                isolation.finished = true;
                isolateExecutorLocked(disabledWorker, isolation);
                inFlightActions.remove(disabledWorker);
            }
            if (automationWorker == disabledWorker) {
                automationWorker = null;
            }
            disabledWorker.interrupt();
        }
        if (workerRetryThread != null) {
            workerRetryThread.interrupt();
        }
        automationQueue.clear();
        urgentReadyQueue.clear();
        queueItems.clear();
        if (shutdownDrainGeneration != null) {
            shutdownDrainGeneration.pending.clear();
        }
        return disabledWorker;
    }

    /** Apply the completion policy for either worker type; caller holds lock. */
    private static void reconcileCompletedClaimLocked(DelayedAutomation item, boolean completed) {
        if (Automations.isDisabled()) {
            retainLatestStateReconciliationLocked(item);
        } else if (!completed
                && !isolatedExecutors.containsKey(
                        Thread.currentThread())) {
            requeueUnrunClaimLocked(item);
        } else if (completed
                && !isolatedExecutors.containsKey(
                        Thread.currentThread())) {
            releaseHeldRetriggerLocked(item);
        }
    }

    private static boolean executeItem(DelayedAutomation item) {
        // Compensations bypass normal triggerActions checks; do not execute a
        // saved setter while the runtime gate is closed. The claim is requeued.
        if (Automations.isServiceModeDisabled()) return false;
        executingItem.set(item);
        try {
            while (item.nextCompensation < item.compensations.size()) {
                if (Thread.currentThread().isInterrupted()
                        || Automations.isServiceModeDisabled()) return false;
                StatefulCompensation compensation =
                        item.compensations.get(item.nextCompensation);
                if (!compensation.isStillOwned()) {
                    synchronized (lock) {
                        item.nextCompensation++;
                    }
                    continue;
                }
                if (Automations.isServiceModeDisabled()) return false;
                boolean successful = false;
                try {
                    successful = compensation.command.run();
                } catch (Throwable failure) {
                    logger.error("Stateful automation compensation threw: " + item.getId());
                }
                if (!successful) {
                    // An interrupt preserves this exact setter boundary for requeue. A terminal
                    // command refusal is not hot-looped forever.
                    return !Thread.currentThread().isInterrupted();
                }
                synchronized (lock) {
                    item.nextCompensation++;
                    item.completedStatefulCompensations.put(
                            compensation.setter, compensation);
                    recordStateSetterCompletionLocked(
                            Thread.currentThread(), item, compensation, true);
                    lock.notifyAll();
                }
            }
            if (!item.compensations.isEmpty()) {
                return true;
            }
            if (item.isStateSetterOnly()) {
                boolean successful =
                        Automations.triggerQueuedStateSetters(item.getId());
                return successful || !Thread.currentThread().isInterrupted();
            }
            return Automations.triggerQueuedActions(item.getId(), item.actionCursor);
        } finally {
            executingItem.remove();
        }
    }

    /**
     * Start the single drainer thread if one is not already running.
     * <p>
     * MUST be called while holding {@link #lock}. Extracted so both the enable path
     * ({@link #checkWorkerState()}) and every enqueue ({@link #addToQueue}) can guarantee a drainer
     * exists. Guaranteeing it from {@code addToQueue} is what makes the queue self-healing across a
     * daemon restart: on restart {@code Automations} reloads persisted automations from disk but no
     * API mutation runs, so {@code checkWorkerState()} is never called — yet the first triggered
     * automation still enqueues, and that enqueue now spins up the worker to drain it. Kept lazy (no
     * worker until something is actually queued) so an idle-but-enabled feature costs only a parked
     * thread, and a feature with zero automations costs nothing.
     */
    private static void ensureWorker() {
        if (automationWorker != null) return;
        // Never spin up a drainer for a disabled/empty feature. Closes the race where a disable-all tears
        // the worker down while an addToQueue is blocked on the lock, then the add re-spawns an orphan
        // worker that outlives the disable. (A queued item under isDisabled would be a no-op anyway: the
        // triggerActions isDisabled guard drops it.)
        if (Automations.isDisabled() || Automations.isServiceModeDisabled()
                || !hasWorkerWorkLocked()
                || shutdownDrainGeneration != null || workerRestartSuspensions != 0) {
            return;
        }
        try {
            Thread worker = new Thread(() -> {
                try {
                    while (true) {
                        final DelayedAutomation item;
                        synchronized (lock) {
                            while (true) {
                                if (shutdownDrainGeneration != null || Automations.isDisabled()
                                        || Automations.isServiceModeDisabled()
                                        || workerRestartSuspensions != 0
                                        || automationWorker != Thread.currentThread()) {
                                    return;
                                }
                                if (enableReconciliationPending) {
                                    forceDisabledLatestStateReconciliationsLocked();
                                }
                                DelayedAutomation due = pollDueItemLocked();
                                if (due != null) {
                                    item = due;
                                    queueItems.remove(item.getQueueKey());
                                    inFlightActions.put(
                                            Thread.currentThread(),
                                            new InFlightAction(item));
                                    if (enableReconciliationPending) {
                                        forceDisabledLatestStateReconciliationsLocked();
                                    }
                                    break;
                                }

                                DelayedAutomation next = automationQueue.peek();
                                if (next == null && urgentReadyQueue.isEmpty()) {
                                    if (enableReconciliationPending) {
                                        lock.wait(WORKER_RETRY_DELAYS_MS[
                                                WORKER_RETRY_DELAYS_MS.length - 1]);
                                    } else {
                                        lock.wait();
                                    }
                                } else if (!urgentReadyQueue.isEmpty()) {
                                    continue;
                                } else {
                                    long nanos = Math.max(
                                            1L, next.getDelay(TimeUnit.NANOSECONDS));
                                    lock.wait(
                                            nanos / 1_000_000L,
                                            (int) (nanos % 1_000_000L));
                                }
                            }
                        }
                        // Ensure the conditions are checked so if they are no longer valid, the automation won't run.
                        // Wrap in catch(Throwable) so a single misbehaving action (RuntimeException/Error from a HAL
                        // call, or a hand-edited persisted config that slipped past API validation) can never kill the
                        // singleton drainer — otherwise every future automation would silently stop until daemon restart.
                        boolean completed = true;
                        try {
                            completed = executeItem(item);
                        } catch (Throwable t) {
                            logger.error("Automation action threw, continuing drainer: " + item.getId());
                        } finally {
                            java.util.List<String> replay;
                            synchronized (lock) {
                                reconcileCompletedClaimLocked(item, completed);
                                inFlightActions.remove(Thread.currentThread());
                                if (!Automations.isDisabled()
                                        && enableReconciliationPending) {
                                    forceDisabledLatestStateReconciliationsLocked();
                                }
                                replay = enqueueReadyReplacementReplaysLocked();
                                lock.notifyAll();
                            }
                            logQueuedReplacementReplays(replay);
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    CancellationBarrier completedBarrier;
                    synchronized (lock) {
                        if (automationWorker == Thread.currentThread()) {
                            automationWorker = null;
                            if (shutdownDrainGeneration == null
                                    && !Automations.isDisabled()
                                    && hasWorkerWorkLocked()) {
                                ensureWorker();
                            }
                        }
                        completedBarrier = cancellationBarrier;
                        lock.notifyAll();
                    }
                    if (completedBarrier != null) {
                        finishCancellationBarrier(completedBarrier, true);
                    }
                    finishIsolatedExecutor(Thread.currentThread());
                }
            });

            // Allow application to exit even if there are still events left in the queue.
            worker.setDaemon(true);
            automationWorker = worker;
            worker.start();
            workerRetryAttempt = 0;
        } catch (Throwable unavailable) {
            automationWorker = null;
            logger.error("Automation worker could not start");
            scheduleWorkerRetryLocked();
        }
    }

    /** Caller holds {@link #lock}. */
    private static boolean hasWorkerWorkLocked() {
        return !urgentReadyQueue.isEmpty()
                || !automationQueue.isEmpty()
                || enableReconciliationPending;
    }

    /**
     * A failed {@link Thread#start()} must not strand a one-shot ACC automation until another
     * unrelated event happens to enqueue. Retry autonomously, with a capped backoff, while work
     * remains queued.
     */
    private static void scheduleWorkerRetryLocked() {
        if ((workerRetryThread != null && workerRetryThread.isAlive())
                || !hasWorkerWorkLocked() || Automations.isDisabled()
                || Automations.isServiceModeDisabled()
                || shutdownDrainGeneration != null || workerRestartSuspensions != 0) {
            return;
        }
        final Thread retry;
        try {
            retry = new Thread(() -> {
                try {
                    while (true) {
                        final long delayMs;
                        synchronized (lock) {
                            if (!hasWorkerWorkLocked() || Automations.isDisabled()
                                    || Automations.isServiceModeDisabled()
                                    || shutdownDrainGeneration != null
                                    || workerRestartSuspensions != 0
                                    || automationWorker != null) {
                                if (!hasWorkerWorkLocked() || Automations.isDisabled()) {
                                    workerRetryAttempt = 0;
                                }
                                return;
                            }
                            int delayIndex = Math.min(
                                    workerRetryAttempt, WORKER_RETRY_DELAYS_MS.length - 1);
                            delayMs = WORKER_RETRY_DELAYS_MS[delayIndex];
                            if (workerRetryAttempt < WORKER_RETRY_DELAYS_MS.length - 1) {
                                workerRetryAttempt++;
                            }
                        }

                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }

                        synchronized (lock) {
                            if (!hasWorkerWorkLocked() || Automations.isDisabled()) {
                                workerRetryAttempt = 0;
                                return;
                            }
                            ensureWorker();
                            if (automationWorker != null) {
                                return;
                            }
                        }
                    }
                } finally {
                    synchronized (lock) {
                        if (workerRetryThread == Thread.currentThread()) {
                            workerRetryThread = null;
                        }
                        lock.notifyAll();
                    }
                }
            }, "AutomationWorkerStartRetry");
            retry.setDaemon(true);
            workerRetryThread = retry;
            retry.start();
        } catch (Throwable retryStartFailure) {
            logger.error("Automation worker retry thread could not start");
            // One bounded caller-side attempt preserves prompt enqueue/ACC handling. Any remaining
            // work is handed to the common-pool supervisor instead of looping on this caller.
            workerRetryThread = Thread.currentThread();
            try {
                try {
                    lock.wait(WORKER_RETRY_DELAYS_MS[0]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (hasWorkerWorkLocked() && !Automations.isDisabled()
                        && shutdownDrainGeneration == null
                        && workerRestartSuspensions == 0
                        && automationWorker == null) {
                    ensureWorker();
                }
            } finally {
                if (workerRetryThread == Thread.currentThread()) {
                    workerRetryThread = null;
                }
            }
            if (automationWorker == null && hasWorkerWorkLocked()
                    && !Automations.isDisabled()) {
                postWorkerRetryFallbackLocked();
            }
        }
    }

    /** Caller holds {@link #lock}. */
    private static void postWorkerRetryFallbackLocked() {
        if (workerRetryFallbackPosted) return;
        workerRetryFallbackPosted = true;
        try {
            java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
                try {
                    Thread.sleep(WORKER_RETRY_DELAYS_MS[1]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                synchronized (lock) {
                    workerRetryFallbackPosted = false;
                    if (hasWorkerWorkLocked() && !Automations.isDisabled()) {
                        ensureWorker();
                        if (automationWorker == null) {
                            scheduleWorkerRetryLocked();
                        }
                    }
                    lock.notifyAll();
                }
            });
        } catch (Throwable fallbackFailure) {
            workerRetryFallbackPosted = false;
            logger.error("Automation worker async retry supervisor unavailable");
        }
    }

    /**
     * Remove an item from the queue before it has been actioned
     * This allows items to be removed which no longer meet some conditions
     * The lookup is done from a set to improve performance when the queue becomes large
     * Synchronized on {@link #lock} so the DelayQueue and its queueItems shadow-set stay consistent.
     *
     * @param id The id of the item to remove from the queue if it exists
     * @return true when a pending item was removed
     */
    public static boolean removeFromQueue(String id) {
        if (id == null) return false;
        java.util.List<String> replay;
        synchronized (lock) {
            java.util.ArrayList<DelayedAutomation> removedItems =
                    new java.util.ArrayList<>();
            // A yielded pause/wait has already started. Condition changes may cancel only
            // pending work, not the continuation of an in-progress action chain.
            boolean removed =
                    automationQueue.removeIf(
                            delayedAutomation -> {
                                boolean match = delayedAutomation.isAutomationWork()
                                        && !delayedAutomation.hasStartedCursor()
                                        && id.equals(delayedAutomation.getId());
                                if (match) removedItems.add(delayedAutomation);
                                return match;
                            });
            removed |= urgentReadyQueue.removeIf(
                    delayedAutomation -> {
                        boolean match = delayedAutomation.isAutomationWork()
                                && !delayedAutomation.hasStartedCursor()
                                && id.equals(delayedAutomation.getId());
                        if (match) removedItems.add(delayedAutomation);
                        return match;
                    });
            for (ShutdownDrainGeneration generation : activeDrainGenerationsLocked()) {
                removed |= generation.pending.removeIf(
                        delayedAutomation -> {
                            boolean match = delayedAutomation.isAutomationWork()
                                    && !delayedAutomation.hasStartedCursor()
                                    && id.equals(delayedAutomation.getId());
                            if (match) removedItems.add(delayedAutomation);
                            return match;
                        });
            }
            removed |= dropHeldRetriggersLocked(id);
            if (!removed) return false;
            for (DelayedAutomation removedItem : removedItems) {
                queueItems.remove(removedItem.getQueueKey());
            }
            replay = enqueueReadyReplacementReplaysLocked();
            lock.notifyAll();
        }
        logQueuedReplacementReplays(replay);
        return true;
    }

    /**
     * Add an item to the queue to be actioned after the delay
     * Synchronized on {@link #lock} so the DelayQueue and its queueItems shadow-set stay consistent
     * and a state change that arrives during the worker's take()->remove window is not silently lost.
     *
     * @param id    The id of the automation to add to the queue
     * @param delay The delay in seconds before the actions can run
     */
    public static void addToQueue(String id, int delay) {
        if (id == null) return;
        synchronized (lock) {
            LatestStatePublication publication = latestStatePublication.get();
            LatestStateStream publicationKey =
                    publication != null ? publication.key : null;
            long publicationGeneration =
                    publication != null ? publication.generation : 0L;
            long publicationSequence =
                    publication != null ? publication.sequence : 0L;
            boolean admitted = false;
            boolean capacityRejected = false;
            if (shutdownDrainGeneration != null) {
                logger.info("Retaining automation queued while shutdown drain is active: " + id);
            }

            if (publication != null && publication.retainedReplay) {
                admitted = enqueueStateSetterReconciliationLocked(
                        id, publicationKey,
                        publicationGeneration, publicationSequence);
                capacityRejected = !admitted;
            } else {
                if (publication != null) {
                    admitted = markExistingPublicationWorkLocked(
                            id, publicationKey,
                            publicationGeneration, publicationSequence,
                            false);
                }
                boolean duplicateOrdinary = publication == null && queueItems.contains(id);
                if (duplicateOrdinary) {
                    holdBehindStartedChainLocked(id, delay);
                }
                if (!admitted && !duplicateOrdinary) {
                    if (!hasQueueCapacityLocked(false)) {
                        capacityRejected = true;
                    } else {
                        String queueKey = queueItems.contains(id)
                                ? "automation:" + publicationKey + ":"
                                        + publicationGeneration + ":" + id
                                : id;
                        enqueueItemLocked(new DelayedAutomation(
                                id, queueKey, delay,
                                publicationKey, publicationGeneration,
                                publicationSequence, false));
                        queueItems.add(queueKey);
                        admitted = true;
                    }
                }
                // A live publication's run re-evaluates the rule; one held behind the chain
                // would only run it a second time.
                if (admitted && publication != null) dropHeldRetriggersLocked(id);
            }
            if (!admitted && publication != null) {
                retainLatestStateActionLocked(id, publication);
            }
            if (admitted && publicationGeneration != 0L) {
                recordReplacementAdmissionLocked(
                        id, publicationKey,
                        publicationGeneration, publicationSequence);
                if (!publication.retainedReplay
                        && !replacementBarriersLocked().isEmpty()
                        && !enqueueStateSetterReconciliationLocked(
                                id, publicationKey,
                                publicationGeneration, publicationSequence)) {
                    retainLatestStateActionLocked(id, publication);
                }
            }
            if (capacityRejected) {
                logger.error("Automation queue capacity reached; "
                        + (publication != null
                        ? "retaining latest-state reconciliation: "
                        : "rejecting: ")
                        + id);
            }
            // Guarantee a drainer for what we just enqueued. Covers the post-restart path where no
            // API mutation (and thus no checkWorkerState()) has run since the automations reloaded,
            // and lets a duplicate enqueue recover from an earlier worker-start failure.
            ensureWorker();
            lock.notifyAll();
        }
    }

    /**
     * Run any already-due automations, for use immediately before the daemon kills itself.
     *
     * <p>The worker is a daemon thread, so a self-terminate ("Vehicle ON only" parks by killing the
     * process) can fire a trigger and then exit before the worker is ever scheduled — the rule's
     * actions are silently lost. This drains them so a "when power turns off" rule actually runs.
     *
     * <p>Only items whose delay has already elapsed are run: a rule that asked to wait cannot be
     * honoured by a process that is about to exit, and running it early would fire it at the wrong
     * time.
     *
     * <p>The normal worker claims a due item and increments its in-flight count under the same lock
     * used by this drain. This closes the old gap where {@code take()} removed the item, the drain
     * saw an empty queue, and the daemon exited before the worker recorded or ran it.
     *
     * <p>Runs due actions on a fixed maximum of {@link #MAX_SHUTDOWN_DRAIN_RUNNERS} daemon
     * runners and interrupts them at the deadline. Claims not started by then are put back with
     * their original deadline so an aborted shutdown can replay them.
     *
     * @param budgetMs wall-clock ceiling for the whole drain
     * @return drain status and the number of automations completed within the budget
     */
    public static ShutdownDrainResult drainDueNowResult(long budgetMs) {
        if (Automations.isServiceModeDisabled()) {
            synchronized (lock) {
                automationQueue.clear();
                urgentReadyQueue.clear();
                queueItems.clear();
                if (shutdownDrainGeneration != null) {
                    shutdownDrainGeneration.pending.clear();
                }
                lock.notifyAll();
            }
            return new ShutdownDrainResult(ShutdownDrainStatus.DISABLED, 0);
        }
        if (Automations.isDisabled()) {
            synchronized (lock) {
                retainLatestStateReconciliationsLocked();
                automationQueue.clear();
                urgentReadyQueue.clear();
                queueItems.clear();
                if (shutdownDrainGeneration != null) {
                    shutdownDrainGeneration.pending.clear();
                }
                lock.notifyAll();
            }
            return new ShutdownDrainResult(ShutdownDrainStatus.DISABLED, 0);
        }
        if (budgetMs <= 0L) {
            return new ShutdownDrainResult(ShutdownDrainStatus.BLOCKED, 0);
        }
        // Clear out isolated executors that can no longer clear themselves, or every later drain
        // is refused by the gate below and the daemon never commits a parked shutdown.
        reapDeadIsolatedExecutors();
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        final ShutdownDrainGeneration generation;
        synchronized (lock) {
            // A cancellation barrier owns all executor quiescence until it completes. Starting a
            // second drain here would let a later ON join the old barrier and miss the new runners.
            if (cancellationBarrier != null || workerRestartSuspensions != 0
                    || !isolatedExecutors.isEmpty()) {
                return new ShutdownDrainResult(ShutdownDrainStatus.BLOCKED, 0);
            }
            if (shutdownDrainGeneration == null) {
                shutdownDrainGeneration = new ShutdownDrainGeneration();
            }
            generation = shutdownDrainGeneration;
            if (generation.drainActive) {
                return new ShutdownDrainResult(ShutdownDrainStatus.BLOCKED, 0);
            }
            generation.drainActive = true;
            // poll() returns only an item whose delay has expired, and null otherwise — so this
            // never blocks and never steals an item still waiting out its delay. Drained fully
            // here so a self-re-triggering rule can't keep the loop alive.
            DelayedAutomation item;
            while ((item = pollDueItemLocked()) != null) {
                generation.pending.addLast(item);
            }
            // Wake an idle worker so it observes the terminal drain gate and exits. An in-flight
            // worker is left uninterrupted and is included in the bounded wait below.
            lock.notifyAll();
        }

        AtomicInteger actioned = new AtomicInteger();
        int runnerCount;
        synchronized (lock) {
            int liveRunners = 0;
            for (Thread runner : generation.runners) {
                if (runner.isAlive()) liveRunners++;
            }
            runnerCount = Math.min(
                    Math.max(0, MAX_SHUTDOWN_DRAIN_RUNNERS - liveRunners),
                    generation.pending.size());
        }
        for (int i = 0; i < runnerCount; i++) {
            try {
                Thread runner = new Thread(() -> {
                    try {
                        while (true) {
                            final DelayedAutomation item;
                            synchronized (lock) {
                                if (shutdownDrainGeneration != generation
                                        || deadline - System.nanoTime() <= 0L) {
                                    return;
                                }
                                item = generation.pending.pollFirst();
                                if (item != null) {
                                    queueItems.remove(item.getQueueKey());
                                    generation.claims.put(Thread.currentThread(), item);
                                    if (enableReconciliationPending
                                            && !Automations.isDisabled()) {
                                        forceDisabledLatestStateReconciliationsLocked();
                                    }
                                }
                            }
                            if (item == null) return;
                            boolean completed = false;
                            try {
                                completed = executeItem(item);
                                if (completed) {
                                    actioned.incrementAndGet();
                                }
                            } catch (Throwable t) {
                                logger.error("Automation action threw during shutdown drain: "
                                        + item.getId());
                            } finally {
                                java.util.List<String> replay;
                                synchronized (lock) {
                                    generation.claims.remove(Thread.currentThread());
                                    reconcileCompletedClaimLocked(item, completed);
                                    if (!Automations.isDisabled()
                                            && enableReconciliationPending) {
                                        forceDisabledLatestStateReconciliationsLocked();
                                    }
                                    replay = enqueueReadyReplacementReplaysLocked();
                                    lock.notifyAll();
                                }
                                logQueuedReplacementReplays(replay);
                            }
                        }
                    } finally {
                        CancellationBarrier completedBarrier;
                        synchronized (lock) {
                            generation.runners.remove(Thread.currentThread());
                            completedBarrier = cancellationBarrier;
                            lock.notifyAll();
                        }
                        if (completedBarrier != null) {
                            finishCancellationBarrier(completedBarrier, true);
                        }
                        finishIsolatedExecutor(Thread.currentThread());
                    }
                }, "AutomationShutdownDrain-" + i);
                // Daemon: if the budget expires mid-action we return and let the process exit rather
                // than being held open by a rule waiting on something that will never happen.
                runner.setDaemon(true);
                if (deadline - System.nanoTime() <= 0L) {
                    break;
                }
                synchronized (lock) {
                    if (shutdownDrainGeneration != generation
                            || deadline - System.nanoTime() <= 0L) {
                        break;
                    }
                    // Register and start atomically with respect to cancellation. Once registered,
                    // this runner cannot escape interruption for its generation.
                    generation.runners.add(runner);
                    try {
                        runner.start();
                    } catch (Throwable startFailure) {
                        generation.runners.remove(runner);
                        throw startFailure;
                    }
                }
            } catch (Throwable startFailure) {
                logger.error("Automation shutdown-drain runner could not start");
            }
        }

        while (true) {
            synchronized (lock) {
                // Cancellation re-opens the gate. Generation identity makes this remain false even
                // if another shutdown drain starts before this caller wakes.
                if (shutdownDrainGeneration != generation) break;
                boolean runnerAlive = false;
                for (Thread runner : generation.runners) {
                    if (runner.isAlive()) {
                        runnerAlive = true;
                        break;
                    }
                }
                if (inFlightActions.isEmpty() && !runnerAlive) break;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                try {
                    lock.wait(
                            remaining / 1_000_000L,
                            (int) (remaining % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int abandoned = 0;
        int requeued = 0;
        boolean quiesced;
        CancellationBarrier completedBarrier;
        synchronized (lock) {
            for (Thread runner : generation.runners) {
                if (!runner.isAlive()) continue;
                abandoned++;
                // Nudge a sleeping pause/waitUntil so it can bail out rather than sleep on to its
                // own ceiling while the process tears down around it.
                runner.interrupt();
            }
            while (!generation.pending.isEmpty()) {
                DelayedAutomation item = generation.pending.removeFirst();
                if (Automations.isDisabled()) {
                    retainLatestStateReconciliationLocked(item);
                    queueItems.remove(item.getQueueKey());
                    continue;
                }
                if (!queueItems.contains(item.getQueueKey())) {
                    queueItems.add(item.getQueueKey());
                }
                enqueueItemLocked(item);
                requeued++;
            }
            quiesced = inFlightActions.isEmpty() && generation.claims.isEmpty();
            for (Thread runner : generation.runners) {
                if (runner.isAlive()) {
                    quiesced = false;
                    break;
                }
            }
            // A shutdown commit destroys this in-memory queue. Even a delayed item must remain
            // alive until its deadline and then be drained; otherwise it can become due while the
            // marker fsync is in progress and disappear with the process.
            if (!urgentReadyQueue.isEmpty()
                    || !automationQueue.isEmpty()
                    || !generation.pending.isEmpty()) {
                quiesced = false;
            }
            generation.drainActive = false;
            completedBarrier = cancellationBarrier;
            lock.notifyAll();
        }
        if (completedBarrier != null) {
            finishCancellationBarrier(completedBarrier, true);
        }
        if (requeued != 0) {
            logger.info(requeued + " unrun shutdown-drain action(s) retained for replay");
        }
        if (abandoned != 0) {
            logger.info(abandoned + " shutdown-drain action(s) still running at " + budgetMs
                    + "ms budget — abandoning");
        }
        synchronized (lock) {
            if (!inFlightActions.isEmpty()) {
                logger.info("Automation worker still has " + inFlightActions.size()
                        + " in-flight action(s) at shutdown deadline");
            }
        }
        return new ShutdownDrainResult(
                quiesced ? ShutdownDrainStatus.QUIESCED : ShutdownDrainStatus.TIMED_OUT,
                actioned.get());
    }

    /** Compatibility wrapper for callers that only need the completion count. */
    public static int drainDueNow(long budgetMs) {
        return drainDueNowResult(budgetMs).completed;
    }

    /**
     * Re-open a shutdown drain when the vehicle turns back on before parked shutdown commits.
     * Cancellation is a bounded barrier: it interrupts both the normal singleton worker and every
     * drain runner owned by the canceled generation, then waits for all of them to exit. If the
     * bound expires, the old executors are isolated by identity, current queue work is reopened,
     * and {@code onLateQuiescence} compensates both immediately and after isolated work returns.
     */
    public static ShutdownDrainCancelResult cancelShutdownDrain(
            long budgetMs, Runnable onLateQuiescence) {
        long boundedBudgetMs = Math.max(1L, budgetMs);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundedBudgetMs);
        final CancellationBarrier barrier;
        synchronized (lock) {
            if (cancellationBarrier != null) {
                barrier = cancellationBarrier;
            } else {
                ShutdownDrainGeneration canceled = shutdownDrainGeneration;
                if (canceled == null) return ShutdownDrainCancelResult.NO_DRAIN;
                shutdownDrainGeneration = null;
                workerRestartSuspensions++;
                barrier = new CancellationBarrier(canceled, automationWorker);
                cancellationBarrier = barrier;
                for (Thread runner : canceled.runners) {
                    runner.interrupt();
                }
                if (barrier.normalWorker != null) {
                    barrier.normalWorker.interrupt();
                }
            }
            barrier.apiWaiters++;
            lock.notifyAll();
        }

        boolean quiesced = awaitCancellationQuiescence(barrier, deadline);
        if (quiesced) {
            synchronized (lock) {
                if (barrier.apiWaiters > 0) barrier.apiWaiters--;
                lock.notifyAll();
            }
            finishCancellationBarrier(barrier, false);
            return ShutdownDrainCancelResult.QUIESCED;
        }

        logger.warn("Shutdown-drain cancellation exceeded " + boundedBudgetMs
                + "ms; isolating the canceled executors and reopening latest-state work");
        boolean hardRelease = false;
        boolean runCallbackNow = false;
        synchronized (lock) {
            if (barrier.apiWaiters > 0) barrier.apiWaiters--;
            if (barrier.finished || cancellationBarrier != barrier) {
                runCallbackNow = onLateQuiescence != null;
            } else {
                if (onLateQuiescence != null) {
                    barrier.lateCallback = onLateQuiescence;
                    barrier.lateCallbackArmed = true;
                }
                hardRelease = true;
            }
            lock.notifyAll();
        }
        if (hardRelease) {
            hardReleaseCancellationBarrier(barrier);
        }
        if (runCallbackNow) {
            runLateCallback(onLateQuiescence);
        }
        return ShutdownDrainCancelResult.TIMED_OUT;
    }

    /**
     * Abort an uncommitted parked shutdown while retaining queued and unrun claims.
     * The same quiescence barrier used by ACC ON prevents a replacement worker from
     * racing an interruption-ignoring drain action.
     */
    public static ShutdownDrainCancelResult abortShutdownDrain(
            long budgetMs, Runnable onLateQuiescence) {
        return cancelShutdownDrain(budgetMs, onLateQuiescence);
    }

    private static boolean awaitCancellationQuiescence(
            CancellationBarrier barrier, long deadline) {
        synchronized (lock) {
            while (cancellationBarrier == barrier
                    && !isCancellationQuiescentLocked(barrier)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    lock.wait(
                            Math.max(1L, remaining / 1_000_000L),
                            (int) (remaining % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return cancellationBarrier != barrier
                || isCancellationQuiescentLocked(barrier);
        }
    }

    private static boolean isCancellationQuiescentLocked(CancellationBarrier barrier) {
        if (barrier.generation.drainActive) {
            return false;
        }
        if (!inFlightActions.isEmpty()) {
            return false;
        }
        if (!barrier.generation.claims.isEmpty()) {
            return false;
        }
        if (barrier.normalWorker != null && barrier.normalWorker.isAlive()) {
            return false;
        }
        for (Thread runner : barrier.generation.runners) {
            if (runner.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Release the terminal gate at the caller's hard deadline. Any executor that ignored
     * interruption is detached by identity and cannot claim another item. Its completion triggers
     * one more latest-state compensation, while a replacement worker can drain current work now.
     */
    private static void hardReleaseCancellationBarrier(CancellationBarrier barrier) {
        Runnable immediateCallback;
        synchronized (lock) {
            if (cancellationBarrier != barrier || barrier.finished) {
                return;
            }

            while (!barrier.generation.pending.isEmpty()) {
                DelayedAutomation item = barrier.generation.pending.removeFirst();
                if (Automations.isDisabled()) {
                    retainLatestStateReconciliationLocked(item);
                    queueItems.remove(item.getQueueKey());
                } else {
                    // Pending drain work still owns its queueItems entry. Put the owner back
                    // directly; requeueUnrunClaimLocked is only for claims whose entry was removed.
                    boolean restored = false;
                    java.util.ArrayList<DelayedAutomation> queuedItems =
                            new java.util.ArrayList<>(automationQueue);
                    queuedItems.addAll(urgentReadyQueue);
                    for (DelayedAutomation queued : queuedItems) {
                        if (!item.getQueueKey().equals(queued.getQueueKey())) continue;
                        if (!queued.hasStartedCursor()) {
                            for (java.util.Map.Entry<LatestStateStream, LatestStateTag> tag :
                                    item.latestStatePublicationGenerations.entrySet()) {
                                queued.markLatestStatePublication(
                                        tag.getKey(),
                                        tag.getValue().generation,
                                        tag.getValue().sequence);
                            }
                        }
                        restored = true;
                    }
                    queueItems.add(item.getQueueKey());
                    if (!restored) {
                        enqueueItemLocked(item);
                    }
                }
            }
            barrier.generation.drainActive = false;

            if (barrier.normalWorker != null && barrier.normalWorker.isAlive()) {
                isolateExecutorLocked(barrier.normalWorker, barrier);
                if (automationWorker == barrier.normalWorker) {
                    automationWorker = null;
                }
            }
            java.util.ArrayList<Thread> runners =
                    new java.util.ArrayList<>(barrier.generation.runners);
            barrier.generation.runners.clear();
            for (Thread runner : runners) {
                if (runner.isAlive()) {
                    isolateExecutorLocked(runner, barrier);
                }
            }

            barrier.finished = true;
            cancellationBarrier = null;
            if (workerRestartSuspensions > 0) {
                workerRestartSuspensions--;
            }
            ensureWorker();
            if (automationWorker == null && hasWorkerWorkLocked()) {
                scheduleWorkerRetryLocked();
            }
            immediateCallback = barrier.lateCallbackArmed
                    ? barrier.lateCallback : null;
            lock.notifyAll();
        }
        // Reconcile immediately for partial effects that landed before isolation. If isolated work
        // later returns, finishIsolatedExecutor invokes the same closure once more.
        runLateCallback(immediateCallback);
    }

    private static void isolateExecutorLocked(
            Thread executor, CancellationBarrier barrier) {
        if (isolatedExecutors.containsKey(executor)) return;
        if (Automations.isDisabled()) {
            InFlightAction normalAction = inFlightActions.get(executor);
            if (normalAction != null) {
                retainLatestStateReconciliationLocked(normalAction.item);
            }
            retainLatestStateReconciliationLocked(
                    barrier.generation.claims.get(executor));
        }
        isolatedExecutors.put(executor, barrier);
        isolatedExecutorSinceNanos.put(executor, System.nanoTime());
        barrier.isolatedExecutorCount++;
        if (!Automations.isDisabled()) {
            // The detached chain never reaches its release; its held run is ordinary work.
            InFlightAction normalAction = inFlightActions.get(executor);
            if (normalAction != null) releaseHeldRetriggerLocked(normalAction.item);
            DelayedAutomation claim = barrier.generation.claims.get(executor);
            if (claim != null) releaseHeldRetriggerLocked(claim);
        }
    }

    /**
     * Drop isolated executors that can never call {@link #finishIsolatedExecutor} themselves.
     *
     * <p>That method is the ONLY removal from {@code isolatedExecutors}, and it runs on the
     * isolated thread's own exit path — so a thread that never returns pins its entry forever.
     * Both shutdown gates ({@code drainDueNowResult} and {@code commitShutdownIfQuiescent}) treat a
     * non-empty map as "not quiescent", so one uninterruptible action (a Binder call into a wedged
     * HAL ignores {@code interrupt()}) permanently prevents the daemon from committing a parked
     * shutdown: it never plants the marker, never exits, and drains the 12V battery until reboot.
     *
     * <p>A dead thread is reaped unconditionally — it demonstrably cannot run its own finally. A
     * still-live thread is released only once it is hopelessly overdue, because releasing it early
     * would let shutdown commit while it is mid-write, which is what the isolation exists to
     * prevent. Reaping routes through {@code finishIsolatedExecutor} so the barrier bookkeeping,
     * late callback and replacement replays all run exactly as a normal exit would.
     *
     * <p>Caller must NOT hold {@link #lock}.
     */
    private static void reapDeadIsolatedExecutors() {
        java.util.List<Thread> reap = null;
        synchronized (lock) {
            if (isolatedExecutors.isEmpty()) return;
            long now = System.nanoTime();
            for (Thread executor : isolatedExecutors.keySet()) {
                boolean dead = !executor.isAlive();
                Long since = isolatedExecutorSinceNanos.get(executor);
                boolean overdue = since != null
                        && (now - since) >= TimeUnit.MILLISECONDS.toNanos(ISOLATED_EXECUTOR_MAX_MS);
                if (!dead && !overdue) continue;
                if (reap == null) reap = new java.util.ArrayList<>();
                reap.add(executor);
                logger.warn("Releasing isolated automation executor " + executor.getName()
                        + (dead ? " (thread died without clearing its isolation)"
                                : " (still running after " + (ISOLATED_EXECUTOR_MAX_MS / 1000)
                                        + "s — abandoning it so shutdown can proceed)"));
            }
        }
        if (reap == null) return;
        for (Thread executor : reap) {
            finishIsolatedExecutor(executor);
        }
    }

    private static void finishIsolatedExecutor(Thread executor) {
        Runnable callback = null;
        java.util.List<String> replay = java.util.Collections.emptyList();
        synchronized (lock) {
            CancellationBarrier barrier = isolatedExecutors.remove(executor);
            isolatedExecutorSinceNanos.remove(executor);
            if (barrier == null) return;
            if (barrier.isolatedExecutorCount > 0) {
                barrier.isolatedExecutorCount--;
            }
            if (barrier.isolatedExecutorCount == 0
                    && barrier.lateCallbackArmed
                    && !barrier.lateCallbackInvoked) {
                barrier.lateCallbackInvoked = true;
                callback = barrier.lateCallback;
                barrier.lateCallback = null;
                barrier.lateCallbackArmed = false;
            }
            if (barrier.isolatedExecutorCount == 0) {
                if (!barrier.replacementPublications.isEmpty()) {
                    pendingReplacementReplays.add(barrier);
                }
                replay = enqueueReadyReplacementReplaysLocked();
            }
            lock.notifyAll();
        }
        runLateCallback(callback);
        logQueuedReplacementReplays(replay);
    }

    /** Caller holds {@link #lock}. */
    private static void recordReplacementAdmissionLocked(
            String id, LatestStateStream publicationKey,
            long publicationGeneration, long publicationSequence) {
        if (publicationKey == null) return;
        for (CancellationBarrier barrier : replacementBarriersLocked()) {
            recordReplacementAdmissionLocked(
                    barrier, id, publicationKey,
                    publicationGeneration, publicationSequence);
        }
    }

    /** Caller holds {@link #lock}. */
    private static void recordReplacementAdmissionLocked(
            CancellationBarrier barrier, String id,
            LatestStateStream publicationKey,
            long publicationGeneration, long publicationSequence) {
        ReplacementPublication replacement =
                barrier.replacementPublications.get(publicationKey);
        if (replacement == null) {
            replacement = new ReplacementPublication();
            barrier.replacementPublications.put(publicationKey, replacement);
        }
        if (publicationGeneration > replacement.generation) {
            replacement.generation = publicationGeneration;
            replacement.sequence = publicationSequence;
            replacement.actions.clear();
            replacement.desiredSetters.clear();
        }
        if (publicationGeneration == replacement.generation) {
            replacement.actions.add(id);
        }
    }

    /** Every cancellation generation that may still need latest-state compensation. */
    private static Set<CancellationBarrier> replacementBarriersLocked() {
        Set<CancellationBarrier> barriers =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (cancellationBarrier != null) {
            barriers.add(cancellationBarrier);
        }
        barriers.addAll(isolatedExecutors.values());
        barriers.addAll(pendingReplacementReplays);
        return barriers;
    }

    /** Caller holds {@link #lock}. */
    private static CancellationBarrier cancellationBarrierForExecutorLocked(
            Thread executor) {
        CancellationBarrier executorBarrier = isolatedExecutors.get(executor);
        if (executorBarrier == null && cancellationBarrier != null) {
            ShutdownDrainGeneration generation = cancellationBarrier.generation;
            if (cancellationBarrier.normalWorker == executor
                    || generation.runners.contains(executor)
                    || generation.claims.containsKey(executor)) {
                executorBarrier = cancellationBarrier;
            }
        }
        return executorBarrier;
    }

    /**
     * Record a completed allowlisted setter at its write boundary. Stale executor completion and
     * latest-generation compensation capture are deliberately independent of enclosing action
     * completion, because cancellation can interrupt the chain immediately after this setter.
     */
    private static void recordStateSetterCompletionLocked(
            Thread executor,
            DelayedAutomation item,
            StatefulCompensation compensation,
            boolean latestDefinition) {
        long completionSequence = ++stateSetterCompletionSequence;
        CancellationBarrier executorBarrier =
                cancellationBarrierForExecutorLocked(executor);
        if (executorBarrier != null) {
            executorBarrier.isolatedSetterAxes.add(compensation.setter);
        }
        if (!latestDefinition) return;

        for (java.util.Map.Entry<LatestStateStream, LatestStateTag> tag :
                item.latestStatePublicationGenerations.entrySet()) {
            for (CancellationBarrier barrier : replacementBarriersLocked()) {
                ReplacementPublication replacement =
                        barrier.replacementPublications.get(tag.getKey());
                if (replacement == null
                        || replacement.generation != tag.getValue().generation
                        || !replacement.actions.contains(
                                compensation.automationId)) {
                    continue;
                }
                replacement.desiredSetters
                        .computeIfAbsent(
                                compensation.setter,
                                ignored -> new java.util.ArrayList<>())
                        .add(new DesiredStateSetter(
                                compensation, completionSequence));
            }
        }
    }

    /** Caller holds {@link #lock}. */
    private static java.util.List<String> enqueueReadyReplacementReplaysLocked() {
        // Keep barriers pending for End rather than enqueuing compensations
        // behind a closed Service Mode gate.
        if (Automations.isServiceModeDisabled()
                || pendingReplacementReplays.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.ArrayList<String> enqueued = new java.util.ArrayList<>();
        java.util.Iterator<CancellationBarrier> barriers =
                pendingReplacementReplays.iterator();
        while (barriers.hasNext()) {
            CancellationBarrier barrier = barriers.next();
            if (barrier.isolatedExecutorCount != 0
                    || Automations.isDisabled()
                    || enableReconciliationPending) {
                continue;
            }

            boolean waitingForLatestSetters = false;
            java.util.Iterator<java.util.Map.Entry<
                    LatestStateStream, ReplacementPublication>> publications =
                    barrier.replacementPublications.entrySet().iterator();
            while (publications.hasNext()) {
                java.util.Map.Entry<LatestStateStream, ReplacementPublication> entry =
                        publications.next();
                ReplacementPublication replacement = entry.getValue();
                Long latestGeneration =
                        latestStatePublicationGenerations.get(entry.getKey());
                if (latestGeneration != null
                        && latestGeneration > replacement.generation) {
                    publications.remove();
                    continue;
                }
                if (replacement.generation == 0L
                        || isLatestStatePublicationOpenLocked(
                                entry.getKey(), replacement.generation)) {
                    waitingForLatestSetters = true;
                    continue;
                }
                for (String id : replacement.actions) {
                    if (hasStateSetterReconciliationWorkLocked(
                            entry.getKey(), replacement.generation, id)) {
                        waitingForLatestSetters = true;
                        break;
                    }
                }
            }

            if (waitingForLatestSetters) continue;
            if (barrier.replacementPublications.isEmpty()
                    || barrier.isolatedSetterAxes.isEmpty()) {
                barrier.replacementPublications.clear();
                barriers.remove();
                continue;
            }

            java.util.ArrayList<SelectedDesiredStateSetter> selected =
                    selectNewestDesiredSettersLocked(barrier);
            if (selected.isEmpty()) {
                barrier.replacementPublications.clear();
                barriers.remove();
                continue;
            }
            if (!hasUrgentQueueCapacityLocked(selected.size())) {
                logger.error("Automation queue urgent reserve reached; "
                        + "retaining axis-based stateful compensation");
                continue;
            }
            selected.sort(java.util.Comparator.comparingLong(
                    value -> value.desired.completionSequence));
            for (SelectedDesiredStateSetter value : selected) {
                StatefulCompensation compensation = value.desired.compensation;
                DelayedAutomation item = new DelayedAutomation(
                        compensation.automationId,
                        java.util.Collections.singletonList(compensation),
                        value.stream,
                        value.publication.generation,
                        value.publication.sequence);
                enqueueItemLocked(item);
                queueItems.add(item.getQueueKey());
                enqueued.add(compensation.automationId + "/" + compensation.setter);
            }
            barrier.replacementPublications.clear();
            barriers.remove();
            ensureWorker();
        }
        return enqueued;
    }

    /** Caller holds {@link #lock}. */
    private static java.util.ArrayList<SelectedDesiredStateSetter>
            selectNewestDesiredSettersLocked(CancellationBarrier barrier) {
        java.util.ArrayList<SelectedDesiredStateSetter> selected =
                new java.util.ArrayList<>();
        for (CompensableStateSetter setter : barrier.isolatedSetterAxes) {
            SelectedDesiredStateSetter newest = null;
            for (java.util.Map.Entry<LatestStateStream, ReplacementPublication> entry :
                    barrier.replacementPublications.entrySet()) {
                java.util.ArrayList<DesiredStateSetter> candidates =
                        entry.getValue().desiredSetters.get(setter);
                DesiredStateSetter candidate = newestOwnedDesiredSetter(candidates);
                if (candidate != null
                        && (newest == null
                        || candidate.completionSequence
                                > newest.desired.completionSequence)) {
                    newest = new SelectedDesiredStateSetter(
                            entry.getKey(), entry.getValue(), candidate);
                }
            }
            if (newest != null) selected.add(newest);
        }
        return selected;
    }

    private static DesiredStateSetter newestOwnedDesiredSetter(
            java.util.ArrayList<DesiredStateSetter> candidates) {
        if (candidates == null) return null;
        for (int index = candidates.size() - 1; index >= 0; index--) {
            DesiredStateSetter candidate = candidates.get(index);
            if (candidate.compensation.isStillOwned()) {
                return candidate;
            }
            candidates.remove(index);
        }
        return null;
    }

    /** Caller holds {@link #lock}. */
    private static boolean hasUrgentQueueCapacityLocked(int count) {
        return count >= 0
                && queueItems.size() + count <= MAX_QUEUE_ITEMS_WITH_URGENT_RESERVE
                && queuedUrgentItemCountLocked() + count
                        <= MAX_URGENT_RECONCILIATION_ITEMS;
    }

    /** Caller holds {@link #lock}. */
    private static boolean isLatestStatePublicationOpenLocked(
            LatestStateStream publicationKey, long publicationGeneration) {
        Set<Long> open = openLatestStatePublications.get(publicationKey);
        return open != null && open.contains(publicationGeneration);
    }

    /** Caller holds {@link #lock}. */
    private static boolean hasStateSetterReconciliationWorkLocked(
            LatestStateStream publicationKey, long publicationGeneration, String id) {
        return hasPublicationWorkLocked(
                publicationKey, publicationGeneration, id, true);
    }

    /** Caller holds {@link #lock}. */
    private static boolean hasPublicationWorkLocked(
            LatestStateStream publicationKey, long publicationGeneration,
            String id, boolean stateSetterOnly) {
        for (DelayedAutomation item : automationQueue) {
            if ((!stateSetterOnly || item.isStateSetterOnly())
                    && id.equals(item.getId())
                    && item.belongsToLatestStatePublication(
                    publicationKey, publicationGeneration)) {
                return true;
            }
        }
        for (DelayedAutomation item : urgentReadyQueue) {
            if ((!stateSetterOnly || item.isStateSetterOnly())
                    && id.equals(item.getId())
                    && item.belongsToLatestStatePublication(
                    publicationKey, publicationGeneration)) {
                return true;
            }
        }
        for (InFlightAction action : inFlightActions.values()) {
            if ((!stateSetterOnly || action.item.isStateSetterOnly())
                    && id.equals(action.item.getId())
                    && action.item.belongsToLatestStatePublication(
                    publicationKey, publicationGeneration)) {
                return true;
            }
        }
        for (ShutdownDrainGeneration drain : activeDrainGenerationsLocked()) {
            for (DelayedAutomation item : drain.pending) {
                if ((!stateSetterOnly || item.isStateSetterOnly())
                        && id.equals(item.getId())
                        && item.belongsToLatestStatePublication(
                        publicationKey, publicationGeneration)) {
                    return true;
                }
            }
            for (DelayedAutomation item : drain.claims.values()) {
                if ((!stateSetterOnly || item.isStateSetterOnly())
                        && id.equals(item.getId())
                        && item.belongsToLatestStatePublication(
                        publicationKey, publicationGeneration)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void logQueuedReplacementReplays(java.util.List<String> replay) {
        for (String id : replay) {
            logger.info("Queued replacement-generation stateful compensation after stale completion: "
                    + id);
        }
    }

    private static void finishCancellationBarrier(
            CancellationBarrier barrier, boolean invokeLateCallback) {
        Runnable lateCallback;
        synchronized (lock) {
            if (cancellationBarrier != barrier
                    || !isCancellationQuiescentLocked(barrier)
                    || (invokeLateCallback && barrier.apiWaiters != 0)) {
                return;
            }
            barrier.finished = true;
            cancellationBarrier = null;
            if (workerRestartSuspensions > 0) {
                workerRestartSuspensions--;
            }
            ensureWorker();
            if (automationWorker == null && hasWorkerWorkLocked()) {
                scheduleWorkerRetryLocked();
            }
            lateCallback = invokeLateCallback && barrier.lateCallbackArmed
                    ? barrier.lateCallback : null;
            barrier.lateCallbackInvoked = lateCallback != null;
            barrier.lateCallback = null;
            barrier.lateCallbackArmed = false;
            lock.notifyAll();
        }
        runLateCallback(lateCallback);
    }

    private static void runLateCallback(Runnable callback) {
        if (callback == null) return;
        try {
            callback.run();
        } catch (Throwable callbackFailure) {
            logger.error("Shutdown-drain quiescence callback failed");
        }
    }

    /**
     * Hold the queue's terminal-generation lock across the caller's irreversible marker commit.
     * Producers cannot enqueue between the final empty check and that commit.
     */
    public static boolean commitShutdownIfQuiescent(ShutdownCommit commit) {
        if (commit == null) return false;
        // Before the isolatedExecutors gate below, release any entry whose thread can never clear
        // it (see reapDeadIsolatedExecutors) — otherwise the commit is refused forever.
        reapDeadIsolatedExecutors();
        synchronized (lock) {
            ShutdownDrainGeneration generation = shutdownDrainGeneration;
            if ((generation == null && !Automations.isDisabled())
                    || (generation != null && generation.drainActive)
                    || cancellationBarrier != null || workerRestartSuspensions != 0
                    || !isolatedExecutors.isEmpty() || !inFlightActions.isEmpty()
                    || (!Automations.isDisabled() && enableReconciliationPending)
                    || (generation != null && !generation.pending.isEmpty())
                    || (generation != null && !generation.claims.isEmpty())
                    || !urgentReadyQueue.isEmpty()
                    || !automationQueue.isEmpty()) {
                return false;
            }
            if (generation != null) {
                for (Thread runner : generation.runners) {
                    if (runner.isAlive()) return false;
                }
            }
            try {
                return commit.commit();
            } catch (Throwable failure) {
                logger.error("Automation shutdown commit failed");
                return false;
            }
        }
    }

    /** Compatibility wrapper for callers that only need to know whether a drain existed. */
    public static boolean cancelShutdownDrain() {
        return cancelShutdownDrain(2_000L, null) != ShutdownDrainCancelResult.NO_DRAIN;
    }

    /** Caller holds {@link #lock}. */
    private static void requeueUnrunClaimLocked(DelayedAutomation item) {
        if (Automations.isDisabled()) {
            retainLatestStateReconciliationLocked(item);
            queueItems.remove(item.getQueueKey());
            return;
        }
        long resumeAtNanos = item.actionCursor == null
                ? 0L : item.actionCursor.resumeAtNanos();
        if (resumeAtNanos != 0L) {
            item.startTime = Math.max(System.nanoTime(), resumeAtNanos);
            // Edited, disabled or deleted while this attempt ran: it could only end.
            if (item.latestStatePublicationGenerations.isEmpty()
                    && Automations.queuedCursorSuperseded(item.getId(), item.actionCursor)) {
                releaseHeldRetriggerLocked(item);
                ensureWorker();
                return;
            }
        }
        if (queueItems.add(item.getQueueKey())) {
            enqueueItemLocked(item);
        } else if (resumeAtNanos != 0L && adoptPendingRetriggerLocked(item)) {
            // A yielded chain resumes; the trigger admitted while it ran waits behind it.
            enqueueItemLocked(item);
        }
        // Otherwise a newer trigger for the same automation supersedes this failed claim.
        ensureWorker();
    }

    /** Caller holds {@link #lock}. The queued item owning {@code queueKey}, or null. */
    private static DelayedAutomation queuedItemLocked(String queueKey) {
        for (DelayedAutomation item : automationQueue) {
            if (queueKey.equals(item.getQueueKey())) return item;
        }
        for (DelayedAutomation item : urgentReadyQueue) {
            if (queueKey.equals(item.getQueueKey())) return item;
        }
        for (ShutdownDrainGeneration generation : activeDrainGenerationsLocked()) {
            for (DelayedAutomation item : generation.pending) {
                if (queueKey.equals(item.getQueueKey())) return item;
            }
        }
        return null;
    }

    /**
     * Caller holds {@link #lock}. Keep ONE trigger that lands while the same automation's
     * started chain is queued (yielded in a pause or wait); it runs after that chain.
     */
    private static void holdBehindStartedChainLocked(String id, int delay) {
        DelayedAutomation chain = queuedItemLocked(id);
        if (chain == null || !chain.hasStartedCursor() || chain.heldRetrigger != null) return;
        chain.heldRetrigger = new DelayedAutomation(id, delay, null, 0L, 0L, false);
        logger.info("Holding automation re-trigger until its running chain finishes: " + id);
    }

    /**
     * Caller holds {@link #lock}. Move the plain pending run that owns {@code chain}'s key
     * behind the chain. False when the owner is anything else (including work a shutdown
     * drain already picked), which keeps it superseding.
     */
    private static boolean adoptPendingRetriggerLocked(DelayedAutomation chain) {
        DelayedAutomation pending = null;
        for (DelayedAutomation item : automationQueue) {
            if (chain.getQueueKey().equals(item.getQueueKey())) {
                pending = item;
                break;
            }
        }
        if (pending == null || !pending.isAutomation() || pending.hasStartedCursor()
                || pending.urgent
                || !pending.latestStatePublicationGenerations.isEmpty()) {
            return false;
        }
        if (!automationQueue.remove(pending)) return false;
        if (chain.heldRetrigger == null) {
            chain.heldRetrigger = pending;
            logger.info("Holding automation re-trigger until its running chain finishes: "
                    + chain.getId());
        }
        return true;
    }

    /** Caller holds {@link #lock}. Enqueue the run held behind a chain that just finished. */
    private static void releaseHeldRetriggerLocked(DelayedAutomation finished) {
        DelayedAutomation held = finished.heldRetrigger;
        if (held == null) return;
        finished.heldRetrigger = null;
        if (queueItems.contains(held.getQueueKey())) return; // a pending run already covers it
        if (!hasQueueCapacityLocked(false)) {
            logger.error("Automation queue capacity reached; rejecting: " + held.getId());
            return;
        }
        enqueueItemLocked(held);
        queueItems.add(held.getQueueKey());
        logger.info("Releasing held automation re-trigger: " + held.getId());
        ensureWorker();
    }

    /** Caller holds {@link #lock}. Discard any run held behind {@code id}'s started chain. */
    private static boolean dropHeldRetriggersLocked(String id) {
        boolean dropped = false;
        for (DelayedAutomation item : automationQueue) dropped |= dropHeldLocked(item, id);
        for (DelayedAutomation item : urgentReadyQueue) dropped |= dropHeldLocked(item, id);
        for (InFlightAction action : inFlightActions.values()) {
            dropped |= dropHeldLocked(action.item, id);
        }
        for (ShutdownDrainGeneration generation : activeDrainGenerationsLocked()) {
            for (DelayedAutomation item : generation.pending) dropped |= dropHeldLocked(item, id);
            for (DelayedAutomation item : generation.claims.values()) {
                dropped |= dropHeldLocked(item, id);
            }
        }
        return dropped;
    }

    private static boolean dropHeldLocked(DelayedAutomation item, String id) {
        if (item.heldRetrigger == null || !id.equals(item.getId())) return false;
        item.heldRetrigger = null;
        return true;
    }
}
