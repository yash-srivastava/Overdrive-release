package com.overdrive.app.navmap.cluster;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * App-process transport for the DI5 OEM cluster service, which accepts only bound app clients.
 * Exported behind android.permission.DUMP, held by the shell-UID daemon but not ordinary apps.
 * Every wait is capped by the request deadline, well inside the broadcast's goAsync window.
 */
public final class ClusterCommReceiver extends BroadcastReceiver {
    private static final String TAG = "ClusterComm";
    private static final long BIND_WAIT_MS = 2_500L, DESCRIPTOR_WAIT_MS = 1_000L;
    private static final Link LINK = new Link();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ClusterCommContract.ACTION.equals(intent.getAction())) return;
        final ResultReceiver reply;
        try {
            reply = intent.getParcelableExtra(ClusterCommContract.EXTRA_REPLY);
        } catch (Throwable malformed) {
            return;
        }
        if (reply == null) return;
        final List<Bundle> commands;
        final long deadline;
        try {
            commands = validated(intent.getParcelableArrayListExtra(ClusterCommContract.EXTRA_COMMANDS));
            deadline = intent.getLongExtra(ClusterCommContract.EXTRA_DEADLINE, 0L);
        } catch (Throwable malformed) {
            send(reply, ClusterCommContract.RESULT_BAD_REQUEST, "", 0);
            return;
        }
        if (commands == null) {
            send(reply, ClusterCommContract.RESULT_BAD_REQUEST, "", 0);
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        // Keeps a process started just for this broadcast alive until the reply is sent.
        final PendingResult pending = goAsync();
        try {
            LINK.worker.execute(() -> {
                try {
                    LINK.run(app, commands, deadline, reply);
                } catch (Throwable failure) {
                    send(reply, ClusterCommContract.RESULT_FAILED, "", 0);
                } finally {
                    if (pending != null) pending.finish();
                }
            });
        } catch (Throwable rejected) {
            send(reply, ClusterCommContract.RESULT_FAILED, "", 0);
            if (pending != null) pending.finish();
        }
    }

    private static List<Bundle> validated(ArrayList<?> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > ClusterCommContract.MAX_COMMANDS) return null;
        ArrayList<Bundle> commands = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Bundle) || !ClusterCommContract.validCommand((Bundle) item)) return null;
            commands.add((Bundle) item);
        }
        return commands;
    }

    private static void send(ResultReceiver reply, int code, String token, int sent) {
        Bundle data = new Bundle();
        data.putString(ClusterCommContract.REPLY_TOKEN, token);
        data.putInt(ClusterCommContract.REPLY_SENT, sent);
        try { reply.send(code, data); } catch (Throwable ignored) {}
    }

    /** The descriptor call is synchronous; a wedged service must not stall the worker. */
    private static String descriptor(IBinder binder, long waitMs) {
        FutureTask<String> query = new FutureTask<>(binder::getInterfaceDescriptor);
        Thread thread = new Thread(query, "ClusterCommDescriptor");
        thread.setDaemon(true);
        try {
            thread.start();
            return query.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (Throwable unknown) {
            return null;
        }
    }

    private static final class Target {
        final IBinder binder;
        final long generation;
        final int failure;

        Target(IBinder binder, long generation, int failure) {
            this.binder = binder;
            this.generation = generation;
            this.failure = failure;
        }
    }

    /** All calls except the ServiceConnection callbacks run on {@link #worker}. */
    private static final class Link {
        final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ClusterCommLink");
            thread.setDaemon(true);
            return thread;
        });
        private final String nonce = Long.toHexString(new SecureRandom().nextLong());
        private final Object lock = new Object();
        /** The live binding; callbacks from any earlier binding are ignored. */
        private Connection connection;
        private Context boundContext;
        private IBinder binder;
        private long generation;
        private boolean nullBinding, bindingDied;
        private CountDownLatch connected = new CountDownLatch(1);
        private long checkedGeneration = -1, rejectedGeneration = -1;

        private final class Connection implements ServiceConnection {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (lock) {
                    if (this != connection) return;
                    binder = service;
                    generation++;
                    connected.countDown();
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                synchronized (lock) {
                    if (this != connection) return;
                    binder = null;
                    renewLatchLocked();
                }
            }

            @Override public void onBindingDied(ComponentName name) {
                synchronized (lock) {
                    if (this != connection) return;
                    binder = null;
                    bindingDied = true;
                    connected.countDown();
                }
            }

            /** Older framework versions also report a null binding right after a dead one. */
            @Override public void onNullBinding(ComponentName name) {
                synchronized (lock) {
                    if (this != connection || bindingDied) return;
                    nullBinding = true;
                    connected.countDown();
                }
            }
        }

        /** A latch is replaced only once signalled, so a waiter is never left on a stale one. */
        private void renewLatchLocked() {
            if (connected.getCount() == 0) connected = new CountDownLatch(1);
        }

        void run(Context context, List<Bundle> commands, long deadline, ResultReceiver reply) {
            if (SystemClock.elapsedRealtime() > deadline) {
                send(reply, ClusterCommContract.RESULT_EXPIRED, "", 0);
                return;
            }
            Target target = connect(context, deadline);
            if (target.failure >= 0) {
                send(reply, target.failure, "", 0);
                return;
            }
            String token = nonce + "/" + target.generation;
            if (checkedGeneration != target.generation) {
                long wait = Math.min(DESCRIPTOR_WAIT_MS, deadline - SystemClock.elapsedRealtime());
                if (wait <= 0) {
                    send(reply, ClusterCommContract.RESULT_EXPIRED, token, 0);
                    return;
                }
                // Once per binder; an unknown answer proceeds, since every call carries the token.
                checkedGeneration = target.generation;
                String descriptor = descriptor(target.binder, wait);
                // A failed descriptor transaction yields "" rather than an exception.
                boolean known = descriptor != null && !descriptor.isEmpty();
                if (known && !ClusterCommContract.DESCRIPTOR.equals(descriptor)) {
                    Log.w(TAG, "unexpected cluster service interface: " + descriptor);
                    rejectedGeneration = target.generation;
                } else if (!known && !target.binder.isBinderAlive()) {
                    drop(target);
                    send(reply, ClusterCommContract.RESULT_SERVICE_DIED, token, 0);
                    return;
                }
            }
            if (rejectedGeneration == target.generation) {
                send(reply, ClusterCommContract.RESULT_UNSUPPORTED_INTERFACE, token, 0);
                return;
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                send(reply, ClusterCommContract.RESULT_EXPIRED, token, 0);
                return;
            }
            int sent = 0;
            for (Bundle command : commands) {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(ClusterCommContract.DESCRIPTOR);
                    data.writeInt(ClusterCommContract.COMMAND_NAVI);
                    data.writeInt(1);
                    command.writeToParcel(data, 0);
                    target.binder.transact(ClusterCommContract.TRANSACTION_SEND_COMMAND,
                            data, null, IBinder.FLAG_ONEWAY);
                    sent++;
                } catch (DeadObjectException failed) {
                    // Also raised for a full async buffer on a live binder; drop only a dead one.
                    boolean dead = !target.binder.isBinderAlive();
                    if (dead) drop(target);
                    send(reply, dead ? ClusterCommContract.RESULT_SERVICE_DIED
                            : ClusterCommContract.RESULT_FAILED, token, sent);
                    return;
                } catch (Throwable failure) {
                    Log.w(TAG, "cluster command failed: " + failure.getClass().getSimpleName());
                    send(reply, ClusterCommContract.RESULT_FAILED, token, sent);
                    return;
                } finally {
                    data.recycle();
                }
            }
            send(reply, ClusterCommContract.RESULT_SENT, token, sent);
        }

        private Target connect(Context context, long deadline) {
            boolean reset;
            synchronized (lock) {
                if (binder != null && binder.isBinderAlive()) return new Target(binder, generation, -1);
                reset = connection != null && (nullBinding || bindingDied);
            }
            if (reset) unbind();
            Connection fresh = null;
            CountDownLatch wait;
            synchronized (lock) {
                if (connection == null) {
                    fresh = new Connection();
                    connection = fresh;
                    boundContext = context;
                    nullBinding = false;
                    bindingDied = false;
                    renewLatchLocked();
                }
                wait = connected;
            }
            if (fresh != null) {
                Intent intent = new Intent().setComponent(new ComponentName(
                        ClusterCommContract.SERVICE_PACKAGE, ClusterCommContract.SERVICE_CLASS));
                int refused = -1;
                try {
                    if (!context.bindService(intent, fresh, Context.BIND_AUTO_CREATE))
                        refused = ClusterCommContract.RESULT_SERVICE_UNAVAILABLE;
                } catch (SecurityException denied) {
                    Log.w(TAG, "cluster service bind denied: " + denied.getMessage());
                    refused = ClusterCommContract.RESULT_BIND_DENIED;
                } catch (Throwable failure) {
                    refused = ClusterCommContract.RESULT_FAILED;
                }
                if (refused >= 0) {
                    unbind();
                    return new Target(null, 0, refused);
                }
            }
            long budget = Math.min(BIND_WAIT_MS, deadline - SystemClock.elapsedRealtime());
            if (budget <= 0) return new Target(null, 0, ClusterCommContract.RESULT_EXPIRED);
            boolean signalled = false;
            try {
                signalled = wait.await(budget, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            int failure;
            synchronized (lock) {
                if (binder != null && binder.isBinderAlive()) return new Target(binder, generation, -1);
                // Still binding or reconnecting: keep the binding, the system completes it.
                if (!nullBinding && !bindingDied) return new Target(null, 0, signalled
                        ? ClusterCommContract.RESULT_SERVICE_DIED : ClusterCommContract.RESULT_BIND_TIMEOUT);
                failure = bindingDied ? ClusterCommContract.RESULT_SERVICE_DIED
                        : ClusterCommContract.RESULT_SERVICE_UNAVAILABLE;
            }
            unbind();
            return new Target(null, 0, failure);
        }

        private void unbind() {
            Connection stale;
            Context context;
            synchronized (lock) {
                stale = connection;
                context = boundContext;
                connection = null;
                boundContext = null;
                nullBinding = false;
                bindingDied = false;
                binder = null;
                renewLatchLocked();
            }
            if (stale != null && context != null) {
                try { context.unbindService(stale); } catch (Throwable ignored) {}
            }
        }

        private void drop(Target target) {
            synchronized (lock) {
                if (binder == target.binder) {
                    binder = null;
                    renewLatchLocked();
                }
            }
        }
    }
}
