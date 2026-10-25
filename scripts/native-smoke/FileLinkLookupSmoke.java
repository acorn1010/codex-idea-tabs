package com.acorn.codextabs;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.function.Supplier;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Check cached lookup and cancellation against the real IDE progress manager. */
public final class FileLinkLookupSmoke {
    public static void run() throws Exception {
        var file = new LightVirtualFile("schema.json", "{}");
        var cached = find("schema.json", () -> file, () -> { throw new AssertionError("Cached files must not refresh"); }, 5, TimeUnit.SECONDS);
        check(cached.get(5, TimeUnit.SECONDS) == file, "Cached file did not open");
        var fresh = find("schema.json", () -> null, () -> file, 5, TimeUnit.SECONDS);
        check(fresh.get(5, TimeUnit.SECONDS) == file, "New file was not found through refresh");
        try {
            find("missing.json", () -> null, () -> null, 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            throw new AssertionError("Missing file must report a failure");
        } catch (ExecutionException expected) { check(expected.getCause().getMessage().equals("File not found: missing.json"), "Missing file error"); }

        var entered = new CountDownLatch(1); var exited = new CountDownLatch(1);
        var navigated = new AtomicBoolean();
        var stalled = find("stalled.json", () -> null, () -> {
            entered.countDown();
            var indicator = ProgressManager.getInstance().getProgressIndicator();
            try {
                while (!indicator.isCanceled()) { java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); }
                indicator.checkCanceled();
                return file;
            } finally { exited.countDown(); }
        }, 1, TimeUnit.SECONDS).thenAccept(ignored -> navigated.set(true));
        check(entered.await(5, TimeUnit.SECONDS), "Refresh did not start");
        try { stalled.get(5, TimeUnit.SECONDS); throw new AssertionError("Stalled refresh must time out"); }
        catch (ExecutionException expected) { check(expected.getCause().getMessage().contains("file lookup timed out"), "Timeout explains the file refresh failure"); }
        check(exited.await(5, TimeUnit.SECONDS), "Timed-out refresh left a blocked worker");
        check(!navigated.get(), "Timed-out lookup opened a file later");
        System.out.println("File lookup: cached files skip refresh, new files refresh, missing files fail, stalled refresh cancels, and late navigation is blocked.");
    }
    @SuppressWarnings("unchecked")
    private static CompletableFuture<VirtualFile> find(String path, Supplier<VirtualFile> cached, Supplier<VirtualFile> refresh, long timeout, TimeUnit unit) throws Exception {
        // The fixture plugin has its own classloader, so package access does not cross this boundary.
        var lookup = Class.forName("com.acorn.codextabs.FileLinkLookup", true, ChatEditor.class.getClassLoader());
        var method = lookup.getDeclaredMethod("find", String.class, Supplier.class, Supplier.class, long.class, TimeUnit.class);
        method.setAccessible(true);
        return (CompletableFuture<VirtualFile>) method.invoke(null, path, cached, refresh, timeout, unit);
    }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private FileLinkLookupSmoke() {}
}
