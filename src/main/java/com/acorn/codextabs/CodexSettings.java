package com.acorn.codextabs;

import com.intellij.openapi.components.*;

/** Project settings follow the workspace while API keys stay in the IDE credential store. */
@State(name = "CodexTabsSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
@Service(Service.Level.PROJECT)
public final class CodexSettings implements PersistentStateComponent<CodexSettings.State> {
    public static final class State {
        public String binary = "codex";
        public String claudeBinary = "claude";
        public String provider = "codex";
        public String apiConnectionId = "";
        public java.util.List<com.acorn.codextabs.core.ApiConnection> apiConnections = new java.util.ArrayList<>();
        public String claudeModel = "";
        public String claudeEffort = "";
        public boolean claudeFast;
        public String claudePermissions = "ask";
        public String claudeAllowRules = "";
        public boolean claudeAllowWebDownloads = true;
        public boolean claudeFilterResponseHeaders = true;
        public java.util.List<String> claudeApprovals = new java.util.ArrayList<>();
        public String distro = "";
        public String cwd = "";
        public String additionalWritableFolders = "";
        public boolean sharedGuidanceEnabled = true;
        public String sharedGuidanceFolder = "";
        public boolean localModelEnabled;
        public String localModelUrl = "http://127.0.0.1:30001/v1";
        public String localModelId = com.acorn.codextabs.core.LocalModel.MODEL;
        public int localModelContextWindow = 98304;
        public String localModelProfile = "auto";
        public java.util.List<String> localModelHistory = new java.util.ArrayList<>();
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
