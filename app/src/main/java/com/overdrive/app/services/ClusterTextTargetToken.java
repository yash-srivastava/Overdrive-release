package com.overdrive.app.services;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Identity shared by the service's target and the keyboard dialog. */
final class ClusterTextTargetToken {
    // A service can be recreated while its keyboard Activity remains alive.
    private static final AtomicLong NEXT_ID = new AtomicLong();
    final long id = NEXT_ID.incrementAndGet();
    private final Object owner;

    ClusterTextTargetToken(Object owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    boolean matches(Object candidateOwner, long candidateId) {
        return owner == candidateOwner && id == candidateId;
    }
}
