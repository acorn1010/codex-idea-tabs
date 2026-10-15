package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalModelTest {
    @Test void keepsLocalRequestsWithinTheConfiguredContextAndUsesFunctionTools() {
        var params = object("config", ComposerOptions.threadConfig("xhigh"));
        LocalModel.apply(params, LocalModel.MODEL);
        assertEquals("local_glm", text(params, "modelProvider"));
        var config = obj(params, "config");
        assertEquals(98304, config.get("model_context_window").getAsInt());
        assertEquals(73728, config.get("model_auto_compact_token_limit").getAsInt());
        assertEquals("none", text(config, "model_reasoning_summary"));
        assertEquals("low", text(config, "model_reasoning_effort"));
        assertFalse(config.get("features.unified_exec").getAsBoolean());
        assertFalse(config.get("features.apply_patch_freeform").getAsBoolean());
    }
    @Test void suppliesATextHandoffPromptOnlyToLocalThreads() {
        var params = object("config", object("unrelated_setting", true));
        LocalModel.apply(params, LocalModel.MODEL);
        var config = obj(params, "config");
        String prompt = text(config, "compact_prompt");
        assertTrue(prompt.contains("Do not call tools"));
        assertTrue(prompt.contains("Completed and verified"));
        assertTrue(prompt.contains("Next step"));
        assertTrue(flag(config, "unrelated_setting"));
        var cloud = object("config", object("compact_prompt", "User's cloud prompt"));
        LocalModel.apply(cloud, "gpt-6-astra");
        assertEquals("User's cloud prompt", text(obj(cloud, "config"), "compact_prompt"));
    }
    @Test void leavesTheCloudModelSettingsAlone() {
        var params = object("config", ComposerOptions.threadConfig("xhigh"));
        var original = params.deepCopy();
        LocalModel.apply(params, "gpt-6-astra");
        assertEquals(original, params);
    }
    @Test void advertisesTheLocalModelWithoutCloudReasoningOrFastMode() {
        var model = LocalModel.catalog();
        assertEquals("glm-flash-uncensored", text(model, "model"));
        assertTrue(flag(model, "isLocal"));
        assertTrue(array(model, "supportedReasoningEfforts").isEmpty());
        assertTrue(flag(model, "supportsAutoMode"));
        assertFalse(flag(model, "isDefault"));
    }
    @Test void addsProviderOverridesAsArgumentsWithALongStreamingTimeout() {
        var args = LocalModel.overrides("http://127.0.0.1:12345/v1");
        assertTrue(args.contains("model_providers.local_glm.base_url=\"http://127.0.0.1:12345/v1\""));
        assertTrue(args.contains("model_providers.local_glm.stream_idle_timeout_ms=1800000"));
        assertFalse(args.stream().anyMatch(value -> value.startsWith("model_provider=")));
    }
    @Test void rejectsRemoteCredentialsAndUnsupportedBasePaths() {
        LocalModel.validateUrl("http://127.0.0.1:30001/v1");
        LocalModel.validateUrl("http://localhost:30001/v1/");
        for (String url : java.util.List.of("https://example.com/v1", "http://user:secret@localhost/v1", "http://localhost/custom", "http://localhost/v1?key=secret", "http://localhost/v1#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> LocalModel.validateUrl(url));
        }
    }
    @Test void omitsCloudTurnSettingsForLocalModelsEvenWithStalePreferences() {
        var turn = object();
        ComposerOptions.apply(turn, object("fast", true, "planMode", true), new com.google.gson.JsonArray(), LocalModel.MODEL, "xhigh", false);
        assertEquals("none", text(turn, "summary"));
        assertFalse(turn.has("serviceTierForTurn"));
        assertFalse(obj(obj(turn, "collaborationMode"), "settings").has("reasoning_effort"));
    }
}
