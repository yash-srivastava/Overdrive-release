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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Explicit commit points for an H2 store of user records.
 *
 * <p>At H2's default WRITE_DELAY (500 ms) the background writer wakes every 50 ms for the
 * life of the store, because it is the only thing that commits. {@link #wrap} raises the
 * delay to 5000 ms (housekeeping at 2 Hz) and commits writes itself: every write through
 * the returned connection schedules one MVStore commit plus fsync on a shared thread, at
 * most once per 250 ms per store. H2 at 500 ms committed within 50 ms of a first write and
 * then every 500-550 ms, so a kill loses no more than before, and the fsync (which H2
 * never does on commit) narrows the power-cut window. The commit and fsync are what {@code CHECKPOINT SYNC} runs, minus the
 * session lock, so readers on the shared connection never wait behind the fsync.
 *
 * <p>The delay is set at runtime only (not in the URL, not persisted in the file). If any
 * step fails the raw connection is returned and the store keeps the 500 ms default.
 */
public final class H2CommitPoint {

    static final int WRITE_DELAY_MS = 5000;
    // Half of H2's default WRITE_DELAY, so a burst never loses more than the 500-550 ms the
    // background writer left open; bounds a sustained writer to 4 commits/s per store.
    private static final long MIN_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    // The 500 ms writer's wake period: how long a write inside a transaction waits.
    private static final long TX_BACKSTOP_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

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
    // Guarded by this.
    private ScheduledFuture<?> scheduled;
    private long scheduledAt;
    private long lastFlushNanos = System.nanoTime() - MIN_GAP_NANOS;

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

    /**
     * {@code settled} = a commit, rollback or auto-commit write: flush as soon as the gap
     * allows. Otherwise (a write inside a transaction, which DDL may commit implicitly) a
     * backstop flush one writer wake from now, as at 500 ms; a quick commit pulls it forward.
     */
    private synchronized void onWrite(boolean settled) {
        long now = System.nanoTime();
        long earliest = lastFlushNanos + MIN_GAP_NANOS;
        long at = Math.max(settled ? now : now + TX_BACKSTOP_NANOS, earliest);
        if (scheduled != null) {
            if (scheduledAt - at <= 0) return;
            if (!scheduled.cancel(false)) return;  // already running; it re-arms from its stamp
        }
        try {
            scheduled = EXECUTOR.schedule(this::flush, at - now, TimeUnit.NANOSECONDS);
            scheduledAt = at;
        } catch (RuntimeException e) {
            scheduled = null;
        }
    }

    private void flush() {
        long previous;
        long started = System.nanoTime();
        synchronized (this) {
            // Cleared and stamped first, so a write landing during this commit schedules the
            // next one a gap later.
            scheduled = null;
            previous = lastFlushNanos;
            lastFlushNanos = started;
        }
        try {
            if (store.isClosed()) return;
            if (store.commit() >= 0) {
                store.sync();
            } else {
                // Nothing was unsaved, so no chunk was written: the next write may flush at once.
                synchronized (this) {
                    if (lastFlushNanos == started) lastFlushNanos = previous;
                }
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
            boolean commits = name.equals("commit") || name.equals("rollback")
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
                if (commits) onWrite(true);
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
            // Read before and after: another thread may flip auto-commit in between.
            boolean autoBefore = autoCommit(connection);
            Object result = null;
            try {
                result = call(raw, m, args);
                return result;
            } finally {
                // execute() answers true for a result set (a query such as the SELECT 1 probe).
                if (!(name.equals("execute") && Boolean.TRUE.equals(result))) {
                    onWrite(autoBefore || autoCommit(connection));
                }
            }
        }
    }
}
