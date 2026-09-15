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
    @Test void doesNotOfferSavedApprovalsForElicitationOrQuestions() {
        for (var method : new String[]{"mcpServer/elicitation/request", "item/tool/requestUserInput"}) {
            assertTrue(ApprovalDecisions.choices(object("method", method)).isEmpty());
        }
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
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/approval-fixtures.json"), GSON.toJson(fixtures));
    }
}
