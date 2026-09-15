package com.acorn.codextabs.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.acorn.codextabs.core.Json.*;

class ApprovalDecisionsTest {
    private static JsonObject command() { return object("method", "item/commandExecution/requestApproval", "command", "git status --short"); }
    private static JsonObject persistent(String... prefix) { return object("acceptWithExecpolicyAmendment", object("execpolicy_amendment", prefix)); }
    @Test void savesOnlyTheOfferedCommandPrefix() {
        var pending = command(); pending.add("proposedExecpolicyAmendment", GSON.toJsonTree(new String[]{"git", "status"}));
        var decision = persistent("git", "status");
        assertEquals(object("decision", decision), ApprovalDecisions.response(pending, decision));
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, persistent("git")));
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(command(), decision));
    }
    @Test void respectsExplicitServerChoicesEvenWhenAProposalExists() {
        var pending = command();
        pending.add("proposedExecpolicyAmendment", GSON.toJsonTree(new String[]{"git"}));
        pending.add("availableDecisions", GSON.toJsonTree(new String[]{"decline", "accept"}));
        assertEquals(2, ApprovalDecisions.choices(pending).size());
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, persistent("git")));
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, new JsonPrimitive("acceptForSession")));
        pending.add("availableDecisions", new JsonArray());
        assertTrue(ApprovalDecisions.choices(pending).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, new JsonPrimitive("accept")));
    }
    @Test void returnsStructuredServerChoicesWithoutFlatteningThem() {
        var pending = command();
        var decisions = new JsonArray(); decisions.add(persistent("git", "status")); pending.add("availableDecisions", decisions);
        assertEquals(object("decision", decisions.get(0)), ApprovalDecisions.response(pending, decisions.get(0)));
        assertEquals("Always allow", text(ApprovalDecisions.choices(pending).get(0).getAsJsonObject(), "label"));
    }
    @Test void savesOnlyTheProposedNetworkHost() {
        var pending = command();
        pending.add("proposedNetworkPolicyAmendments", GSON.toJsonTree(new Object[]{object("host", "example.com", "action", "allow")}));
        var decision = object("applyNetworkPolicyAmendment", object("network_policy_amendment", object("host", "example.com", "action", "allow")));
        assertEquals(object("decision", decision), ApprovalDecisions.response(pending, decision));
        var wrongHost = object("applyNetworkPolicyAmendment", object("network_policy_amendment", object("host", "other.example.com", "action", "allow")));
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, wrongHost));
    }
    @Test void fileAndPermissionGrantsAreOnlySessionScoped() {
        var files = object("method", "item/fileChange/requestApproval");
        assertEquals(object("decision", "acceptForSession"), ApprovalDecisions.response(files, new JsonPrimitive("acceptForSession")));
        var permissions = object("method", "item/permissions/requestApproval", "permissions", object("network", object("enabled", true)));
        assertEquals(object("permissions", obj(permissions, "permissions"), "scope", "session"), ApprovalDecisions.response(permissions, new JsonPrimitive("acceptForSession")));
        assertEquals("turn", text(ApprovalDecisions.response(permissions, new JsonPrimitive("accept")), "scope"));
        assertEquals(object("permissions", object(), "scope", "turn"), ApprovalDecisions.response(permissions, new JsonPrimitive("decline")));
        for (var pending : new JsonObject[]{files, permissions}) {
            assertFalse(ApprovalDecisions.choices(pending).toString().contains("Always allow"));
            assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, persistent("git")));
        }
    }
    private static JsonObject elicitation(Object persist) {
        return object("method", "mcpServer/elicitation/request", "mode", "form", "serverName", "cua_repl",
            "message", "Allow Computer Use to use \"IntelliJ IDEA\"?", "requestedSchema", object("type", "object", "properties", object()),
            "_meta", object("codex_approval_kind", "mcp_tool_call", "connector_id", "computer-use", "connector_name", "Computer Use",
                "persist", persist, "tool_name", "get_app_state", "tool_params", object("app", "com.jetbrains.intellij")));
    }
    @Test void forwardsComputerUsePersistenceAsResponseMetadata() {
        var pending = elicitation(new String[]{"session", "always"});
        var original = pending.deepCopy();
        var content = object("confirmed", true);
        for (var scope : new String[]{"session", "always"}) {
            var decision = new JsonPrimitive(scope.equals("always") ? "acceptAlways" : "acceptForSession");
            assertEquals(object("action", "accept", "content", content, "_meta", object("persist", scope)), ApprovalDecisions.response(pending, decision, content));
        }
        assertEquals(object("action", "accept", "content", content), ApprovalDecisions.response(pending, new JsonPrimitive("accept"), content));
        assertEquals(original, pending);
    }
    @Test void supportsScalarScopesUsedByBrowserUseAndRejectsUnavailableDurations() {
        var browser = elicitation("always");
        assertEquals("always", text(obj(ApprovalDecisions.response(browser, new JsonPrimitive("acceptAlways")), "_meta"), "persist"));
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(browser, new JsonPrimitive("acceptForSession")));
        for (var persist : new Object[]{null, "session", new String[]{"session"}, false, new String[]{"unknown"}}) {
            var pending = elicitation(persist);
            assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, new JsonPrimitive("acceptAlways")));
        }
    }
    @Test void declineDoesNotReturnFormDataOrPersistence() {
        for (var decision : new String[]{"decline"}) {
            assertEquals(object("action", decision, "content", JsonNull.INSTANCE),
                ApprovalDecisions.response(elicitation("always"), new JsonPrimitive(decision), object("secret", "must not return")));
        }
    }
    @Test void connectorCardsHaveOneNegativeAction() {
        var pending = elicitation(new String[]{"session", "always"});
        var choices = ApprovalDecisions.choices(pending);
        assertTrue(choices.toString().contains("Decline"));
        assertFalse(choices.toString().contains("Cancel request"));
        assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, new JsonPrimitive("cancel")));
    }
    @Test void ordinaryQuestionsAndUnsupportedModesDoNotGainSavedApprovals() {
        assertTrue(ApprovalDecisions.choices(object("method", "item/tool/requestUserInput")).isEmpty());
        for (var mode : new String[]{"url", "openai/userVerification"}) {
            var pending = elicitation("always"); pending.addProperty("mode", mode);
            assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, new JsonPrimitive("acceptAlways")));
            if (mode.equals("openai/userVerification")) {
                assertThrows(IllegalArgumentException.class, () -> ApprovalDecisions.response(pending, new JsonPrimitive("accept")));
            }
        }
        for (var mode : new String[]{"form", "openai/form", "openaiForm"}) {
            var pending = elicitation("always"); pending.addProperty("mode", mode);
            assertEquals("always", text(obj(ApprovalDecisions.response(pending, new JsonPrimitive("acceptAlways")), "_meta"), "persist"));
        }
    }
    @Test void honorsOfferedNetworkBlockRules() {
        var pending = command();
        var decision = object("applyNetworkPolicyAmendment", object("network_policy_amendment", object("host", "example.com", "action", "deny")));
        var decisions = new JsonArray(); decisions.add(decision); pending.add("availableDecisions", decisions);
        assertEquals("Always decline", text(ApprovalDecisions.choices(pending).get(0).getAsJsonObject(), "label"));
        assertEquals(object("decision", decision), ApprovalDecisions.response(pending, decision));
    }
    @Test void snapshotsAndUpdatesCarryChoicesWithoutChangingRawRequests() throws Exception {
        var fixtures = new JsonArray();
        for (var method : new String[]{"item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval"}) {
            var chat = new Conversation(method, "/project");
            var params = object("itemId", "approval", "turnId", "turn", "command", "git status --short", "reason", "Run the requested repository check.",
                "proposedExecpolicyAmendment", new String[]{"git", "status"});
            if (method.equals("item/permissions/requestApproval")) { params.add("permissions", object("network", object("enabled", true))); }
            chat.event(object("id", 7, "method", method, "params", params));
            var pending = array(chat.snapshot(), "requests").get(0).getAsJsonObject();
            assertFalse(array(pending, "approvalChoices").isEmpty());
            assertEquals(pending, array(chat.changes(chat.revision()), "requests").get(0));
            assertFalse(chat.request("7").has("approvalChoices"));
            fixtures.add(pending);
        }
        for (var persist : new Object[]{new String[]{"session", "always"}, "always", "session", null}) {
            var pending = elicitation(persist);
            pending.addProperty("key", "mcp-fixture-" + fixtures.size()); pending.addProperty("rpcId", fixtures.size());
            pending.add("approvalChoices", ApprovalDecisions.choices(pending)); fixtures.add(pending);
        }
        var mcp = elicitation(new String[]{"session", "always"});
        var chat = new Conversation("mcp-test", "/project");
        chat.event(object("id", 9, "method", "mcpServer/elicitation/request", "params", mcp));
        assertEquals(4, array(array(chat.snapshot(), "requests").get(0).getAsJsonObject(), "approvalChoices").size());
        assertEquals(array(chat.snapshot(), "requests"), array(chat.changes(chat.revision()), "requests"));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/approval-fixtures.json"), GSON.toJson(fixtures));
    }
}
