package com.acorn.codextabs;

import com.intellij.openapi.components.*;

/** Project settings follow the workspace while authentication stays in Codex's own credential store. */
@State(name = "CodexTabsSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
@Service(Service.Level.PROJECT)
public final class CodexSettings implements PersistentStateComponent<CodexSettings.State> {
    public static final class State {
        public String binary = "codex";
        public String claudeBinary = "claude";
        public String provider = "codex";
        public String claudeModel = "";
        public String claudeEffort = "";
        public boolean claudeFast;
        public String claudePermissions = "ask";
        public java.util.List<String> claudeApprovals = new java.util.ArrayList<>();
        public String distro = "";
        public String cwd = "";
        public boolean sharedGuidanceEnabled = true;
        public String sharedGuidanceFolder = "";
        public String model = "";
        public String effort = "";
        public boolean modelSelectionSaved;
        public boolean fast;
        public boolean planMode;
        public String permissions = "auto";
    }
    private State state = new State();
    @Override public State getState() { return state; }
    @Override public void loadState(State value) { state = value; }
}
