package com.acorn.codextabs.core;

/** Transient permission selection belongs to one chat and is never copied or saved. */
public final class SessionApprovals {
    private String override = "";
    private boolean active;
    private boolean sessionTurns;
    private long revision;

    public synchronized void select(String mode) {
        override = mode.equals("session") ? "session" : "";
        if (!mode.equals("session")) { active = false; }
        revision++;
    }
    public synchronized String mode(String fallback) { return override.isEmpty() ? fallback : override; }
    /** A stale composer or queued send cannot re-enable an expired grant. */
    public synchronized String resolve(String requested) { return requested.equals("session") && !override.equals("session") ? "ask" : requested; }
    public synchronized void beginTurn(String mode) { sessionTurns = mode.equals("session"); turnStarted(); }
    /** Goal continuations retain the backend mode of the turn that started them. */
    public synchronized void turnStarted() { active = override.equals("session") && sessionTurns; }
    public synchronized void endTurn() { active = false; }
    public synchronized boolean active() { return active; }
    public synchronized long revision() { return revision; }
    public synchronized void reset() {
        active = false; sessionTurns = false;
        if (override.equals("session")) { override = "ask"; revision++; }
    }
}
