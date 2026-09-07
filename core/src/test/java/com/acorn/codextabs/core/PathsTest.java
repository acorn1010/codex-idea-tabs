package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathsTest {
    @Test void wslAttachmentsRoundTrip() {
        String windows = "\\\\wsl.localhost\\Ubuntu\\home\\acorn\\image with space.png";
        assertEquals("Ubuntu", Paths.distro(windows));
        assertEquals("/home/acorn/image with space.png", Paths.linux(windows));
        assertEquals(windows.replace('\\', '/'), Paths.host(Paths.linux(windows), "Ubuntu", true));
    }
    @Test void driveAndFileUrlsAreTranslated() {
        assertEquals("/mnt/c/Users/acorn/image.png", Paths.linux("/C:/Users/acorn/image.png"));
        assertEquals("/home/acorn/a b.png", Paths.link("file:///home/acorn/a%20b.png", "/project"));
        assertEquals("/project/image.png", Paths.link("image.png", "/project"));
        assertEquals("C:/Users/test/image.png", Paths.link("file:///C:/Users/test/image.png", "C:/project"));
        assertEquals("/project/app.java#L10", Paths.link("file:///project/app.java#L10", "/project"));
    }
    @Test void binaryIsPassedAsAnArgumentNotShellCode() {
        var args = Paths.command("/home/a b/$(echo secret)/codex", "Ubuntu", true);
        assertEquals("/home/a b/$(echo secret)/codex", args.getLast());
        assertFalse(args.get(6).contains("secret"));
    }
}
