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
    @Test void providerOverridesStaySeparateFromTheShellProgram() {
        var options = LocalModel.overrides("http://127.0.0.1:30001/v1");
        var wsl = Paths.command("/home/a b/codex", "Ubuntu", true, options);
        assertEquals(options, wsl.subList(wsl.size() - options.size(), wsl.size()));
        assertEquals("/home/a b/codex", wsl.get(wsl.size() - options.size() - 1));
        assertFalse(wsl.get(6).contains("base_url"));
        var nativeArgs = Paths.command("C:/CLI/codex.exe", "", true, options);
        assertEquals("app-server", nativeArgs.getLast());
        assertEquals(options, nativeArgs.subList(1, nativeArgs.size() - 1));
    }
}
