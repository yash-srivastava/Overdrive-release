package com.overdrive.app.byd.dilink5;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client of the DiLink 5 (Desay SV) {@code QnxMessage} binder service
 * ({@code com.ts.lib.qnx.IQnx}, hosted by {@code com.ts.qnxapp}), which
 * relays Android requests to QNX over FDBus. Usable from the shell daemons:
 * it needs no Context, only {@code ServiceManager}.
 *
 * <p>The transport is private on purpose. The public surface is one method
 * per operation that has been verified on a vehicle; add a new operation as
 * its own named method rather than exposing raw message types.
 *
 * <p>Thread-safe. Survives a restart of the service: the binder and the
 * answer-callback registrations are re-established on the next call.
 */
public final class QnxMessageBus {

    private static final String TAG = "QnxMessageBus";

    /** QNX accepted the request. */
    public static final int RESULT_OK = 1;
    /** QNX rejected the request. */
    public static final int RESULT_FAIL = 0;
    /** Request was relayed, but QNX did not answer in time. */
    public static final int RESULT_NO_ANSWER = -102;
    /** The QnxMessage service is not present on this firmware. */
    public static final int RESULT_UNAVAILABLE = -103;
    /** The binder call itself failed. */
    public static final int RESULT_SEND_FAILED = -104;

    private static final String SERVICE = "QnxMessage";
    private static final String DESCRIPTOR = "com.ts.lib.qnx.IQnx";
    private static final String CALLBACK_DESCRIPTOR = "com.ts.lib.qnx.IQnxCallBack";
    private static final int TX_SEND_MESSAGE = 1;
    private static final int TX_REGISTER_CALLBACK = 4;
    private static final int CALLBACK_TX_ON_MESSAGE_CHANGED = 1;
    private static final String KEY_MSG_TYPE = "msgType";
    private static final String KEY_SUB_TYPE = "subtype";
    private static final String KEY_RESULT_NUMBER = "resultNumber";

    // Common vehicle-network control (OTA link): the request the OTA updater
    // uses to keep the vehicle network awake while it flashes a parked car.
    private static final int TYPE_OTA = 8;
    private static final int OTA_NETWORK_CONTROL = 23;
    private static final String KEY_NETWORK_CONTROL_STATE = "networkControlState";
    private static final int NETWORK_CONTROL_START = 0;
    private static final int NETWORK_CONTROL_STOP = 1;

    private static final long ANSWER_TIMEOUT_MS = 4_000L;

    private static final QnxMessageBus INSTANCE = new QnxMessageBus();

    public static QnxMessageBus get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final Answers answers = new Answers();
    private final Set<Long> registered = new HashSet<>();
    private IBinder binder;

    private QnxMessageBus() {}

    /** Whether this firmware exposes the QnxMessage service. */
    public boolean isAvailable() {
        return service() != null;
    }

    /**
     * Asks QNX to keep (START) or release (STOP) the common vehicle network.
     * Verified on a BYD Shark 6: both answer {@link #RESULT_OK}.
     *
     * @return a {@code RESULT_*} code.
     */
    public int setVehicleNetworkHold(boolean hold) {
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_NETWORK_CONTROL_STATE,
                hold ? NETWORK_CONTROL_START : NETWORK_CONTROL_STOP);
        return request(TYPE_OTA, OTA_NETWORK_CONTROL, bundle);
    }

    // ── Transport ─────────────────────────────────────────────────────────

    private int request(int msgType, int subType, Bundle bundle) {
        IBinder service = service();
        if (service == null) return RESULT_UNAVAILABLE;
        long key = key(msgType, subType);
        if (!ensureRegistered(service, msgType)) return RESULT_SEND_FAILED;

        long seen = answers.count(key);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(msgType);
            data.writeInt(subType);
            data.writeString(null);
            data.writeInt(1);
            bundle.writeToParcel(data, 0);
            if (!service.transact(TX_SEND_MESSAGE, data, reply, 0)) {
                dropBinder(service);
                return RESULT_SEND_FAILED;
            }
            reply.readException();
            if (reply.readInt() == 0) return RESULT_SEND_FAILED;
        } catch (Throwable t) {
            Log.w(TAG, "sendMessage(" + msgType + "," + subType + ") failed: " + t.getMessage());
            dropBinder(service);
            return RESULT_SEND_FAILED;
        } finally {
            reply.recycle();
            data.recycle();
        }
        Bundle answer = answers.awaitAfter(key, seen, ANSWER_TIMEOUT_MS);
        return answer != null ? answer.getInt(KEY_RESULT_NUMBER, RESULT_FAIL) : RESULT_NO_ANSWER;
    }

    private IBinder service() {
        synchronized (lock) {
            if (binder != null && binder.isBinderAlive()) return binder;
            binder = null;
            registered.clear();
            try {
                binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class)
                        .invoke(null, SERVICE);
            } catch (Throwable t) {
                Log.w(TAG, "ServiceManager lookup failed: " + t.getMessage());
            }
            return binder;
        }
    }

    /**
     * Registers the answer callback for every subtype of {@code msgType}. The
     * service only delivers answers to callbacks registered with subtype 0
     * (as the OTA updater does); a subtype-specific registration hears nothing.
     */
    private boolean ensureRegistered(IBinder service, int msgType) {
        synchronized (lock) {
            long key = key(msgType, 0);
            if (registered.contains(key)) return true;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(msgType);
                data.writeInt(0);
                data.writeStrongBinder(answers);
                service.transact(TX_REGISTER_CALLBACK, data, reply, 0);
                reply.readException();
                registered.add(key);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "registerCallback(" + msgType + ",0) failed: " + t.getMessage());
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    private void dropBinder(IBinder service) {
        synchronized (lock) {
            if (binder == service) {
                binder = null;
                registered.clear();
            }
        }
    }

    private static long key(int msgType, int subType) {
        return ((long) msgType << 32) | (subType & 0xffffffffL);
    }

    /** IQnxCallBack: keeps the latest answer per (msgType, subType). */
    private static final class Answers extends Binder {
        private final Map<Long, Bundle> latest = new HashMap<>();
        private final Map<Long, Long> counts = new HashMap<>();

        Answers() {
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        synchronized long count(long key) {
            Long c = counts.get(key);
            return c == null ? 0L : c;
        }

        synchronized Bundle awaitAfter(long key, long seen, long timeoutMs) {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (count(key) <= seen) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return null;
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return latest.get(key);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code != CALLBACK_TX_ON_MESSAGE_CHANGED) return false;
            data.enforceInterface(CALLBACK_DESCRIPTOR);
            Bundle bundle = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
            if (bundle != null) {
                long key = key(bundle.getInt(KEY_MSG_TYPE, -1), bundle.getInt(KEY_SUB_TYPE, -1));
                synchronized (this) {
                    latest.put(key, bundle);
                    counts.put(key, count(key) + 1);
                    notifyAll();
                }
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }
}
