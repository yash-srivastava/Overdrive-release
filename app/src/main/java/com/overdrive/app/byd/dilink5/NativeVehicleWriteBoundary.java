package com.overdrive.app.byd.dilink5;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure admission, cancellation ownership, and response classification for the Binder boundary. */
public final class NativeVehicleWriteBoundary {
    public static final String DESCRIPTOR = "com.overdrive.app.vehicle.INativeVehicleWrite";
    private static final int MAX_EARLY_CANCELLATIONS = 64;
    private static final int MAX_TAILGATE_STOP_LISTENERS = 64;

    public interface Request {
        int callingUid();
        boolean enforceInterface(String descriptor);
        boolean hasInt();
        int readInt();
        RequestAuthority readAuthority();
    }

    public interface CancellationRequest {
        int callingUid();
        boolean enforceInterface(String descriptor);
        boolean complete();
        long requestId();
        Object owner();
    }

    public interface RequestAuthority {
        long requestId();
        Object owner();
        boolean current();
        default long tailgateStopGeneration() { return -1L; }
    }

    public interface Cancellation {
        boolean current();
        <T> T callIfCurrent(Callable<T> action, T cancelledResult) throws Exception;
    }

    public interface TrustedUid {
        int resolve();
    }

    public interface Dispatcher {
        TransportReply dispatch(NativeVehicleAction action, RequestAuthority authority,
                Cancellation cancellation);
    }

    public interface FinalAdmission {
        boolean allows();
    }

    /** One owned callback; explicit close, Binder death, and STOP share its cleanup path. */
    public final class TailgateStopOwner implements Runnable, AutoCloseable {
        private final Object owner;
        private final Runnable notification;
        private final Runnable cleanup;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TailgateStopOwner(Object owner, Runnable notification, Runnable cleanup) {
            this.owner = owner;
            this.notification = notification;
            this.cleanup = cleanup;
        }

        public boolean register(long generation) {
            if (closed.get()) return false;
            if (!registerTailgateStopListener(generation, owner, this)) {
                close();
                return false;
            }
            if (closed.get()) {
                unregisterTailgateStopListener(owner, this);
                return false;
            }
            return true;
        }

        @Override public void run() {
            if (closed.get()) return;
            try {
                if (notification != null) notification.run();
            } finally {
                close();
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            unregisterTailgateStopListener(owner, this);
            if (cleanup != null) cleanup.run();
        }
    }

    public TailgateStopOwner createTailgateStopOwner(Object owner, Runnable notification,
            Runnable cleanup) {
        return new TailgateStopOwner(owner, notification, cleanup);
    }

    /** Linearizes the last authority/safety checks with cancellation and one transaction. */
    public static TransportReply dispatchAtFinalBoundary(
            RequestAuthority authority,
            Cancellation cancellation,
            FinalAdmission finalAdmission,
            Callable<TransportReply> transaction) {
        if (authority == null || cancellation == null || finalAdmission == null
                || transaction == null) {
            return TransportReply.failed(Kind.TRANSPORT_ERROR);
        }
        try {
            return cancellation.callIfCurrent(() -> {
                if (!authority.current()) return TransportReply.failed(Kind.STALE_REQUEST);
                if (!finalAdmission.allows()) return TransportReply.handled(-1);
                if (!authority.current()) return TransportReply.failed(Kind.STALE_REQUEST);
                return transaction.call();
            }, TransportReply.failed(Kind.CANCELLED));
        } catch (Throwable failure) {
            return TransportReply.failed(Kind.TRANSPORT_ERROR);
        }
    }

    public enum Kind {
        CALLER_REJECTED,
        TOKEN_REJECTED,
        TRUNCATED,
        UNKNOWN_ACTION,
        STALE_REQUEST,
        CANCELLED,
        TRANSACT_FALSE,
        MISSING_STATUS,
        DEAD_SERVICE,
        TRANSPORT_ERROR,
        REJECTED,
        ACCEPTED
    }

    public static final class TransportReply {
        public final Kind failure;
        public final boolean hasStatus;
        public final int status;
        public final boolean hasOptionalWord;
        public final int optionalWord;

        private TransportReply(Kind failure, boolean hasStatus, int status,
                boolean hasOptionalWord, int optionalWord) {
            this.failure = failure;
            this.hasStatus = hasStatus;
            this.status = status;
            this.hasOptionalWord = hasOptionalWord;
            this.optionalWord = optionalWord;
        }

        public static TransportReply handled(int status) {
            return new TransportReply(null, true, status, false, 0);
        }

        public static TransportReply handled(int status, int optionalWord) {
            return new TransportReply(null, true, status, true, optionalWord);
        }

        public static TransportReply failed(Kind failure) {
            if (failure != Kind.STALE_REQUEST && failure != Kind.CANCELLED
                    && failure != Kind.TRANSACT_FALSE && failure != Kind.MISSING_STATUS
                    && failure != Kind.DEAD_SERVICE && failure != Kind.TRANSPORT_ERROR) {
                throw new IllegalArgumentException("invalid transport failure");
            }
            return new TransportReply(failure, false, 0, false, 0);
        }
    }

    public static final class Result {
        public final Kind kind;
        public final boolean hasStatus;
        public final int status;
        public final boolean hasOptionalWord;
        public final int optionalWord;

        private Result(Kind kind, boolean hasStatus, int status,
                boolean hasOptionalWord, int optionalWord) {
            this.kind = kind;
            this.hasStatus = hasStatus;
            this.status = status;
            this.hasOptionalWord = hasOptionalWord;
            this.optionalWord = optionalWord;
        }

        public static Result failure(Kind kind) {
            return new Result(kind, false, 0, false, 0);
        }

        public boolean accepted() {
            return kind == Kind.ACCEPTED;
        }

        /** A write reply never constitutes an observation of physical state. */
        public boolean observed() {
            return false;
        }
    }

    private final class RequestState implements Cancellation {
        final long requestId;
        final Object owner;
        final NativeVehicleAction action;
        final long tailgateStopGeneration;
        boolean cancelled;
        boolean finished;

        RequestState(long requestId, Object owner, NativeVehicleAction action,
                long tailgateStopGeneration, boolean cancelled) {
            this.requestId = requestId;
            this.owner = owner;
            this.action = action;
            this.tailgateStopGeneration = tailgateStopGeneration;
            this.cancelled = cancelled;
        }

        @Override public boolean current() {
            synchronized (mutationLock) {
                return currentUnserialized();
            }
        }

        private boolean currentUnserialized() {
            return !cancelled && !finished
                    && (action != NativeVehicleAction.TAILGATE_OPEN
                        || tailgateStopGeneration == currentTailgateStopGeneration);
        }

        @Override public <T> T callIfCurrent(
                Callable<T> actionCall, T cancelledResult) throws Exception {
            synchronized (mutationLock) {
                if (!currentUnserialized() || actionCall == null) return cancelledResult;
                return actionCall.call();
            }
        }

        void cancel() {
            synchronized (mutationLock) { cancelled = true; }
        }

        void finish() {
            synchronized (mutationLock) { finished = true; }
        }
    }

    private final TrustedUid trustedUid;
    private final Dispatcher dispatcher;
    /** Orders STOP generation changes with registration and the final privileged transaction. */
    private final Object mutationLock = new Object();
    private final Map<Long, RequestState> active = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Object> earlyCancellations = new LinkedHashMap<>();
    private final Map<Object, Runnable> tailgateStopListeners = new LinkedHashMap<>();
    private long currentTailgateStopGeneration;

    public NativeVehicleWriteBoundary(TrustedUid trustedUid, Dispatcher dispatcher) {
        this.trustedUid = trustedUid;
        this.dispatcher = dispatcher;
    }

    public Result handle(Request request) {
        int expectedUid = trustedUid != null ? trustedUid.resolve() : -1;
        if (request == null || expectedUid < 0 || request.callingUid() != expectedUid) {
            return Result.failure(Kind.CALLER_REJECTED);
        }
        if (!request.enforceInterface(DESCRIPTOR)) {
            return Result.failure(Kind.TOKEN_REJECTED);
        }
        if (!request.hasInt()) return Result.failure(Kind.TRUNCATED);
        int device = request.readInt();
        if (!request.hasInt()) return Result.failure(Kind.TRUNCATED);
        int featureId = request.readInt();
        if (!request.hasInt()) return Result.failure(Kind.TRUNCATED);
        int value = request.readInt();
        NativeVehicleAction action = NativeVehicleAction.fromTuple(device, featureId, value);
        if (action == null) return Result.failure(Kind.UNKNOWN_ACTION);

        RequestAuthority authority;
        try {
            authority = request.readAuthority();
        } catch (Throwable malformed) {
            return Result.failure(Kind.TRUNCATED);
        }
        if (authority == null || authority.requestId() <= 0L || authority.owner() == null) {
            return Result.failure(Kind.TRUNCATED);
        }
        RequestState state = begin(
                authority.requestId(), authority.owner(), action,
                authority.tailgateStopGeneration());
        if (state == null) return Result.failure(Kind.CANCELLED);
        if (!state.current()) {
            finish(state);
            return Result.failure(Kind.CANCELLED);
        }
        try {
            if (!authority.current()) return Result.failure(Kind.STALE_REQUEST);
            TransportReply reply;
            try {
                reply = dispatcher != null ? dispatcher.dispatch(action, authority, state) : null;
            } catch (Throwable failure) {
                return Result.failure(Kind.TRANSPORT_ERROR);
            }
            if (reply == null) return Result.failure(Kind.TRANSPORT_ERROR);
            if (reply.failure != null) return Result.failure(reply.failure);
            if (!reply.hasStatus) return Result.failure(Kind.MISSING_STATUS);
            return new Result(reply.status >= 0 ? Kind.ACCEPTED : Kind.REJECTED,
                    true, reply.status, reply.hasOptionalWord, reply.optionalWord);
        } finally {
            finish(state);
        }
    }

    /** Records cancellation before or after write admission, bound to the request owner. */
    public boolean cancel(CancellationRequest request) {
        int expectedUid = trustedUid != null ? trustedUid.resolve() : -1;
        if (request == null || expectedUid < 0 || request.callingUid() != expectedUid
                || !request.enforceInterface(DESCRIPTOR) || !request.complete()
                || request.requestId() <= 0L || request.owner() == null) {
            return false;
        }
        synchronized (mutationLock) {
            RequestState state = active.get(request.requestId());
            if (state != null) {
                if (!sameOwner(state.owner, request.owner())) return false;
                state.cancel();
                return true;
            }
            earlyCancellations.put(request.requestId(), request.owner());
            while (earlyCancellations.size() > MAX_EARLY_CANCELLATIONS) {
                Long eldest = earlyCancellations.keySet().iterator().next();
                earlyCancellations.remove(eldest);
            }
            return true;
        }
    }

    private RequestState begin(long requestId, Object owner, NativeVehicleAction action,
            long tailgateStopGeneration) {
        synchronized (mutationLock) {
            if (active.containsKey(requestId)) return null;
            Object earlyOwner = earlyCancellations.remove(requestId);
            boolean staleTailgateOpen = action == NativeVehicleAction.TAILGATE_OPEN
                    && (tailgateStopGeneration < 0L
                        || tailgateStopGeneration != currentTailgateStopGeneration);
            RequestState state = new RequestState(
                    requestId, owner, action, tailgateStopGeneration,
                    staleTailgateOpen || (earlyOwner != null && sameOwner(earlyOwner, owner)));
            active.put(requestId, state);
            return state;
        }
    }

    private void finish(RequestState state) {
        synchronized (mutationLock) {
            if (active.get(state.requestId) == state) active.remove(state.requestId);
            state.finished = true;
        }
    }

    /** Snapshot carried by a manual tailgate OPEN across the app-process bridge. */
    public long captureTailgateStopGeneration() {
        synchronized (mutationLock) { return currentTailgateStopGeneration; }
    }

    /** Registration and STOP share the same authority boundary; late registration fails closed. */
    public boolean registerTailgateStopListener(long expectedGeneration, Object owner,
            Runnable listener) {
        synchronized (mutationLock) {
            if (expectedGeneration < 0L || expectedGeneration != currentTailgateStopGeneration
                    || owner == null || listener == null || tailgateStopListeners.containsKey(owner)
                    || tailgateStopListeners.size() >= MAX_TAILGATE_STOP_LISTENERS) {
                return false;
            }
            tailgateStopListeners.put(owner, listener);
            return true;
        }
    }

    public void unregisterTailgateStopListener(Object owner) {
        synchronized (mutationLock) { tailgateStopListeners.remove(owner); }
    }

    public void unregisterTailgateStopListener(Object owner, Runnable listener) {
        synchronized (mutationLock) {
            if (tailgateStopListeners.get(owner) == listener) {
                tailgateStopListeners.remove(owner);
            }
        }
    }

    /** Cancels every active OPEN and fences requests that have not registered yet. */
    public void cancelTailgateOpen() {
        List<Runnable> listeners;
        synchronized (mutationLock) {
            currentTailgateStopGeneration = currentTailgateStopGeneration == Long.MAX_VALUE
                    ? 0L : currentTailgateStopGeneration + 1L;
            for (RequestState state : active.values()) {
                if (state.action == NativeVehicleAction.TAILGATE_OPEN) state.cancelled = true;
            }
            listeners = new ArrayList<>(tailgateStopListeners.values());
            tailgateStopListeners.clear();
        }
        // A callback may unregister or enter Binder IPC. Never invoke it under mutationLock.
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable unavailableOwner) {
                // Other owners must still be revoked when one process has disappeared.
            }
        }
    }

    private static boolean sameOwner(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
