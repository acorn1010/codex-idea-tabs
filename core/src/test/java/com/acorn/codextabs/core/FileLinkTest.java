package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileLinkTest {
    @Test void htmlAnchorIsNotPartOfTheFilename() {
        String path = "/Users/acorn/projects/js/artie/reader.worktrees/reader-streaming/output/transfer-hybrid/index.html";
        var link = FileLink.parse(path + "#overview", "/repo");
        assertEquals(path, link.path()); assertTrue(link.preview());
        assertEquals("file://" + path + "#overview", link.browserUrl("file://" + path));
        assertTrue(FileLink.parse("page.HTML#L10", "/repo").preview(), "HTML IDs that look like line numbers remain browser anchors");
    }
    @Test void urlPartsAndEncodedFilenamesStaySeparate() {
        for (String prefix : new String[]{"", "file://", "sandbox:"}) {
            var link = FileLink.parse(prefix + "/repo/a%20b%23c%3Fd+e.html?mode=wide%20view#overview", "/other");
            assertEquals("/repo/a b#c?d+e.html", link.path());
            assertEquals("file:///repo/a%20b%23c%3Fd+e.html?mode=wide%20view#overview", link.browserUrl("file:///repo/a%20b%23c%3Fd+e.html"));
        }
        assertEquals("/repo/output/page.html", FileLink.parse("output/page.html#overview", "/repo").path());
        assertEquals("/repo/100%.html", FileLink.parse("100%.html#overview", "/repo").path());
    }
    @Test void codeLinksKeepLineAndColumnNavigation() {
        for (String value : new String[]{"src/App.java:42:7", "src/App.java#L42C7", "src/App.java#L42C7-L44C9", "file:///repo/src/App.java:42:7"}) {
            var link = FileLink.parse(value, "/repo");
            assertEquals("/repo/src/App.java", link.path()); assertEquals(41, link.line()); assertEquals(6, link.column()); assertFalse(link.preview());
        }
        assertEquals(0, FileLink.parse("src/App.java#L1", "/repo").line());
        assertEquals("/repo/README.md", FileLink.parse("README.md#setup", "/repo").path());
        assertFalse(FileLink.parse("page.html:3", "/repo").preview(), "Explicit source positions still open HTML in the editor");
    }
    @Test void windowsAndWslPathsRetainTheirHostMapping() {
        assertEquals("C:/repo/page.html", FileLink.parse("file:///C:/repo/page.html#overview", "C:/other").path());
        var link = FileLink.parse("file://wsl.localhost/Ubuntu/home/me/page.html#overview", "/repo");
        assertEquals("//wsl.localhost/Ubuntu/home/me/page.html", link.path());
        assertEquals("//wsl.localhost/Ubuntu/home/me/page.html", Paths.host(FileLink.parse("/home/me/page.html#overview", "/repo").path(), "Ubuntu", true));
        assertEquals("C:\\repo\\file.java", FileLink.parse("C:\\repo\\file.java:3", "C:\\repo").path());
    }
}
