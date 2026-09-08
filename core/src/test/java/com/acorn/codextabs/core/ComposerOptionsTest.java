package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static com.acorn.codextabs.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ComposerOptionsTest {
    private JsonArray models() { return JsonParser.parseString("[{\"model\":\"test\",\"isDefault\":true,\"serviceTiers\":[{\"id\":\"priority\",\"name\":\"Fast\"}]}]").getAsJsonArray(); }
    @Test void fastUsesTheCatalogTierAndStandardExplicitlyResetsIt() {
        var params = new JsonObject();
        ComposerOptions.apply(params, object("fast", true), models(), "test", "high", false);
        assertEquals("priority", text(params, "serviceTierForTurn"));
        ComposerOptions.apply(params, object("fast", false), models(), "test", "high", false);
        assertEquals("default", text(params, "serviceTierForTurn"));
        assertThrows(IllegalArgumentException.class, () -> ComposerOptions.apply(params, object("fast", true), new JsonArray(), "other", "", false));
    }
    @Test void leavingPlanModeRestoresDefaultInstructions() {
        var params = new JsonObject();
        ComposerOptions.apply(params, object("planMode", true), models(), "", "high", false);
        assertEquals("plan", text(obj(params, "collaborationMode"), "mode"));
        assertEquals("test", text(obj(obj(params, "collaborationMode"), "settings"), "model"));
        assertFalse(obj(obj(params, "collaborationMode"), "settings").has("developer_instructions"));
        ComposerOptions.apply(params, object("planMode", false), models(), "test", "high", true);
        assertEquals("default", text(obj(params, "collaborationMode"), "mode"));
    }
    @Test void goalAndReviewInputsAreValidatedBeforeStartingAThread() {
        assertThrows(IllegalArgumentException.class, () -> ComposerOptions.goal(object("goalObjective", " "), "thread"));
        assertThrows(IllegalArgumentException.class, () -> ComposerOptions.goal(object("goalObjective", "Build it", "tokenBudget", 1.5), "thread"));
        assertEquals(100, ComposerOptions.goal(object("goalObjective", "Build it", "tokenBudget", 100), "thread").get("tokenBudget").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> ComposerOptions.review(object("type", "baseBranch", "branch", "")));
        assertEquals("main", text(ComposerOptions.review(object("type", "baseBranch", "branch", "main")), "branch"));
    }
}
