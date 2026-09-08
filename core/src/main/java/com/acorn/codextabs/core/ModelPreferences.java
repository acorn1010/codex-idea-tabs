package com.acorn.codextabs.core;

import com.google.gson.JsonArray;
import static com.acorn.codextabs.core.Json.*;

/** Pick initial defaults from available models without replacing a saved user choice. */
public record ModelPreferences(String model, String effort) {
    public static ModelPreferences initial(JsonArray models, String model, String effort, boolean saved) {
        if (saved || !model.isBlank() || !effort.isBlank()) { return new ModelPreferences(model, effort); }
        for (var value : models) {
            var candidate = value.getAsJsonObject();
            if (!text(candidate, "model").equals("gpt-6-astra")) { continue; }
            String preferredEffort = text(candidate, "defaultReasoningEffort");
            for (var option : array(candidate, "supportedReasoningEfforts")) {
                if (text(option.getAsJsonObject(), "reasoningEffort").equals("xhigh")) { preferredEffort = "xhigh"; break; }
            }
            return new ModelPreferences("gpt-6-astra", preferredEffort);
        }
        return new ModelPreferences("", "");
    }
}
