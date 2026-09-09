package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import static com.acorn.codextabs.core.Json.text;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentFilesTest {
    @TempDir Path directory;

    @Test void readsPdfBytesAndNameWithoutChangingTheSource() throws Exception {
        byte[] bytes = new byte[]{37, 80, 68, 70, 45, 49, 46, 55, 10, 0, (byte) 255};
        Path file = Files.write(directory.resolve("Résumé notes.PDF"), bytes);
        var attachment = AttachmentFiles.read(file, false);
        assertEquals("Résumé notes.PDF", text(attachment, "name"));
        assertEquals("application/pdf", text(attachment, "mime"));
        assertArrayEquals(bytes, Base64.getDecoder().decode(text(attachment, "data")));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void rejectsDirectoriesMissingFilesAndOversizedFiles() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AttachmentFiles.read(directory, false));
        assertThrows(IllegalArgumentException.class, () -> AttachmentFiles.read(directory.resolve("missing.pdf"), false));
        Path file = directory.resolve("large.pdf");
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) { sparse.setLength(50 * 1024 * 1024 + 1); }
        assertEquals("large.pdf exceeds 50 MB.", assertThrows(IllegalArgumentException.class, () -> AttachmentFiles.read(file, false)).getMessage());
    }

    @Test void keepsTheImageOnlyEditorRestriction() throws Exception {
        Path pdf = Files.writeString(directory.resolve("notes.pdf"), "%PDF-1.7");
        assertEquals("notes.pdf is not an image.", assertThrows(IllegalArgumentException.class, () -> AttachmentFiles.read(pdf, true)).getMessage());
        Path image = Files.write(directory.resolve("example.PNG"), new byte[]{1, 2, 3});
        assertEquals("image/png", text(AttachmentFiles.read(image, true), "mime"));
    }
}
