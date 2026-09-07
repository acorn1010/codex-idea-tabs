package com.acorn.codextabs;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.*;
import javax.swing.*;
import java.awt.*;

/** Keep execution settings native and separate from the compact chat composer. */
public final class CodexConfigurable implements Configurable {
    private final Project project;
    private final JBTextField binary = new JBTextField();
    private final JBTextField distro = new JBTextField();
    private final JBTextField cwd = new JBTextField();
    public CodexConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Codex Tabs"; }
    @Override public JComponent createComponent() {
        var panel = new JPanel(new GridBagLayout());
        var c = new GridBagConstraints();
        c.insets = new Insets(6, 8, 6, 8); c.fill = GridBagConstraints.HORIZONTAL;
        String[] labels = {"Codex executable", "WSL distribution", "Working directory"};
        JBTextField[] fields = {binary, distro, cwd};
        for (int i = 0; i < fields.length; i++) {
            c.gridy = i; c.gridx = 0; c.weightx = 0; panel.add(new JBLabel(labels[i]), c);
            c.gridx = 1; c.weightx = 1; fields[i].setColumns(38); panel.add(fields[i], c);
        }
        c.gridy = 3; c.gridx = 0; c.gridwidth = 2;
        panel.add(new JBLabel("<html>Use an executable path inside WSL when a distribution is selected.<br>Leave distribution empty to detect it from a WSL project path.<br>Leave working directory empty to use the current project.<br>Sign in with your ChatGPT account from a chat. Codex stores the credentials.</html>"), c);
        c.gridy = 4; c.weighty = 1; c.fill = GridBagConstraints.BOTH; panel.add(new JPanel(), c);
        reset(); return panel;
    }
    @Override public boolean isModified() {
        var state = CodexService.get(project).settings();
        return !binary.getText().equals(state.binary) || !distro.getText().equals(state.distro) || !cwd.getText().equals(state.cwd);
    }
    @Override public void reset() {
        var state = CodexService.get(project).settings();
        binary.setText(state.binary); distro.setText(state.distro); cwd.setText(state.cwd);
    }
    @Override public void apply() {
        var service = CodexService.get(project); var state = service.settings();
        state.binary = binary.getText().trim(); state.distro = distro.getText().trim(); state.cwd = cwd.getText().trim();
        service.reconnect();
    }
}
