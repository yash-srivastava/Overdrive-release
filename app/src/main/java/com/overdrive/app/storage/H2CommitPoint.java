package com.overdrive.app.storage;

import com.overdrive.app.logging.DaemonLogger;

import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.mvstore.MVStore;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit commit points for an H2 store of user records.
 *
 * <p>At H2's default WRITE_DELAY (500 ms) the background writer wakes every 50 ms for the
 * life of the store, because it is the only thing that commits. {@link #wrap} raises the
 * delay to 5000 ms (housekeeping at 2 Hz) and commits writes itself: every write through
 * the returned connection schedules one MVStore commit plus fsync on a shared thread, at
 * most once per 500 ms per store. That is H2's own commit cadence at 500 ms, so a kill
 * loses no more than before, and the fsync (which H2 never does on commit) narrows the
 * power-cut window. The commit and fsync are what {@code CHECKPOINT SYNC} runs, minus the
 * session lock, so readers on the shared connection never wait behind the fsync.
 *
 * <p>The delay is set at runtime only (not in the URL, not persisted in the file). If any
 * step fails the raw connection is returned and the store keeps the 500 ms default.
 */
public final class H2CommitPoint {

    static final int WRITE_DELAY_MS = 5000;
    /** H2's default WRITE_DELAY: never commit more often than its background writer did. */
    private static final long MIN_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private static final DaemonLogger logger = DaemonLogger.getInstance("H2CommitPoint");

    // One thread for every store; it only wakes when a store was written. Never shut down
    // or interrupted: an interrupt during file I/O closes the H2 store.
    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "H2-commit");
                t.setDaemon(true);
                return t;
            });

    private final MVStore store;
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile long lastFlushNanos = System.nanoTime() - MIN_GAP_NANOS;

    private H2CommitPoint(MVStore store) {
        this.store = store;
    }

    /** Returns a connection whose writes are committed explicitly, or {@code raw} unchanged. */
    public static Connection wrap(Connection raw) {
        if (raw == null) return null;
        Database db;
        MVStore store;
        try {
            db = ((SessionLocal) ((JdbcConnection) raw).getSession()).getDatabase();
            store = db.getStore().getMvStore();
        } catch (Throwable t) {
            logger.warn("commit point unavailable, keeping H2 default write delay: " + t);
            return raw;
        }
        if (!store.isPersistent() || store.isReadOnly()) return raw;
        H2CommitPoint point = new H2CommitPoint(store);
        Connection wrapped = point.connection(raw);
        try {
            db.setWriteDelay(WRITE_DELAY_MS);
        } catch (Throwable t) {
            logger.warn("commit point unavailable, keeping H2 default write delay: " + t);
            try { db.setWriteDelay(500); } catch (Throwable ignored) { /* store is failing anyway */ }
            return raw;
        }
        return wrapped;
    }

    private void onWrite() {
        if (!pending.compareAndSet(false, true)) return;
        long wait = Math.max(0L, lastFlushNanos + MIN_GAP_NANOS - System.nanoTime());
        try {
            EXECUTOR.schedule(this::flush, wait, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            pending.set(false);
        }
    }

    private void flush() {
        // Cleared first, so a write landing during this commit schedules the next one.
        pending.set(false);
        long previous = lastFlushNanos;
        lastFlushNanos = System.nanoTime();
        try {
            if (store.isClosed()) return;
            if (store.commit() >= 0) {
                store.sync();
            } else {
                // Nothing was unsaved, so no chunk was written: the next write may flush at once.
                lastFlushNanos = previous;
            }
        } catch (Throwable ignored) {
            // Closed or died meanwhile: close() already persisted, and the owner reopens a
            // dead store on its next statement.
        }
    }

    private Connection connection(Connection raw) {
        return (Connection) Proxy.newProxyInstance(H2CommitPoint.class.getClassLoader(),
                new Class<?>[] {Connection.class}, new ConnectionHandler(raw));
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static boolean autoCommit(Connection c) {
        try {
            return c.getAutoCommit();
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object objectMethod(Object proxy, Method m, Object[] args, Object target) {
        switch (m.getName()) {
            case "equals": return proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            default: return String.valueOf(target);
        }
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection raw;

        ConnectionHandler(Connection raw) {
            this.raw = raw;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (m.getDeclaringClass() == Object.class) return objectMethod(proxy, m, args, raw);
            String name = m.getName();
            boolean commits = name.equals("commit")
                    || (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0]));
            try {
                Object result = call(raw, m, args);
                if (result instanceof Statement
                        && (name.equals("createStatement") || name.startsWith("prepare"))) {
                    return Proxy.newProxyInstance(H2CommitPoint.class.getClassLoader(),
                            new Class<?>[] {m.getReturnType()},
                            new StatementHandler(result, (Connection) proxy));
                }
                return result;
            } finally {
                if (commits) onWrite();
            }
        }
    }

    private final class StatementHandler implements InvocationHandler {
        private final Object raw;
        private final Connection connection;

        StatementHandler(Object raw, Connection connection) {
            this.raw = raw;
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (m.getDeclaringClass() == Object.class) return objectMethod(proxy, m, args, raw);
            String name = m.getName();
            if (name.equals("getConnection")) return connection;
            // Every execute* but a query may write; a failed batch can still have committed
            // rows, so a throw marks too. A flush with nothing unsaved is a no-op.
            if (!name.startsWith("execute") || name.equals("executeQuery")) return call(raw, m, args);
            Object result = null;
            try {
                result = call(raw, m, args);
                return result;
            } finally {
                // execute() answers true for a result set (a query such as the SELECT 1 probe).
                // Inside a transaction the commit() marks instead, so a half-done transaction
                // never takes the flush slot that its commit needs.
                if (!(name.equals("execute") && Boolean.TRUE.equals(result))
                        && autoCommit(connection)) {
                    onWrite();
                }
            }
        }
    }
}
