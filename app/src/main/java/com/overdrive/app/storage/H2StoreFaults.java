package com.overdrive.app.storage;

import org.h2.mvstore.MVStoreException;

import java.nio.channels.ClosedChannelException;
import java.sql.SQLException;

/** Recognises an H2 store that died under a still-open connection; only a reopen cures it. */
public final class H2StoreFaults {

    private H2StoreFaults() {}

    /** Same codes as RecordingsIndex.isClosedStoreError; lock/deadlock codes also wrap MVStoreException. */
    private static boolean isFatalCode(int code) {
        switch (code) {
            case 90098:   // DATABASE_IS_CLOSED
            case 90007:   // OBJECT_CLOSED
            case 90030:   // FILE_CORRUPTED
            case 90028:   // IO_EXCEPTION
            case 50000:   // GENERAL_ERROR (MVStore panic)
            case 90097:   // DATABASE_IS_READ_ONLY
            case 42102:
            case 42103:
            case 42104:   // tables gone after a wipe
                return true;
            default:
                return false;
        }
    }

    public static boolean isStoreDead(Throwable failure) {
        boolean sawSqlCode = false;
        Throwable t = failure;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof SQLException) {
                if (isFatalCode(((SQLException) t).getErrorCode())) return true;
                sawSqlCode = true;
            } else if (!sawSqlCode
                    && (t instanceof MVStoreException || t instanceof ClosedChannelException)) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** Thread, cause chain and first caller frames: the killer is the call that saw ClosedByInterruptException. */
    public static String describe(Throwable failure) {
        StringBuilder sb = new StringBuilder("thread=").append(Thread.currentThread().getName());
        Throwable t = failure;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            sb.append(depth == 0 ? " | " : " <- ").append(t.getClass().getSimpleName());
            if (t instanceof SQLException) {
                sb.append('(').append(((SQLException) t).getErrorCode()).append(')');
            }
            String m = t.getMessage();
            if (m != null) {
                m = m.replace('\n', ' ');
                sb.append(": ").append(m.length() > 120 ? m.substring(0, 120) + "…" : m);
            }
            if (t.getCause() == t) break;
        }
        if (failure != null) {
            int frames = 0;
            for (StackTraceElement f : failure.getStackTrace()) {
                String c = f.getClassName();
                if (c.startsWith("org.h2.") || c.startsWith("java.") || c.startsWith("jdk.")
                        || c.startsWith("sun.")) {
                    continue;
                }
                sb.append(frames == 0 ? " @ " : " < ").append(c.substring(c.lastIndexOf('.') + 1))
                        .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
                if (++frames == 3) break;
            }
        }
        return sb.toString();
    }

    /** " (describe)" for a dead-store failure, "" otherwise, for appending to an existing log line. */
    public static String detail(Throwable failure) {
        return isStoreDead(failure) ? " (" + describe(failure) + ")" : "";
    }
}
