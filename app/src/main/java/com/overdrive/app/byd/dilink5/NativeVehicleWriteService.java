package com.overdrive.app.byd.dilink5;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shell-daemon Binder exposing only the reviewed named native vehicle actions. */
public final class NativeVehicleWriteService extends Binder {
    private static final String TAG = "NativeVehicleWrite";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final String SERVICE_NAME = "overdrive_native_vehicle";
    public static final String ACTION_REQUEST =
            "com.overdrive.app.action.REQUEST_NATIVE_VEHICLE_WRITE_BINDER";
    public static final String EXTRA_CALLBACK = "native_vehicle_write_callback";
    public static final String HANDOFF_PERMISSION =
            "com.overdrive.app.permission.NATIVE_VEHICLE_BINDER_HANDOFF";
    public static final String HANDOFF_DESCRIPTOR =
            "com.overdrive.app.vehicle.INativeVehicleWriteHandoff";
    public static final int TX_WRITE = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TX_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int TX_CAPTURE_TAILGATE_STOP_GENERATION =
            IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int TX_REGISTER_TAILGATE_STOP = IBinder.FIRST_CALL_TRANSACTION + 3;
    public static final int TX_UNREGISTER_TAILGATE_STOP = IBinder.FIRST_CALL_TRANSACTION + 4;
    public static final String STOP_CALLBACK_DESCRIPTOR =
            "com.overdrive.app.vehicle.ITailgateStopCallback";
    public static final int TX_NOTIFY_TAILGATE_STOP = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TX_OFFER = IBinder.FIRST_CALL_TRANSACTION;
    private static final int AUTOSERVICE_WRITE_TRANSACTION = 6;

    private static volatile NativeVehicleWriteService instance;
    private static volatile NativeVehicleBinderHandoff handoff;
    private static volatile BroadcastReceiver requestReceiver;

    private final Context context;
    private final NativeVehicleWriteBoundary boundary;
    private final ConcurrentMap<IBinder, CloudStopOwner> cloudStopOwners =
            new ConcurrentHashMap<>();

    private NativeVehicleWriteService(Context context) {
        this.context = context;
        attachInterface(null, NativeVehicleWriteBoundary.DESCRIPTOR);
        TrustedAppUidResolver resolver = new TrustedAppUidResolver(context);
        boundary = new NativeVehicleWriteBoundary(resolver::resolve, this::dispatchApproved);
    }

    private static synchronized NativeVehicleWriteService getBinder(Context context) {
        if (instance == null && context != null) {
            Context app = context.getApplicationContext();
            instance = new NativeVehicleWriteService(app != null ? app : context);
        }
        return instance;
    }

    public static synchronized void register(Context context) {
        if (context == null) return;
        NativeVehicleWriteService service = getBinder(context);
        if (service == null) return;
        if (handoff == null) {
            handoff = new NativeVehicleBinderHandoff(
                    listener -> registerRequestChannel(service.context, listener),
                    NativeVehicleWriteService::addToServiceManager,
                    service::offerToCallback);
        }
        if (!handoff.start()) {
            logger.warn("private native vehicle service handoff unavailable");
        }
    }

    private static boolean registerRequestChannel(Context context,
            NativeVehicleBinderHandoff.RequestListener listener) {
        if (context == null || listener == null) return false;
        if (requestReceiver != null) return true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (intent == null || !ACTION_REQUEST.equals(intent.getAction())
                        || !DiLink5Platform.isSelected()) return;
                IBinder callback = intent.getExtras() != null
                        ? intent.getExtras().getBinder(EXTRA_CALLBACK) : null;
                listener.onRequest(callback);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(ACTION_REQUEST);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, HANDOFF_PERMISSION, null,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, HANDOFF_PERMISSION, null);
            }
            requestReceiver = receiver;
            return true;
        } catch (Throwable failure) {
            logger.warn("private native vehicle request channel unavailable: "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean addToServiceManager() {
        NativeVehicleWriteService service = instance;
        if (service == null) return false;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method add;
            try {
                add = sm.getDeclaredMethod("addService", String.class, IBinder.class,
                        boolean.class, int.class);
                add.setAccessible(true);
                add.invoke(null, SERVICE_NAME, service, false, 0);
            } catch (NoSuchMethodException oldPlatform) {
                add = sm.getDeclaredMethod("addService", String.class, IBinder.class);
                add.setAccessible(true);
                add.invoke(null, SERVICE_NAME, service);
            }
            logger.info("registered private native vehicle service");
            return true;
        } catch (Throwable failure) {
            logger.warn("private native vehicle service registration unavailable: "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean offerToCallback(Object rawCallback) {
        if (!(rawCallback instanceof IBinder) || !DiLink5Platform.isSelected()) return false;
        IBinder callback = (IBinder) rawCallback;
        if (!callback.isBinderAlive()) return false;
        Parcel offer = Parcel.obtain();
        try {
            offer.writeInterfaceToken(HANDOFF_DESCRIPTOR);
            offer.writeStrongBinder(this);
            offer.writeLong(boundary.captureTailgateStopGeneration());
            boolean sent = callback.transact(TX_OFFER, offer, null, IBinder.FLAG_ONEWAY);
            if (sent) logger.info("offered private native vehicle service to requesting app");
            return sent;
        } catch (Throwable failure) {
            logger.warn("private native vehicle service offer unavailable: "
                    + failure.getClass().getSimpleName());
            return false;
        } finally {
            offer.recycle();
        }
    }

    /** Daemon STOP authority shared with manual app-process OPEN requests. */
    public static void cancelTailgateOpenRequests() {
        NativeVehicleWriteService service = instance;
        if (service != null) service.boundary.cancelTailgateOpen();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == TX_REGISTER_TAILGATE_STOP || code == TX_UNREGISTER_TAILGATE_STOP) {
            boolean accepted = false;
            try {
                int expectedUid = new TrustedAppUidResolver(context).resolve();
                StopRegistrationRequest request = decodeStopRegistration(
                        code, expectedUid, new StopRegistrationReader() {
                            @Override public int callingUid() { return Binder.getCallingUid(); }
                            @Override public boolean enforceInterface(String descriptor) {
                                data.enforceInterface(descriptor);
                                return true;
                            }
                            @Override public boolean hasGeneration() { return data.dataAvail() >= 8; }
                            @Override public long readGeneration() { return data.readLong(); }
                            @Override public Object readCallback() { return data.readStrongBinder(); }
                            @Override public int remaining() { return data.dataAvail(); }
                        });
                if (request != null) {
                    if (code == TX_REGISTER_TAILGATE_STOP) {
                        accepted = registerCloudStopOwner(request.generation, request.callback);
                    } else {
                        CloudStopOwner owner = cloudStopOwners.get(request.callback);
                        if (owner != null) {
                            owner.close();
                            accepted = true;
                        }
                    }
                }
            } catch (Throwable malformed) {
                accepted = false;
            }
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(accepted ? 1 : 0);
            }
            return true;
        }
        if (code == TX_CAPTURE_TAILGATE_STOP_GENERATION) {
            long captured = -1L;
            try {
                data.enforceInterface(NativeVehicleWriteBoundary.DESCRIPTOR);
                int expectedUid = new TrustedAppUidResolver(context).resolve();
                if (expectedUid >= 0 && Binder.getCallingUid() == expectedUid
                        && data.dataAvail() == 0) {
                    captured = boundary.captureTailgateStopGeneration();
                }
            } catch (Throwable malformed) {
                captured = -1L;
            }
            if (reply != null) {
                reply.writeNoException();
                reply.writeLong(captured);
            }
            return true;
        }
        if (code == TX_CANCEL) {
            boolean cancelled;
            try {
                cancelled = boundary.cancel(new ParcelCancellation(data, Binder.getCallingUid()));
            } catch (Throwable malformed) {
                cancelled = false;
            }
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(cancelled ? 1 : 0);
            }
            return true;
        }
        if (code != TX_WRITE) return super.onTransact(code, data, reply, flags);
        NativeVehicleWriteBoundary.Result result;
        try {
            result = boundary.handle(new ParcelRequest(data, Binder.getCallingUid()));
        } catch (Throwable malformed) {
            result = NativeVehicleWriteBoundary.Result.failure(
                    NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR);
        }
        if (reply != null) writeResult(reply, result);
        return true;
    }

    interface StopRegistrationReader {
        int callingUid();
        boolean enforceInterface(String descriptor);
        boolean hasGeneration();
        long readGeneration();
        Object readCallback();
        int remaining();
    }

    static final class StopRegistrationRequest {
        final long generation;
        final IBinder callback;

        StopRegistrationRequest(long generation, IBinder callback) {
            this.generation = generation;
            this.callback = callback;
        }
    }

    static StopRegistrationRequest decodeStopRegistration(
            int code, int expectedUid, StopRegistrationReader reader) {
        if (reader == null || expectedUid < 0
                || (code != TX_REGISTER_TAILGATE_STOP && code != TX_UNREGISTER_TAILGATE_STOP)) {
            return null;
        }
        try {
            if (reader.callingUid() != expectedUid
                    || !reader.enforceInterface(NativeVehicleWriteBoundary.DESCRIPTOR)) {
                return null;
            }
            long generation = -1L;
            if (code == TX_REGISTER_TAILGATE_STOP) {
                if (!reader.hasGeneration()) return null;
                generation = reader.readGeneration();
                if (generation < 0L) return null;
            }
            Object callback = reader.readCallback();
            if (!(callback instanceof IBinder) || reader.remaining() != 0) return null;
            return new StopRegistrationRequest(generation, (IBinder) callback);
        } catch (Throwable malformed) {
            return null;
        }
    }

    private boolean registerCloudStopOwner(long generation, IBinder callback) {
        if (generation < 0L || !callback.isBinderAlive() || !DiLink5Platform.isSelected()
                || !com.overdrive.app.config.UnifiedConfigManager
                        .isNativeVehicleManualProfileEnabled()) {
            return false;
        }
        CloudStopOwner owner = new CloudStopOwner(callback);
        if (cloudStopOwners.putIfAbsent(callback, owner) != null) return false;
        try {
            callback.linkToDeath(owner, 0);
            if (!callback.isBinderAlive() || !owner.registration.register(generation)) {
                owner.close();
                callback.unlinkToDeath(owner, 0);
                return false;
            }
            return true;
        } catch (Throwable unavailable) {
            owner.close();
            return false;
        }
    }

    private final class CloudStopOwner implements IBinder.DeathRecipient {
        private final IBinder callback;
        private final NativeVehicleWriteBoundary.TailgateStopOwner registration;

        CloudStopOwner(IBinder callback) {
            this.callback = callback;
            registration = boundary.createTailgateStopOwner(
                    callback, this::notifyStop, this::release);
        }

        private void notifyStop() {
            Parcel notification = Parcel.obtain();
            try {
                notification.writeInterfaceToken(STOP_CALLBACK_DESCRIPTOR);
                callback.transact(TX_NOTIFY_TAILGATE_STOP, notification, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable unavailable) {
                // The owner has disappeared; the daemon's epoch is already revoked.
            } finally {
                notification.recycle();
            }
        }

        @Override public void binderDied() {
            close();
        }

        void close() {
            registration.close();
        }

        private void release() {
            cloudStopOwners.remove(callback, this);
            try {
                callback.unlinkToDeath(this, 0);
            } catch (Throwable unavailable) {
            }
        }
    }

    private NativeVehicleWriteBoundary.TransportReply dispatchApproved(
            NativeVehicleAction action,
            NativeVehicleWriteBoundary.RequestAuthority authority,
            NativeVehicleWriteBoundary.Cancellation cancellation) {
        if (!registeredAction(action)) {
            return NativeVehicleWriteBoundary.TransportReply.failed(
                    NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR);
        }
        IBinder autoservice = resolveAutoservice();
        if (autoservice == null || !autoservice.isBinderAlive()) {
            return NativeVehicleWriteBoundary.TransportReply.failed(
                    NativeVehicleWriteBoundary.Kind.DEAD_SERVICE);
        }
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            String descriptor = autoservice.getInterfaceDescriptor();
            if (descriptor == null || descriptor.isEmpty()) {
                return NativeVehicleWriteBoundary.TransportReply.failed(
                        NativeVehicleWriteBoundary.Kind.DEAD_SERVICE);
            }
            request.writeInterfaceToken(descriptor);
            request.writeInt(action.device);
            request.writeInt(action.featureId);
            request.writeInt(action.value);
            return NativeVehicleWriteBoundary.dispatchAtFinalBoundary(
                    authority,
                    cancellation,
                    () -> DiLink5Platform.isSelected()
                            && com.overdrive.app.config.UnifiedConfigManager
                                    .isNativeVehicleManualProfileEnabled()
                            && registeredAction(action)
                            && safetyAllows(action),
                    () -> {
                        if (!autoservice.isBinderAlive()) {
                            return NativeVehicleWriteBoundary.TransportReply.failed(
                                    NativeVehicleWriteBoundary.Kind.DEAD_SERVICE);
                        }
                        final boolean transacted;
                        try {
                            transacted = autoservice.transact(
                                    AUTOSERVICE_WRITE_TRANSACTION, request, response, 0);
                        } catch (DeadObjectException dead) {
                            return NativeVehicleWriteBoundary.TransportReply.failed(
                                    NativeVehicleWriteBoundary.Kind.DEAD_SERVICE);
                        }
                        if (!transacted) {
                            return NativeVehicleWriteBoundary.TransportReply.failed(
                                    NativeVehicleWriteBoundary.Kind.TRANSACT_FALSE);
                        }
                        response.setDataPosition(0);
                        if (response.dataAvail() < 4) {
                            return NativeVehicleWriteBoundary.TransportReply.failed(
                                    NativeVehicleWriteBoundary.Kind.MISSING_STATUS);
                        }
                        int status = response.readInt();
                        return response.dataAvail() >= 4
                                ? NativeVehicleWriteBoundary.TransportReply.handled(
                                        status, response.readInt())
                                : NativeVehicleWriteBoundary.TransportReply.handled(status);
                    });
        } catch (DeadObjectException dead) {
            return NativeVehicleWriteBoundary.TransportReply.failed(
                    NativeVehicleWriteBoundary.Kind.DEAD_SERVICE);
        } catch (Throwable failure) {
            return NativeVehicleWriteBoundary.TransportReply.failed(
                    NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR);
        } finally {
            request.recycle();
            response.recycle();
        }
    }

    private static boolean registeredAction(NativeVehicleAction action) {
        return action != null && NativeVehicleAction.fromTuple(
                action.device, action.featureId, action.value) == action;
    }

    private boolean safetyAllows(NativeVehicleAction action) {
        BydDataCollector collector = BydDataCollector.getInstance();
        if (action.safety == NativeVehicleAction.Safety.TAILGATE_OPEN) {
            if (DrivingSafetyGuard.getActionBlockReason(DrivingSafetyGuard.GUARD_TRUNK) != null) {
                return false;
            }
            return collector.readDoorLockState() == BydDataCollector.DOOR_STATE_UNLOCK;
        }
        if (action.safety == NativeVehicleAction.Safety.PARKED) {
            return DrivingSafetyGuard.getActionBlockReason(DrivingSafetyGuard.GUARD_TRUNK) == null;
        }
        if (action.safety == NativeVehicleAction.Safety.SUNROOF_OPENING) {
            return isKnownSunroofOpeningSpeedSafe(
                    com.overdrive.app.byd.DiLink5DirectTelemetry.readFreshSpeedKmh());
        }
        return true;
    }

    public static boolean isKnownSunroofOpeningSpeedSafe(double speedKmh) {
        return !Double.isNaN(speedKmh) && !Double.isInfinite(speedKmh)
                && speedKmh >= 0.0 && speedKmh <= 80.0;
    }

    private static IBinder resolveAutoservice() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object value = sm.getMethod("getService", String.class)
                    .invoke(null, "autoservice");
            return value instanceof IBinder ? (IBinder) value : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static void writeResult(Parcel reply, NativeVehicleWriteBoundary.Result result) {
        reply.writeNoException();
        reply.writeInt(result.kind.ordinal());
        reply.writeInt(result.hasStatus ? 1 : 0);
        if (result.hasStatus) reply.writeInt(result.status);
        reply.writeInt(result.hasOptionalWord ? 1 : 0);
        if (result.hasOptionalWord) reply.writeInt(result.optionalWord);
        reply.writeInt(0);
    }

    interface AuthoritySuffixReader {
        String readString();
        long readLong();
        Object readOwner();
        int remaining();
    }

    static NativeVehicleWriteBoundary.RequestAuthority decodeAuthoritySuffix(
            AuthoritySuffixReader reader) {
        if (reader == null) return null;
        try {
            String generation = reader.readString();
            long deadline = reader.readLong();
            long requestId = reader.readLong();
            Object rawOwner = reader.readOwner();
            long tailgateStopGeneration = reader.readLong();
            if (!(rawOwner instanceof IBinder) || reader.remaining() != 0) return null;
            return new ParcelAuthority(
                    generation,
                    deadline,
                    requestId,
                    (IBinder) rawOwner,
                    tailgateStopGeneration);
        } catch (Throwable malformed) {
            return null;
        }
    }

    private static final class ParcelAuthority
            implements NativeVehicleWriteBoundary.RequestAuthority {
        private final String expectedGeneration;
        private final long deadlineElapsedMs;
        private final long requestId;
        private final IBinder owner;
        private final long tailgateStopGeneration;

        ParcelAuthority(String expectedGeneration, long deadlineElapsedMs,
                long requestId, IBinder owner, long tailgateStopGeneration) {
            this.expectedGeneration = expectedGeneration;
            this.deadlineElapsedMs = deadlineElapsedMs;
            this.requestId = requestId;
            this.owner = owner;
            this.tailgateStopGeneration = tailgateStopGeneration;
        }

        @Override public long requestId() { return requestId; }
        @Override public Object owner() { return owner; }
        @Override public long tailgateStopGeneration() { return tailgateStopGeneration; }

        @Override public boolean current() {
            try {
                DiLink5Platform.refreshActiveMode();
                return expectedGeneration != null && !expectedGeneration.isEmpty()
                        && deadlineElapsedMs > 0L
                        && android.os.SystemClock.elapsedRealtime() < deadlineElapsedMs
                        && owner != null && owner.isBinderAlive()
                        && DiLink5Platform.isSelected()
                        && DiLink5Platform.matchesActiveModeGeneration(expectedGeneration);
            } catch (Throwable unavailable) {
                return false;
            }
        }
    }

    private static final class ParcelRequest implements NativeVehicleWriteBoundary.Request {
        private final Parcel data;
        private final int callingUid;

        ParcelRequest(Parcel data, int callingUid) {
            this.data = data;
            this.callingUid = callingUid;
        }

        @Override public int callingUid() { return callingUid; }

        @Override public boolean enforceInterface(String descriptor) {
            try {
                data.enforceInterface(descriptor);
                return true;
            } catch (SecurityException rejected) {
                return false;
            }
        }

        @Override public boolean hasInt() {
            return data.dataAvail() >= 4;
        }

        @Override public int readInt() {
            return data.readInt();
        }

        @Override public NativeVehicleWriteBoundary.RequestAuthority readAuthority() {
            return decodeAuthoritySuffix(new AuthoritySuffixReader() {
                @Override public String readString() { return data.readString(); }
                @Override public long readLong() { return data.readLong(); }
                @Override public Object readOwner() { return data.readStrongBinder(); }
                @Override public int remaining() { return data.dataAvail(); }
            });
        }
    }

    private static final class ParcelCancellation
            implements NativeVehicleWriteBoundary.CancellationRequest {
        private final Parcel data;
        private final int callingUid;
        private boolean parsed;
        private boolean complete;
        private long requestId;
        private IBinder owner;

        ParcelCancellation(Parcel data, int callingUid) {
            this.data = data;
            this.callingUid = callingUid;
        }

        @Override public int callingUid() { return callingUid; }

        @Override public boolean enforceInterface(String descriptor) {
            try {
                data.enforceInterface(descriptor);
                return true;
            } catch (SecurityException rejected) {
                return false;
            }
        }

        @Override public boolean complete() {
            if (parsed) return complete;
            parsed = true;
            try {
                requestId = data.readLong();
                owner = data.readStrongBinder();
                complete = owner != null && data.dataAvail() == 0;
            } catch (Throwable malformed) {
                complete = false;
            }
            return complete;
        }

        @Override public long requestId() { return requestId; }
        @Override public Object owner() { return owner; }
    }
}
