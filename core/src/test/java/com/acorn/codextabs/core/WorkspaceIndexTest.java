package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class WorkspaceIndexTest {
    @Test void matchesTheDeepestWorkspaceAtDirectoryBoundaries() {
        var values = new JsonArray();
        values.add(object("path", "/project", "branch", "main"));
        values.add(object("path", "/project/server/", "branch", "feature"));
        var index = new WorkspaceIndex(values);
        assertEquals("feature", text(index.find("/project/server/src").orElseThrow(), "branch"));
        assertEquals("main", text(index.find("/project/server-other").orElseThrow(), "branch"));
        assertTrue(index.find("/project-other").isEmpty());
        index.find("/project/server").orElseThrow().addProperty("branch", "mutated");
        assertEquals("feature", text(index.find("/project/server").orElseThrow(), "branch"));
        values.get(1).getAsJsonObject().addProperty("branch", "new-branch");
        assertEquals("new-branch", text(new WorkspaceIndex(values).find("/project/server").orElseThrow(), "branch"));
    }
    @Test void keepsHostAndWslPathRules() {
        for (var pair : new String[][] {{"/", "/repo"}, {"/repo///", "/repo/src/"}, {"C:\\repo\\", "C:/repo/src"}, {"\\\\wsl.localhost\\Ubuntu\\home", "//wsl.localhost/Ubuntu/home/repo"}}) {
            assertTrue(GitWorktrees.contains(pair[0], pair[1]));
        }
        assertTrue(GitWorktrees.same("C:\\repo\\", "C:/repo///"));
        assertFalse(GitWorktrees.contains("/repo", "/repository"));
        assertFalse(GitWorktrees.same("/Repo", "/repo"));
    }
}
