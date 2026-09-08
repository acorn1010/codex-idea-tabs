package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ThreadSessionsTest {
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final List<String> released = new ArrayList<>();
    private final CompletableFuture<Void> unsubscribe = new CompletableFuture<>();
    private final ThreadSessions sessions = new ThreadSessions(tasks::add, id -> { released.add(id); return unsubscribe; }, (id, error) -> {});
    private void drain() { while (!tasks.isEmpty()) { tasks.remove().run(); } }
    private CompletableFuture<Void> loaded() { return CompletableFuture.completedFuture(null); }

    @Test void closingLastEditorReleasesOnlyItsThread() {
        sessions.retain("one"); sessions.retain("one"); sessions.retain("two");
        sessions.load("one", this::loaded); sessions.load("two", this::loaded); drain();
        sessions.release("one"); drain(); assertTrue(released.isEmpty());
        sessions.release("one"); drain(); assertEquals(List.of("one"), released);
        sessions.release("one"); drain(); assertEquals(List.of("one"), released);
    }
    @Test void closedActiveTurnReleasesAfterCompletion() {
        sessions.retain("chat"); sessions.load("chat", this::loaded); drain();
        sessions.working("chat", true); sessions.release("chat"); drain();
        assertTrue(released.isEmpty());
        sessions.working("chat", false); drain(); assertEquals(List.of("chat"), released);
    }
    @Test void closeDuringResumeWaitsForRecoveredActiveStatus() {
        var resume = new CompletableFuture<Void>();
        sessions.retain("chat"); sessions.load("chat", () -> resume); drain();
        sessions.release("chat"); drain(); assertTrue(released.isEmpty());
        sessions.working("chat", true); resume.complete(null); drain();
        assertTrue(released.isEmpty());
        sessions.working("chat", false); drain(); assertEquals(List.of("chat"), released);
    }
    @Test void closingDuringIdleResumeReleasesWhenItFinishes() {
        var resume = new CompletableFuture<Void>();
        sessions.retain("chat"); sessions.load("chat", () -> resume); drain();
        sessions.release("chat"); resume.complete(null); drain();
        assertEquals(List.of("chat"), released);
    }
    @Test void reopeningWaitsForUnsubscribeThenResumesAgain() {
        var resumes = new AtomicInteger();
        sessions.retain("chat"); sessions.load("chat", () -> { resumes.incrementAndGet(); return loaded(); }); drain();
        sessions.release("chat"); drain();
        sessions.retain("chat"); var reopened = sessions.load("chat", () -> { resumes.incrementAndGet(); return loaded(); }); drain();
        assertEquals(1, resumes.get()); assertFalse(reopened.isDone());
        unsubscribe.complete(null); drain();
        assertEquals(2, resumes.get()); assertTrue(reopened.isDone());
    }
    @Test void failedResumeCanRetryWithoutReplacingConnection() {
        sessions.retain("chat");
        var first = sessions.load("chat", () -> CompletableFuture.failedFuture(new IllegalStateException("Offline"))); drain();
        assertTrue(first.isCompletedExceptionally());
        var second = sessions.load("chat", this::loaded); drain();
        assertNotSame(first, second); assertFalse(second.isCompletedExceptionally()); assertTrue(second.isDone());
    }
    @Test void queuedSendOwnsThreadAfterItsEditorCloses() {
        sessions.retain("chat"); sessions.load("chat", this::loaded); drain();
        sessions.retain("chat"); sessions.release("chat"); drain(); assertTrue(released.isEmpty());
        sessions.working("chat", true); sessions.release("chat"); drain(); assertTrue(released.isEmpty());
        sessions.working("chat", false); drain(); assertEquals(List.of("chat"), released);
    }
    @Test void oldConnectionCannotUnsubscribeReopenedThread() {
        sessions.retain("chat"); sessions.load("chat", this::loaded); drain();
        sessions.release("chat"); sessions.disconnected();
        sessions.retain("chat"); var reconnected = sessions.load("chat", this::loaded); drain();
        assertTrue(released.isEmpty()); assertTrue(reconnected.isDone());
        sessions.release("chat"); drain(); assertEquals(List.of("chat"), released);
    }
    @Test void failedHistoryLoadStillReleasesPossibleBackendSubscription() {
        sessions.retain("chat"); sessions.load("chat", () -> CompletableFuture.failedFuture(new IllegalStateException("History failed"))); drain();
        sessions.release("chat"); drain(); assertEquals(List.of("chat"), released);
    }
}
