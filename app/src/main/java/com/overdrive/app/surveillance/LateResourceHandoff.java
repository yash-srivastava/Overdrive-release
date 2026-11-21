package com.overdrive.app.surveillance;

/**
 * Single-result ownership handoff for a resource created on a timeout-isolated worker.
 *
 * <p>The caller either takes the published resource after the worker finishes or abandons the
 * handoff when the operation times out. A worker that publishes after abandonment is rejected
 * and must dispose of its resource itself.
 */
final class LateResourceHandoff<T> {
    private boolean closed;
    private T resource;

    /** Publishes one created resource, or rejects it when ownership has already closed. */
    synchronized boolean publish(T created) {
        if (closed || created == null || resource != null) {
            return false;
        }
        resource = created;
        return true;
    }

    /** Transfers the published resource to the caller and closes further publication. */
    synchronized T take() {
        closed = true;
        T result = resource;
        resource = null;
        return result;
    }

    /** Closes publication and transfers any already-published resource for timeout cleanup. */
    synchronized T abandon() {
        closed = true;
        T result = resource;
        resource = null;
        return result;
    }
}
