package com.overdrive.app.launcher.layout;

import java.util.concurrent.atomic.AtomicReference;

/** Input focus is owned by a layout generation; a late gesture cannot change its replacement. */
public final class ClusterLayoutSelection {
    private static final class Choice {
        final long generation;
        final String slotId;
        Choice(long generation, String slotId) { this.generation = generation; this.slotId = slotId; }
    }
    private final AtomicReference<Choice> choice = new AtomicReference<>(new Choice(0, ""));

    public void clear() { choice.set(new Choice(0, "")); }
    public void initialize(long generation, String slotId) { choice.set(new Choice(generation, slotId)); }
    public String selected(long generation) {
        Choice value = choice.get();
        return value.generation == generation ? value.slotId : "";
    }
    public boolean select(long generation, String slotId) {
        Choice value = choice.get();
        return generation > 0 && value.generation == generation
                && choice.compareAndSet(value, new Choice(generation, slotId));
    }
}
