package com.acorn.codextabs;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.components.*;
import javax.swing.*;
import java.awt.*;

/** Keep execution settings native and separate from the compact chat composer. */
public final class CodexConfigurable implements Configurable {
    private final Project project;
    private final JBTextField binary = new JBTextField();
    private final JBTextField claudeBinary = new JBTextField();
    private final JBTextField distro = new JBTextField();
    private final JBTextField cwd = new JBTextField();
    private final JBTextField sharedGuidance = new JBTextField();
    private final JBCheckBox sharedGuidanceEnabled = new JBCheckBox("Use shared project guidance");
    public CodexConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Codex Tabs"; }
    @Override public JComponent createComponent() {
        var panel = new JPanel(new GridBagLayout());
        var c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8); c.fill = GridBagConstraints.HORIZONTAL;
        String[] labels = SystemInfo.isWindows
            ? new String[]{"Codex executable", "Claude executable", "WSL distribution", "Working directory", "Shared guidance folder"}
            : new String[]{"Codex executable", "Claude executable", "Working directory", "Shared guidance folder"};
        JBTextField[] fields = SystemInfo.isWindows
            ? new JBTextField[]{binary, claudeBinary, distro, cwd, sharedGuidance} : new JBTextField[]{binary, claudeBinary, cwd, sharedGuidance};
        for (int i = 0; i < fields.length; i++) {
            c.gridy = i; c.gridx = 0; c.weightx = 0; panel.add(new JBLabel(labels[i]), c);
            c.gridx = 1; c.weightx = 1; fields[i].setColumns(38); panel.add(fields[i], c);
        }
        c.gridy = fields.length; c.gridx = 0; c.gridwidth = 2;
        panel.add(sharedGuidanceEnabled, c);
        sharedGuidanceEnabled.addActionListener(event -> sharedGuidance.setEnabled(sharedGuidanceEnabled.isSelected()));
        sharedGuidance.getEmptyText().setText("Auto: IDEA project folder");
        c.gridy++;
        String help = SystemInfo.isWindows
            ? "Use an executable path inside WSL when a distribution is selected.<br>Leave distribution empty to detect it from a WSL project path.<br>"
            : SystemInfo.isMac
                ? "Codex runs directly on macOS.<br>Leave the executable as codex to detect a CLI or app installation, or enter its full path.<br>"
                : "Codex runs directly on this computer. Enter its full path if it is not on PATH.<br>";
        panel.add(new JBLabel("<html>" + help + "Leave working directory empty to use the current project.<br>Leave shared guidance empty to detect AGENTS.md and .agents in the IDEA project folder.<br>Shared guidance applies to this project’s chats, including subrepos and external worktrees.<br>Changes apply when chats reconnect. Linked files stay in their original locations.<br>Sign in with ChatGPT from a Codex chat.<br>For Claude, install Claude Code and sign in by running claude in a terminal.<br>Each CLI keeps its own credentials. Choose the provider before sending the first message.</html>"), c);
        c.gridy = fields.length + 2; c.weighty = 1; c.fill = GridBagConstraints.BOTH; panel.add(new JPanel(), c);
        reset(); return panel;
    }
    @Override public boolean isModified() {
        var state = CodexService.get(project).settings();
        return !claudeBinary.getText().equals(state.claudeBinary) || !binary.getText().equals(state.binary) || !distro.getText().equals(state.distro) || !cwd.getText().equals(state.cwd)
            || !sharedGuidance.getText().equals(state.sharedGuidanceFolder) || sharedGuidanceEnabled.isSelected() != state.sharedGuidanceEnabled;
    }
    @Override public void reset() {
        var state = CodexService.get(project).settings();
        claudeBinary.setText(state.claudeBinary); binary.setText(state.binary); distro.setText(state.distro); cwd.setText(state.cwd);
        sharedGuidance.setText(state.sharedGuidanceFolder); sharedGuidanceEnabled.setSelected(state.sharedGuidanceEnabled);
        sharedGuidance.setEnabled(state.sharedGuidanceEnabled);
    }
    @Override public void apply() throws com.intellij.openapi.options.ConfigurationException {
        try {
            com.acorn.codextabs.core.SharedGuidance.resolve(sharedGuidanceEnabled.isSelected(), sharedGuidance.getText().trim(),
                java.util.Objects.toString(project.getBasePath(), ""), SystemInfo.isWindows ? (distro.getText().isBlank() ? com.acorn.codextabs.core.Paths.distro(java.util.Objects.toString(project.getBasePath(), "")) : distro.getText().trim()) : "", SystemInfo.isWindows);
        } catch (RuntimeException error) { throw new com.intellij.openapi.options.ConfigurationException(error.getMessage()); }
        var service = CodexService.get(project); var state = service.settings();
        state.claudeBinary = claudeBinary.getText().trim(); state.binary = binary.getText().trim(); state.distro = distro.getText().trim(); state.cwd = cwd.getText().trim();
        state.sharedGuidanceEnabled = sharedGuidanceEnabled.isSelected(); state.sharedGuidanceFolder = sharedGuidance.getText().trim();
        service.reconnect();
    }
}
