package com.overdrive.app.byd;

/**
 * A local write was no longer authorized or its execution outcome became uncertain. Unlike a
 * definite HAL refusal, this must not start a cloud fallback for the same command.
 */
public final class LocalActuationAbort extends RuntimeException {
    public enum Kind { BLOCKED_DRIVING, CANCELLED, REQUEST_EXPIRED, UNCERTAIN }

    public final Kind kind;

    public LocalActuationAbort(Kind kind, String reason) {
        super(reason);
        this.kind = kind;
    }

    public static LocalActuationAbort fromWire(String kind, String reason) {
        if (kind == null) return null;
        try {
            return new LocalActuationAbort(Kind.valueOf(kind), reason);
        } catch (IllegalArgumentException malformed) {
            return new LocalActuationAbort(
                    Kind.REQUEST_EXPIRED, "invalid local actuation refusal");
        }
    }
}
