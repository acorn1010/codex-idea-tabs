package com.acorn.codextabs;

import com.acorn.codextabs.core.ApiConnection;
import com.acorn.codextabs.core.ApiProbe;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Editable connection drafts. Cancel never writes credentials or starts a backend. */
final class ApiConnectionsPanel extends JPanel {
    private final Project project;
    private final DefaultListModel<ApiConnection> entries = new DefaultListModel<>();
    private final JBList<ApiConnection> list = new JBList<>(entries);
    private final JComboBox<Object> preferred = new JComboBox<>();
    private final JBTextField name = new JBTextField();
    private final JBTextField url = new JBTextField();
    private final JPasswordField key = new JPasswordField();
    private final JBTextArea models = new JBTextArea(3, 28);
    private final JBCheckBox clearKey = new JBCheckBox("Remove saved key");
    private final JBLabel keyHint = CodexConfigurable.hint("");
    private final JBLabel status = CodexConfigurable.hint(" ");
    private final JButton check = new JButton("Check API");
    private final JButton remove = new JButton("Remove");
    private final JPanel details = new JPanel(new CardLayout());
    private final Map<String, String> changedKeys = new HashMap<>();
    private ApiConnection editing;
    private boolean resetting;
    private boolean disposed;
    private long checkGeneration;

    ApiConnectionsPanel(Project project) {
        super(new BorderLayout(0, JBUI.scale(16))); this.project = project;
        var heading = new JPanel(new GridBagLayout());
        CodexConfigurable.row(heading, "New chats use", preferred, null);
        add(heading, BorderLayout.NORTH);
        var body = new JPanel(new BorderLayout(JBUI.scale(20), 0));
        var sidebar = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        sidebar.setPreferredSize(JBUI.size(180, 310));
        sidebar.add(new JBScrollPane(list), BorderLayout.CENTER);
        var buttons = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(6)));
        for (String title : List.of("Add Routeway", "Add custom API")) {
            var button = new JButton(title); buttons.add(button);
            button.addActionListener(event -> addConnection(title.equals("Add Routeway")));
        }
        buttons.add(remove); remove.addActionListener(event -> removeConnection()); sidebar.add(buttons, BorderLayout.SOUTH);
        body.add(sidebar, BorderLayout.WEST);
        var form = CodexConfigurable.section("API connection", "Responses API with tool calling. Powered by Codex.");
        CodexConfigurable.row(form, "Name", name, null);
        CodexConfigurable.row(form, "Base URL", url, "Include the API version path, such as /v1.");
        CodexConfigurable.row(form, "API key", key, null);
        CodexConfigurable.fullRow(form, keyHint);
        CodexConfigurable.fullRow(form, clearKey);
        models.setLineWrap(false);
        CodexConfigurable.row(form, "Model IDs", new JBScrollPane(models), "One per line. The first model is the default.");
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); actions.add(check);
        CodexConfigurable.fullRow(form, actions); CodexConfigurable.fullRow(form, status);
        check.addActionListener(event -> checkApi());
        details.add(CodexConfigurable.section("Your own models, in the same chat", "Add an API connection to choose it from the provider menu."), "empty");
        var formContainer = new JPanel(new BorderLayout()); formContainer.add(form, BorderLayout.NORTH);
        details.add(formContainer, "form"); body.add(details, BorderLayout.CENTER); add(body, BorderLayout.CENTER);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !resetting) { flush(); show(list.getSelectedValue()); } });
        var changed = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { invalidateCheck(); }
            @Override public void removeUpdate(DocumentEvent event) { invalidateCheck(); }
            @Override public void changedUpdate(DocumentEvent event) { invalidateCheck(); }
        };
        for (var document : List.of(name.getDocument(), url.getDocument(), key.getDocument(), models.getDocument())) { document.addDocumentListener(changed); }
        clearKey.addActionListener(event -> { key.setEnabled(!clearKey.isSelected()); invalidateCheck(); });
    }
    private void invalidateCheck() {
        checkGeneration++; check.setEnabled(true); status.setText(" ");
    }
    private void flush() {
        if (editing == null) { return; }
        editing.name = name.getText().strip(); editing.baseUrl = url.getText().strip().replaceAll("/+$", "");
        editing.models = new ArrayList<>(models.getText().lines().map(String::strip).filter(value -> !value.isBlank()).distinct().toList());
        if (!editing.models.contains(editing.selectedModel)) { editing.selectedModel = ""; }
        char[] password = key.getPassword();
        try {
            if (clearKey.isSelected()) { changedKeys.put(editing.id, ""); editing.hasKey = false; }
            else if (password.length > 0) { changedKeys.put(editing.id, new String(password).strip()); editing.hasKey = !changedKeys.get(editing.id).isEmpty(); }
        } finally { Arrays.fill(password, '\0'); }
        list.repaint(); preferred.repaint();
    }
    private void show(ApiConnection entry) {
        editing = entry; checkGeneration++; remove.setEnabled(entry != null);
        ((CardLayout) details.getLayout()).show(details, entry == null ? "empty" : "form");
        name.setText(entry == null ? "" : entry.name); url.setText(entry == null ? "" : entry.baseUrl);
        models.setText(entry == null ? "" : String.join("\n", entry.models));
        key.setText(entry == null ? "" : changedKeys.getOrDefault(entry.id, ""));
        clearKey.setSelected(entry != null && changedKeys.containsKey(entry.id) && changedKeys.get(entry.id).isEmpty());
        clearKey.setVisible(entry != null && (entry.hasKey || clearKey.isSelected()));
        clearKey.setEnabled(entry != null && (entry.hasKey || clearKey.isSelected())); key.setEnabled(!clearKey.isSelected());
        keyHint.setText(entry != null && entry.hasKey ? "Saved securely. Leave blank to keep the current key." : "Stored in the IDE credential store. Optional for local servers.");
        status.setText(" ");
    }
    private void addConnection(boolean routeway) {
        flush(); var entry = new ApiConnection(); entry.id = UUID.randomUUID().toString();
        entry.name = routeway ? "Routeway" : "Custom API";
        entry.baseUrl = routeway ? "https://api.routeway.ai/v1" : "";
        if (routeway) { entry.models.add("glm-5.3-flash-uncensored"); }
        entries.addElement(entry); preferred.addItem(entry); list.setSelectedValue(entry, true); name.requestFocusInWindow();
    }
    private void removeConnection() {
        var entry = list.getSelectedValue(); if (entry == null) { return; }
        if (preferred.getSelectedItem() == entry) { preferred.setSelectedItem("Codex"); }
        preferred.removeItem(entry); entries.removeElement(entry); changedKeys.remove(entry.id);
        if (!entries.isEmpty()) { list.setSelectedIndex(0); } else { show(null); }
    }
    void reset(CodexSettings.State state) {
        resetting = true; editing = null; changedKeys.clear(); entries.clear(); preferred.removeAllItems();
        preferred.addItem("Codex"); preferred.addItem("Claude");
        for (var stored : state.apiConnections) { var entry = stored.copy(); entries.addElement(entry); preferred.addItem(entry); }
        preferred.setSelectedItem(state.provider.equals("claude") ? "Claude" : "Codex");
        if (!state.provider.equals("claude")) {
            for (var entry : Collections.list(entries.elements())) { if (entry.id.equals(state.apiConnectionId)) { preferred.setSelectedItem(entry); } }
        }
        resetting = false;
        if (!entries.isEmpty()) { list.setSelectedIndex(0); } else { show(null); }
    }
    List<ApiConnection> connections() { flush(); return Collections.list(entries.elements()).stream().map(ApiConnection::copy).toList(); }
    String provider() { return "Claude".equals(preferred.getSelectedItem()) ? "claude" : "codex"; }
    String preferredId() { return preferred.getSelectedItem() instanceof ApiConnection entry ? entry.id : ""; }
    boolean keysModified() { flush(); return !changedKeys.isEmpty(); }
    Map<String, String> keyChanges() { flush(); return Map.copyOf(changedKeys); }
    void dispose() { disposed = true; checkGeneration++; changedKeys.clear(); key.setText(""); }

    /** A bounded metadata request checks authentication without spending tokens or following redirects. */
    private void checkApi() {
        flush(); if (editing == null) { return; }
        var entry = editing.copy();
        try { entry.validate(); } catch (IllegalArgumentException error) { status.setText(error.getMessage()); return; }
        String supplied = changedKeys.get(entry.id); long generation = ++checkGeneration;
        check.setEnabled(false); status.setForeground(JBColor.GRAY); status.setText("Checking API…");
        CompletableFuture.supplyAsync(() -> {
            try {
                String secret = supplied == null && entry.hasKey ? ApiKeys.read(project, entry.id) : Objects.toString(supplied, "");
                return ApiProbe.check(entry, secret);
            } catch (Exception error) { return "Could not check the API. Check the URL and your network connection."; }
        }).thenAccept(message -> SwingUtilities.invokeLater(() -> {
            if (!disposed && generation == checkGeneration) { check.setEnabled(true); status.setText("<html><div style='width: 330px'>" + message + "</div></html>"); }
        }));
    }
}
