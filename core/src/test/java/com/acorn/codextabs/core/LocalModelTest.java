package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalModelTest {
    @Test void keepsLocalControlsWhenTheServerAliasMatchesACloudModel() {
        var cloud = new com.google.gson.JsonArray();
        cloud.add(object("id", "same-id", "model", "same-id", "isDefault", true));
        cloud.add(object("id", "other", "model", "other"));
        var models = LocalModel.withLocal(cloud, "same-id");
        assertEquals(2, models.size());
        assertFalse(cloud.get(0).getAsJsonObject().has("isLocal"));
        var turn = object();
        ComposerOptions.apply(turn, object("fast", true), models, "same-id", "high", false);
        assertEquals("none", text(turn, "summary"));
        assertFalse(turn.has("serviceTierForTurn"));
    }
    @Test void configuresOtherLocalModelsWithoutGlmReasoning() {
        var params = object("config", ComposerOptions.threadConfig("xhigh"));
        LocalModel.apply(params, "qwen-coder", 32768, "auto");
        assertEquals(LocalModel.PROVIDER, text(params, "modelProvider"));
        assertEquals("qwen-coder", text(params, "model"));
        var config = obj(params, "config");
        assertEquals(32768, config.get("model_context_window").getAsInt());
        assertEquals(24576, config.get("model_auto_compact_token_limit").getAsInt());
        assertEquals("none", text(config, "model_reasoning_effort"));
        assertFalse(flag(config, "features.unified_exec"));
        assertFalse(flag(config, "features.apply_patch_freeform"));
        assertTrue(text(config, "compact_prompt").contains("Do not call tools"));
    }
    @Test void preservesGlmTuningAndAllowsAnExplicitProfileForAliases() {
        for (String model : java.util.List.of(LocalModel.MODEL, "zai-org/GLM-4.7-Flash-GGUF", "glm4:latest")) {
            var params = object();
            LocalModel.apply(params, model, 98304, "auto");
            assertEquals("low", text(obj(params, "config"), "model_reasoning_effort"));
            assertEquals(73728, obj(params, "config").get("model_auto_compact_token_limit").getAsInt());
        }
        var alias = object();
        LocalModel.apply(alias, "my-model", 65536, "glm");
        assertEquals("low", text(obj(alias, "config"), "model_reasoning_effort"));
        LocalModel.apply(alias, LocalModel.MODEL, 65536, "standard");
        assertEquals("none", text(obj(alias, "config"), "model_reasoning_effort"));
    }
    @Test void recognizesOnlyTheConfiguredLocalIdAndPublishesItsName() {
        assertTrue(LocalModel.selected("qwen-coder", "qwen-coder"));
        assertFalse(LocalModel.selected(LocalModel.MODEL, "qwen-coder"));
        assertFalse(LocalModel.selected("", ""));
        var model = LocalModel.catalog("qwen-coder");
        assertEquals("qwen-coder (local)", text(model, "displayName"));
        var turn = object();
        var catalog = new com.google.gson.JsonArray(); catalog.add(model);
        ComposerOptions.apply(turn, object("fast", true, "planMode", true), catalog, "qwen-coder", "xhigh", false);
        assertEquals("none", text(turn, "summary"));
        assertFalse(turn.has("serviceTierForTurn"));
        assertFalse(obj(obj(turn, "collaborationMode"), "settings").has("reasoning_effort"));
    }
    @Test void validatesModelContextAndProfileBeforeConnecting() {
        LocalModel.validate("Qwen/model:latest", 8192, "auto");
        for (String model : java.util.List.of("", "two\nmodels", " leading")) {
            assertThrows(IllegalArgumentException.class, () -> LocalModel.validate(model, 8192, "auto"));
        }
        assertThrows(IllegalArgumentException.class, () -> LocalModel.validate("model", 0, "auto"));
        assertThrows(IllegalArgumentException.class, () -> LocalModel.validate("model", 8192, "unknown"));
    }
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
        var models = new com.google.gson.JsonArray(); models.add(LocalModel.catalog());
        ComposerOptions.apply(turn, object("fast", true, "planMode", true), models, LocalModel.MODEL, "xhigh", false);
        assertEquals("none", text(turn, "summary"));
        assertFalse(turn.has("serviceTierForTurn"));
        assertFalse(obj(obj(turn, "collaborationMode"), "settings").has("reasoning_effort"));
    }
}
