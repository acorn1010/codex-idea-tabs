package com.acorn.codextabs;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;

/** Access PasswordSafe on a worker thread, never while rendering settings. */
final class ApiKeys {
    private ApiKeys() {}
    private static CredentialAttributes attributes(Project project, String id) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("Codex Tabs", project.getLocationHash() + "/" + id));
    }
    static String read(Project project, String id) {
        return java.util.Objects.toString(PasswordSafe.getInstance().getPassword(attributes(project, id)), "");
    }
    static void write(Project project, String id, String key) {
        PasswordSafe.getInstance().setPassword(attributes(project, id), key.isEmpty() ? null : key);
    }
}
