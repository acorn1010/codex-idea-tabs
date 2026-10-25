package com.acorn.codextabs;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Open known files without waiting for unrelated IDE refreshes. */
final class FileLinkLookup {
    private static final long TIMEOUT_SECONDS = 15;

    static CompletableFuture<VirtualFile> find(String path) {
        var files = LocalFileSystem.getInstance();
        return find(path, () -> files.findFileByPathIfCached(path), () -> files.refreshAndFindFileByPath(path), TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    static CompletableFuture<VirtualFile> find(String path, Supplier<VirtualFile> cached, Supplier<VirtualFile> refresh, long timeout, TimeUnit unit) {
        var indicator = new EmptyProgressIndicator();
        var lookup = CompletableFuture.supplyAsync(() -> ProgressManager.getInstance().runProcess(() -> {
            indicator.checkCanceled();
            var target = cached.get();
            if (target == null) { indicator.checkCanceled(); target = refresh.get(); }
            if (target == null) { throw new IllegalArgumentException("File not found: " + path); }
            return target;
        }, indicator));
        return lookup.orTimeout(timeout, unit).handle((target, error) -> {
            if (error != null) {
                // Cancel the refresh wait too, so timed-out clicks do not leave blocked workers behind.
                indicator.cancel();
                if (error instanceof TimeoutException) {
                    throw new CompletionException(new IllegalStateException("IntelliJ file lookup timed out. Its file refresh may be stuck. Try File > Open with this path: " + path));
                }
                if (error instanceof CompletionException failure) { throw failure; }
                throw new CompletionException(error);
            }
            return target;
        });
    }

    private FileLinkLookup() {}
}
