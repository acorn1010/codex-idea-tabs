package com.acorn.codextabs;

import com.intellij.openapi.components.*;

/** Project settings follow the workspace while authentication stays in Codex's own credential store. */
@State(name = "CodexTabsSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
@Service(Service.Level.PROJECT)
public final class CodexSettings implements PersistentStateComponent<CodexSettings.State> {
    public static final class State {
        public String binary = "codex";
        public String distro = "";
        public String cwd = "";
        public String model = "";
        public String effort = "";
        public boolean modelSelectionSaved;
        public String permissions = "auto";
    }
    private State state = new State();
    @Override public State getState() { return state; }
    @Override public void loadState(State value) { state = value; }
}
