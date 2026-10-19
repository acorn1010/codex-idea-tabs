package com.acorn.codextabs;

import com.acorn.codextabs.core.SharedGuidance;
import com.acorn.codextabs.core.SandboxSettings;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Native, project-scoped settings with credential IO and reconnects off the event thread. */
public final class CodexConfigurable implements Configurable {
    private final Project project;
    private final JBTextField binary = new JBTextField();
    private final JBTextField claudeBinary = new JBTextField();
    private final JBTextField distro = new JBTextField();
    private final JBTextField cwd = new JBTextField();
    private final JBTextArea additionalWritableFolders = new JBTextArea(4, 30);
    private final JBTextField localModelUrl = new JBTextField();
    private final JBCheckBox localModelEnabled = new JBCheckBox("Enable local GLM with Codex");
    private final JBTextField sharedGuidance = new JBTextField();
    private final JBCheckBox sharedGuidanceEnabled = new JBCheckBox("Use shared project guidance");
    private ApiConnectionsPanel connections;
    public CodexConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Codex Tabs"; }
    @Override public JComponent createComponent() {
        var root = new JPanel(new BorderLayout(0, JBUI.scale(16)));
        root.setBorder(JBUI.Borders.empty(16));
        var title = new JBLabel("Codex Tabs"); title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        var header = new JPanel(new BorderLayout(0, JBUI.scale(6))); header.add(title, BorderLayout.NORTH);
        header.add(hint("Settings for " + project.getName() + ". Apply changes without restarting IntelliJ."), BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);
        var tabs = new JBTabbedPane();
        connections = new ApiConnectionsPanel(project); connections.setBorder(JBUI.Borders.empty(16, 0));
        tabs.addTab("Connections", new JBScrollPane(connections));
        var local = section("Local GLM", "Add GLM 5.3 Flash to the Codex model menu. Start your model server before chatting.");
        fullRow(local, localModelEnabled);
        row(local, "API URL", localModelUrl, "Use a local Responses server, such as http://127.0.0.1:30001/v1. No sign-in needed.");
        if (SystemInfo.isWindows) { fullRow(local, hint("WSL needs Python 3. Windows curl forwards requests to the Windows API.")); }
        localModelEnabled.addActionListener(event -> localModelUrl.setEnabled(localModelEnabled.isSelected()));
        tabs.addTab("Local GLM", page(local));
        var tools = section("Command-line tools", "Codex Tabs uses your installed agents and their existing sign-ins.");
        row(tools, "Codex executable", binary, "Use codex for automatic detection, or enter its full path.");
        row(tools, "Claude executable", claudeBinary, "Use claude, or enter its full path. Sign in from a terminal.");
        if (SystemInfo.isWindows) { row(tools, "WSL distribution", distro, "Leave blank to detect it from the project path."); }
        tabs.addTab("Command-line tools", page(tools));
        var workspace = section("Workspace", "Keep shared instructions available to subrepos and worktrees.");
        cwd.getEmptyText().setText("Current IntelliJ project"); sharedGuidance.getEmptyText().setText("Detect from the IntelliJ project folder");
        row(workspace, "Working directory", cwd, null);
        additionalWritableFolders.getAccessibleContext().setAccessibleName("Additional writable folders");
        row(workspace, "Additional writable folders", new JBScrollPane(additionalWritableFolders),
            "One absolute folder per line. Grants Codex chats in this project write access there. Read only mode stays read only.");
        if (SystemInfo.isWindows) { fullRow(workspace, hint("With WSL, use Linux paths, Windows paths, or paths from the selected WSL distribution.")); }
        fullRow(workspace, sharedGuidanceEnabled);
        row(workspace, "Guidance folder", sharedGuidance, "Reads AGENTS.md and .agents. Linked files stay in their original locations.");
        sharedGuidanceEnabled.addActionListener(event -> sharedGuidance.setEnabled(sharedGuidanceEnabled.isSelected()));
        tabs.addTab("Workspace", page(workspace)); root.add(tabs, BorderLayout.CENTER);
        root.add(hint("Changes apply when chats are idle. No restart needed."), BorderLayout.SOUTH);
        reset(); return root;
    }
    private static JComponent page(JPanel content) {
        var page = new JPanel(new BorderLayout()); page.setBorder(JBUI.Borders.empty(16)); page.add(content, BorderLayout.NORTH); return new JBScrollPane(page);
    }
    static JPanel section(String title, String description) {
        var panel = new JPanel(new GridBagLayout());
        var heading = new JBLabel(title); heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        fullRow(panel, heading); fullRow(panel, hint(description)); return panel;
    }
    static JBLabel hint(String text) {
        var label = new JBLabel("<html><div style='width: 330px'>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</div></html>");
        label.setForeground(JBColor.GRAY); return label;
    }
    static void fullRow(JPanel panel, JComponent component) {
        var c = constraints(panel); c.gridwidth = 2; c.weightx = 1; panel.add(component, c);
    }
    static void row(JPanel panel, String label, JComponent field, String help) {
        var c = constraints(panel); c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.NORTHWEST;
        var text = new JBLabel(label); text.setLabelFor(field); panel.add(text, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(field, c);
        if (help != null) { var h = constraints(panel); h.gridx = 1; h.weightx = 1; panel.add(hint(help), h); }
    }
    private static GridBagConstraints constraints(JPanel panel) {
        var c = new GridBagConstraints(); c.gridx = 0; c.gridy = panel.getComponentCount();
        c.anchor = GridBagConstraints.NORTHWEST; c.fill = GridBagConstraints.HORIZONTAL; c.insets = JBUI.insets(5, 0, 5, 12); return c;
    }
    @Override public boolean isModified() {
        if (connections == null) { return false; }
        var state = CodexService.get(project).settings();
        return !claudeBinary.getText().equals(state.claudeBinary) || !binary.getText().equals(state.binary) || !distro.getText().equals(state.distro) || !cwd.getText().equals(state.cwd)
            || !additionalWritableFolders.getText().equals(state.additionalWritableFolders)
            || !sharedGuidance.getText().equals(state.sharedGuidanceFolder) || sharedGuidanceEnabled.isSelected() != state.sharedGuidanceEnabled
            || localModelEnabled.isSelected() != state.localModelEnabled || !localModelUrl.getText().equals(state.localModelUrl)
            || !connections.connections().equals(state.apiConnections) || connections.keysModified()
            || !connections.provider().equals(state.provider) || !connections.preferredId().equals(state.apiConnectionId);
    }
    @Override public void reset() {
        var state = CodexService.get(project).settings();
        claudeBinary.setText(state.claudeBinary); binary.setText(state.binary); distro.setText(state.distro); cwd.setText(state.cwd);
        additionalWritableFolders.setText(state.additionalWritableFolders); additionalWritableFolders.setCaretPosition(0);
        sharedGuidance.setText(state.sharedGuidanceFolder); sharedGuidanceEnabled.setSelected(state.sharedGuidanceEnabled);
        sharedGuidance.setEnabled(state.sharedGuidanceEnabled);
        localModelEnabled.setSelected(state.localModelEnabled); localModelUrl.setText(state.localModelUrl); localModelUrl.setEnabled(state.localModelEnabled);
        if (connections != null) { connections.reset(state); }
    }
    @Override public void apply() throws ConfigurationException {
        var entries = connections.connections();
        try {
            if (localModelEnabled.isSelected()) { com.acorn.codextabs.core.LocalModel.validateUrl(localModelUrl.getText().trim()); }
            for (var entry : entries) { entry.validate(); }
            String selectedDistro = SystemInfo.isWindows ? (distro.getText().isBlank() ? com.acorn.codextabs.core.Paths.distro(Objects.toString(project.getBasePath(), "")) : distro.getText().trim()) : "";
            for (String folder : SandboxSettings.folders(additionalWritableFolders.getText(), selectedDistro, SystemInfo.isWindows)) {
                if (!java.nio.file.Files.isDirectory(java.nio.file.Path.of(com.acorn.codextabs.core.Paths.host(folder, selectedDistro, SystemInfo.isWindows)))) {
                    throw new IllegalArgumentException("Writable folder does not exist or is not a directory: " + folder);
                }
            }
            SharedGuidance.resolve(sharedGuidanceEnabled.isSelected(), sharedGuidance.getText().trim(), Objects.toString(project.getBasePath(), ""), selectedDistro, SystemInfo.isWindows);
        } catch (RuntimeException error) { throw new ConfigurationException(error.getMessage()); }
        var service = CodexService.get(project); var state = service.settings();
        boolean all = !state.binary.equals(binary.getText().trim()) || !state.claudeBinary.equals(claudeBinary.getText().trim()) || !state.distro.equals(distro.getText().trim())
            || !state.cwd.equals(cwd.getText().trim()) || !state.sharedGuidanceFolder.equals(sharedGuidance.getText().trim()) || state.sharedGuidanceEnabled != sharedGuidanceEnabled.isSelected();
        boolean api = !state.apiConnections.equals(entries) || connections.keysModified()
            || !state.additionalWritableFolders.equals(additionalWritableFolders.getText())
            || state.localModelEnabled != localModelEnabled.isSelected() || !state.localModelUrl.equals(localModelUrl.getText().trim());
        var keys = new java.util.HashMap<>(connections.keyChanges());
        for (var entry : state.apiConnections) { if (entries.stream().noneMatch(value -> value.id.equals(entry.id))) { keys.put(entry.id, ""); } }
        var failure = new AtomicReference<RuntimeException>();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            try { keys.forEach((id, key) -> ApiKeys.write(project, id, key)); }
            catch (RuntimeException error) { failure.set(error); }
        }, "Saving API credentials", false, project);
        if (failure.get() != null) { throw new ConfigurationException("Could not save the API key in the IDE credential store. Your settings have not been applied."); }
        state.claudeBinary = claudeBinary.getText().trim(); state.binary = binary.getText().trim(); state.distro = distro.getText().trim(); state.cwd = cwd.getText().trim();
        state.additionalWritableFolders = additionalWritableFolders.getText();
        state.sharedGuidanceEnabled = sharedGuidanceEnabled.isSelected(); state.sharedGuidanceFolder = sharedGuidance.getText().trim();
        state.localModelEnabled = localModelEnabled.isSelected(); state.localModelUrl = localModelUrl.getText().trim();
        state.apiConnections = new java.util.ArrayList<>(entries); state.provider = connections.provider(); state.apiConnectionId = connections.preferredId();
        service.settingsApplied(all, api); connections.reset(state);
    }
    @Override public void disposeUIResources() { if (connections != null) { connections.dispose(); connections = null; } }
}
