package com.overdrive.app.surveillance;

/** One-result handoff from a timeout-bounded worker: after take() or abandon(), publish() refuses
 *  and the worker must release what it created. */
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
