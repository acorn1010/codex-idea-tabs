package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedGuidanceTest {
    @TempDir Path root;

    @Test void autoDetectionKeepsOriginalPathsAndRefreshesFiles() throws Exception {
        var shared = Files.createDirectories(root.resolve("workspace with spaces"));
        Files.createDirectories(shared.resolve(".agents/skills/engineering"));
        Files.writeString(shared.resolve("AGENTS.md"), "Read .agents/skills/engineering/SKILL.md and artie-skillz/rules.md.");
        var source = SharedGuidance.resolve(true, "", shared.toString(), "", false);
        assertEquals(shared.toString(), source.root());
        assertEquals(shared.resolve(".agents/skills").toString(), source.skillsRoot());
        String instructions = source.context();
        var injection = source.injection("thread");
        assertEquals("user", injection.getAsJsonArray("items").get(0).getAsJsonObject().get("role").getAsString());
        assertFalse(injection.has("developerInstructions"));
        assertTrue(instructions.contains("supplemental workspace guidance"));
        assertTrue(instructions.replaceAll("\\s+", " ").contains("more specific instructions in the current checkout take precedence"));
        assertTrue(instructions.contains(shared.resolve("AGENTS.md").toString()));
        assertTrue(instructions.contains("artie-skillz/rules.md"));
        Files.writeString(shared.resolve("AGENTS.md"), "Updated guidance");
        assertEquals("Updated guidance", SharedGuidance.resolve(true, "", shared.toString(), "", false).instructions());
    }

    @Test void explicitRootSurvivesAnUnrelatedWorktreeAndPersistsInChatState() throws Exception {
        var shared = Files.createDirectories(root.resolve("shared"));
        var worktree = Files.createDirectories(root.resolve("external/task"));
        Files.writeString(shared.resolve("AGENTS.md"), "Shared defaults");
        Files.writeString(worktree.resolve("AGENTS.md"), "Local overrides");
        var source = SharedGuidance.resolve(true, shared.toString(), worktree.toString(), "", false);
        var chat = new Conversation("test", worktree.toString());
        chat.set("sharedGuidanceRoot", source.root());
        var restored = Conversation.restore(chat.snapshot());
        assertEquals(shared.toString(), restored.get("sharedGuidanceRoot"));
        assertEquals(worktree.toString(), restored.get("cwd"));
        assertEquals("Local overrides", Files.readString(worktree.resolve("AGENTS.md")));
        assertFalse(Files.exists(worktree.resolve(".agents")));
    }

    @Test void overrideWinsAndBlankOverrideFallsBack() throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "Default");
        Files.writeString(root.resolve("AGENTS.override.md"), "Override");
        assertEquals("Override", SharedGuidance.resolve(true, "", root.toString(), "", false).instructions());
        Files.writeString(root.resolve("AGENTS.override.md"), "  ");
        assertEquals("Default", SharedGuidance.resolve(true, "", root.toString(), "", false).instructions());
    }

    @Test void disabledAndAbsentGuidanceDoNotChangeInstructions() throws Exception {
        assertEquals("", SharedGuidance.resolve(false, "/missing", root.toString(), "", false).root());
        assertEquals("", SharedGuidance.resolve(true, "", root.toString(), "", false).root());
        Files.createDirectories(root.resolve(".agents/skills"));
        assertFalse(SharedGuidance.resolve(true, "", root.toString(), "", false).skillsRoot().isBlank());
    }

    @Test void invalidPathsAndOversizedFilesFailInsteadOfSilentlyLosingGuidance() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SharedGuidance.resolve(true, "relative", "", "", false));
        assertThrows(IllegalArgumentException.class, () -> SharedGuidance.resolve(true, root.resolve("missing").toString(), "", "", false));
        assertThrows(IllegalArgumentException.class, () -> SharedGuidance.resolve(true, "~/rules", "", "Ubuntu", true));
        Files.writeString(root.resolve("AGENTS.md"), "x".repeat(128 * 1024 + 1));
        assertThrows(IllegalStateException.class, () -> SharedGuidance.resolve(true, "", root.toString(), "", false));
    }
}
