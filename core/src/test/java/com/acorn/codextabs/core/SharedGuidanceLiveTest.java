package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

/** Opt-in check of actual skill discovery and instruction priority across start, resume, and an external Git worktree. */
@EnabledIfEnvironmentVariable(named = "CODEX_TEST_BINARY", matches = ".+")
class SharedGuidanceLiveTest {
    @TempDir Path root;

    @Test void sharedGuidanceReachesSubreposAndExternalWorktrees() throws Exception {
        var shared = Files.createDirectories(root.resolve("workspace"));
        var repo = Files.createDirectories(shared.resolve("repo"));
        var external = root.resolve("external-task");
        var skill = Files.createDirectories(shared.resolve(".agents/skills/shared-guidance-probe"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: shared-guidance-probe\ndescription: Integration check skill.\n---\nRead ../../../../reference.txt only if asked to use this skill.\n");
        Files.writeString(root.resolve("reference.txt"), "LINKED_RESOURCE_OK");
        Files.writeString(shared.resolve("AGENTS.md"), "Shared marker: SHARED_INITIAL. Default flavor: PARENT_FLAVOR. More specific checkout instructions may override the flavor.");
        git(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("AGENTS.md"), "The flavor for this checkout is LOCAL_FLAVOR. It overrides shared defaults.");
        git(repo, "add", "AGENTS.md");
        git(repo, "-c", "user.name=Codex Test", "-c", "user.email=test@example.invalid", "commit", "-m", "Test fixture");
        git(repo, "worktree", "add", "-b", "codex/probe", external.toString());
        var events = new LinkedBlockingQueue<JsonObject>();
        var created = new ArrayList<String>();
        try (var rpc = new RpcClient(new ProcessBuilder(System.getenv("CODEX_TEST_BINARY"), "app-server").directory(repo.toFile()).start(), events::offer, ignored -> {})) {
            try {
                rpc.request("initialize", object("clientInfo", object("name", "codex_shared_guidance_test", "version", "1.0.8"), "capabilities", object("experimentalApi", true))).get(45, TimeUnit.SECONDS);
                rpc.notify("initialized", new JsonObject());
                var source = SharedGuidance.resolve(true, "", shared.toString(), "", false);
                rpc.request("skills/extraRoots/set", object("extraRoots", new String[]{source.skillsRoot()})).get(15, TimeUnit.SECONDS);
                var skills = rpc.request("skills/list", object("cwds", new String[]{external.toString()}, "forceReload", true)).get(15, TimeUnit.SECONDS);
                assertTrue(GSON.toJson(skills).contains(skill.resolve("SKILL.md").toString()), GSON.toJson(skills));
                var models = array(rpc.request("model/list", object("limit", 100)).get(15, TimeUnit.SECONDS), "data");
                var selection = ModelPreferences.initial(models, "", "", false);
                var options = object("model", selection.model(), "cwd", repo.toString(), "sandbox", "read-only", "approvalPolicy", "never", "config", object("model_reasoning_effort", "low"), "developerInstructions", "Use no tools during this integration check. Reply with exactly the requested three values, separated by spaces.");
                String id = text(obj(rpc.request("thread/start", options).get(45, TimeUnit.SECONDS), "thread"), "id");
                created.add(id);
                rpc.request("thread/inject_items", source.injection(id)).get(15, TimeUnit.SECONDS);
                checkReply(rpc, events, id, "SHARED_INITIAL");
                rpc.request("thread/unsubscribe", object("threadId", id)).get(15, TimeUnit.SECONDS);
                Files.writeString(shared.resolve("AGENTS.md"), "Shared marker: SHARED_UPDATED. Default flavor: PARENT_FLAVOR. More specific checkout instructions may override the flavor.");
                source = SharedGuidance.resolve(true, "", shared.toString(), "", false);
                options.addProperty("threadId", id);
                options.addProperty("developerInstructions", "Use no tools during this integration check. Reply with exactly the requested three values, separated by spaces.");
                rpc.request("thread/resume", options).get(45, TimeUnit.SECONDS);
                rpc.request("thread/inject_items", source.injection(id)).get(15, TimeUnit.SECONDS);
                checkReply(rpc, events, id, "SHARED_UPDATED");
                options.addProperty("cwd", external.toString());
                String fork = text(obj(rpc.request("thread/fork", options).get(45, TimeUnit.SECONDS), "thread"), "id");
                created.add(fork);
                rpc.request("thread/inject_items", source.injection(fork)).get(15, TimeUnit.SECONDS);
                checkReply(rpc, events, fork, "SHARED_UPDATED");
                assertFalse(Files.exists(external.resolve(".agents")));
            } finally {
                for (String id : created) { rpc.request("thread/archive", object("threadId", id)).get(15, TimeUnit.SECONDS); }
            }
        } finally { git(repo, "worktree", "remove", external.toString()); }
    }

    private void checkReply(RpcClient rpc, BlockingQueue<JsonObject> events, String id, String marker) throws Exception {
        rpc.request("turn/start", object("threadId", id, "input", new Object[]{object("type", "text", "text", "From your current instructions and skill catalog, report the current shared marker, the applicable flavor, and the name of the shared integration check skill. Do not use tools.")})).get(45, TimeUnit.SECONDS);
        String reply = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            var event = events.poll(5, TimeUnit.SECONDS);
            if (event == null) { continue; }
            if (event.has("id")) { rpc.reject(event.get("id"), "No tools in this check."); }
            var params = obj(event, "params");
            if (!text(params, "threadId").equals(id)) { continue; }
            if (text(event, "method").equals("item/completed") && text(obj(params, "item"), "type").equals("agentMessage")) { reply = text(obj(params, "item"), "text"); }
            if (text(event, "method").equals("turn/completed")) {
                assertEquals("completed", text(obj(params, "turn"), "status"), GSON.toJson(params));
                assertEquals(marker + " LOCAL_FLAVOR shared-guidance-probe", reply);
                return;
            }
        }
        fail("Shared guidance integration turn timed out");
    }

    private void git(Path cwd, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", cwd.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }
}
