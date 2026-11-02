package com.overdrive.app.safekeep;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Scheduling and configuration only; no replacement for LinkManager decisions. */
final class Engine {
    final ScheduledThreadPoolExecutor aux;
    final Config cfg = new Config();
    Engine(ScheduledThreadPoolExecutor aux) { this.aux = aux; }
    static final class Config { Object destination(String id) { return null; } }
    void runCtl(Runnable command) { command.run(); }
    void setDestState(String id, String state, String error, long retryAt) {
        throw new AssertionError("Relink is outside this new-destination fixture");
    }
}
