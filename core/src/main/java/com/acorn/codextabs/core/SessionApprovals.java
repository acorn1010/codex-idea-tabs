package com.acorn.codextabs.core;

/** Transient permission selection belongs to one chat and is never copied or saved. */
public final class SessionApprovals {
    private String override = "";
    private boolean running;
    private boolean suspended = true;
    private long revision;

    public synchronized void select(String mode) {
        override = mode.equals("session") ? "session" : "";
        suspended = false;
        revision++;
    }
    public synchronized String mode(String fallback) { return override.isEmpty() ? fallback : override; }
    /** The live grant wins over queued settings, but cannot change an explicit read-only turn or revive an expired grant. */
    public synchronized String resolve(String requested) {
        if (override.equals("session") && !requested.equals("read")) { return "session"; }
        return requested.equals("session") ? "ask" : requested;
    }
    public synchronized void beginTurn(String mode) { suspended = mode.equals("read"); turnStarted(); }
    public synchronized void turnStarted() { running = true; }
    public synchronized void endTurn() { running = false; }
    /** Stop pauses automatic replies until another turn or an explicit selection. */
    public synchronized void stopTurn() { running = false; suspended = true; }
    public synchronized boolean active() { return running && permitsRequests(); }
    /** Claude can request tool permissions after the foreground result, within the same live session. */
    public synchronized boolean permitsRequests() { return override.equals("session") && !suspended; }
    public synchronized long revision() { return revision; }
    public synchronized void reset() {
        running = false; suspended = true;
        if (override.equals("session")) { override = "ask"; revision++; }
    }
}
