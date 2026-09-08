package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonArray;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelPreferencesTest {
    @Test void prefersAstraExtraHighWhenAvailable() {
        var models = models("xhigh");
        assertEquals(new ModelPreferences("gpt-6-astra", "xhigh"), ModelPreferences.initial(models, "", "", false));
    }
    @Test void keepsSavedChoicesIncludingCodexDefaultAndLegacyPreferences() {
        assertEquals(new ModelPreferences("other", "high"), ModelPreferences.initial(models("xhigh"), "other", "high", false));
        assertEquals(new ModelPreferences("", ""), ModelPreferences.initial(models("xhigh"), "", "", true));
    }
    @Test void doesNotRequestAnUnavailableModelOrReasoningLevel() {
        assertEquals(new ModelPreferences("", ""), ModelPreferences.initial(new JsonArray(), "", "", false));
        assertEquals(new ModelPreferences("gpt-6-astra", "medium"), ModelPreferences.initial(models("medium"), "", "", false));
    }
    private static JsonArray models(String effort) {
        return GSON.toJsonTree(new Object[]{object("model", "gpt-6-astra", "defaultReasoningEffort", "medium", "supportedReasoningEfforts", new Object[]{object("reasoningEffort", effort)})}).getAsJsonArray();
    }
}
