package com.acorn.codextabs.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import static com.acorn.codextabs.core.Json.object;

/** Read host files for copying into the Codex backend, including files dropped from Windows. */
public final class AttachmentFiles {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private AttachmentFiles() {}

    /** Validate and read one file without exposing its host path to the webview or changing its contents. */
    public static JsonObject read(Path path, boolean imagesOnly) throws IOException {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (!Files.isRegularFile(path)) { throw new IllegalArgumentException(name + " is not a file."); }
        if (Files.size(path) > MAX_BYTES) { throw new IllegalArgumentException(name + " exceeds 50 MB."); }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = imageMime(extension);
        if (imagesOnly && mime.isBlank()) { throw new IllegalArgumentException(name + " is not an image."); }
        if (mime.isBlank()) { mime = extension.equals("pdf") ? "application/pdf" : Files.probeContentType(path); }
        // Bound the read too, in case the file grows after the size check.
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) { throw new IllegalArgumentException(name + " exceeds 50 MB."); }
        return object("name", name, "mime", mime == null ? "application/octet-stream" : mime,
            "data", Base64.getEncoder().encodeToString(bytes));
    }

    /** Keep the file chooser and drop validation consistent for image-only edits. */
    public static String imageMime(String extension) {
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "bmp" -> "image/bmp";
            case "avif" -> "image/avif";
            default -> "";
        };
    }
}
