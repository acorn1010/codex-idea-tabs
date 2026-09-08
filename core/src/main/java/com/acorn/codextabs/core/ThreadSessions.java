package com.acorn.codextabs.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Owns thread subscriptions across editor copies and background work, including close/reopen races. */
public final class ThreadSessions {
    private static final class Entry {
        int owners;
        boolean working;
        long generation;
        CompletableFuture<Void> loaded;
        CompletableFuture<Void> releasing = CompletableFuture.completedFuture(null);
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private final Executor executor;
    private final Function<String, CompletableFuture<Void>> unsubscribe;
    private final BiConsumer<String, Throwable> releaseFailed;

    public ThreadSessions(Executor executor, Function<String, CompletableFuture<Void>> unsubscribe, BiConsumer<String, Throwable> releaseFailed) {
        this.executor = executor; this.unsubscribe = unsubscribe; this.releaseFailed = releaseFailed;
    }
    /** Retain a thread for an editor or a queued operation. Each owner must release it once. */
    public synchronized void retain(String id) { entries.computeIfAbsent(id, ignored -> new Entry()).owners++; }
    public synchronized void release(String id) {
        var entry = entries.get(id);
        if (entry == null || entry.owners == 0) { return; }
        entry.owners--;
        releaseIdle(id, entry);
    }
    /** Keep a closed chat subscribed until its active turn or blocking request finishes. */
    public synchronized void working(String id, boolean working) {
        var entry = entries.get(id);
        if (entry == null) { return; }
        entry.working = working;
        releaseIdle(id, entry);
    }
    /** Share a resume, retry failed loads, and wait for an earlier unsubscribe before reopening. */
    public synchronized CompletableFuture<Void> load(String id, Supplier<CompletableFuture<Void>> resume) {
        var entry = entries.computeIfAbsent(id, ignored -> new Entry());
        if (entry.loaded != null && !entry.loaded.isCompletedExceptionally()) { return entry.loaded; }
        var result = new CompletableFuture<Void>();
        entry.loaded = result;
        long generation = entry.generation;
        entry.releasing.handle((value, error) -> null).thenComposeAsync(ignored -> {
            synchronized (this) {
                if (generation != entry.generation) { return CompletableFuture.<Void>failedFuture(new CancellationException("Connection was replaced")); }
            }
            return resume.get();
        }, executor).whenComplete((value, error) -> {
            if (error == null) { result.complete(null); } else { result.completeExceptionally(error); }
            synchronized (this) {
                if (generation == entry.generation) { releaseIdle(id, entry); }
            }
        });
        return result;
    }
    private void releaseIdle(String id, Entry entry) {
        if (entry.owners != 0 || entry.working || entry.loaded == null || !entry.loaded.isDone()) { return; }
        entry.loaded = null;
        long generation = entry.generation;
        entry.releasing = CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                if (generation != entry.generation) { return CompletableFuture.<Void>completedFuture(null); }
                return unsubscribe.apply(id);
            }
        }, executor).thenCompose(value -> value).whenComplete((value, error) -> {
            synchronized (this) {
                if (generation != entry.generation) { return; }
                if (error != null) { releaseFailed.accept(id, error); }
                else if (entry.owners == 0 && !entry.working && entry.loaded == null) { entries.remove(id, entry); }
            }
        });
    }
    /** A closed/archived backend thread must resume again next time it is used. */
    public synchronized void forget(String id) {
        var entry = entries.get(id);
        if (entry != null) { entry.generation++; entry.loaded = null; entry.working = false; entry.releasing = CompletableFuture.completedFuture(null); }
    }
    /** Discard connection-owned futures while keeping editor and queued-operation ownership. */
    public synchronized void disconnected() {
        for (String id : List.copyOf(entries.keySet())) { forget(id); }
    }
}
