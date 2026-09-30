package com.acorn.codextabs.smoke;

import com.acorn.codextabs.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.openapi.util.JDOMUtil;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static com.acorn.codextabs.core.Json.*;

/** Check real chat creation and IDEA settings serialization without starting either CLI. */
final class ProviderPreferenceSmoke {
    static void run(Project project, Path output) throws Exception {
        var service = CodexService.get(project);
        service.restoreReady().get(10, TimeUnit.SECONDS);
        var component = project.getService(CodexSettings.class);
        service.settings().provider = "codex";
        var codex = service.create();
        codex.set("cwd", service.cwd() + "/selected-worktree"); codex.set("workspaceBase", "feature-base");
        var claude = service.chat(UUID.randomUUID().toString()); claude.set("provider", "claude");
        service.selectProvider(claude.id, "claude").get(10, TimeUnit.SECONDS);
        check(service.settings().provider.equals("claude"), "Selecting the active provider did not save it");
        check(service.create().get("provider").equals("claude"), "Plain new tab ignored the preference");
        var created = service.createInWorkspace(codex.id);
        check(created.get("provider").equals("claude"), "New or split chat inherited the old tab's provider");
        check(created.get("cwd").equals(codex.get("cwd")) && created.get("workspaceBase").equals("feature-base"), "New chat lost the selected workspace");
        check(service.createForPathAsync(service.cwd()).get(10, TimeUnit.SECONDS).get("provider").equals("claude"), "Chat from a file ignored the preference");

        // Round-trip through the same serializer IDEA uses for PersistentStateComponent.
        var saved = output.resolve("provider-settings.xml");
        JDOMUtil.write(XmlSerializer.serialize(component.getState()), saved);
        component.loadState(new CodexSettings.State());
        component.loadState(XmlSerializer.deserialize(JDOMUtil.load(saved), CodexSettings.State.class));
        check(service.createInWorkspace(codex.id).get("provider").equals("claude"), "Saved provider did not survive settings reload");
        check(codex.get("provider").equals("codex"), "An existing conversation changed provider");

        service.selectProvider(codex.id, "codex").get(10, TimeUnit.SECONDS);
        check(service.createInWorkspace(claude.id).get("provider").equals("codex"), "Selecting Codex did not update the preference");
        check(claude.get("provider").equals("claude"), "Selecting a preference changed another conversation");
        Files.writeString(output.resolve("provider-preference-result.json"), GSON.toJson(object(
            "plainNewTab", true, "newAndSplitFromOldTab", true, "fileContext", true, "workspaceKept", true,
            "settingsReload", true, "sameProviderSelection", true, "bothProviders", true, "existingChatsKept", true)));
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private ProviderPreferenceSmoke() {}
}
