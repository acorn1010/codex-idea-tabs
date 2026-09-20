# Changelog

## 1.0.11

- Copy the whole chat as Markdown, including older messages, tool activity, and image references. Works with archived chats and preserves the visible history and draft.
- Replace the Search and Split Screen header icons with Copy chat. Chat search and native split actions remain available.
- Replace the chat Settings icon with Archive. Restore archived chats when needed.
- Remove a linked worktree directly from an archived chat, with the same checks and explicit discard confirmation as the workspace menu.
- Avoid full repository scans during worktree removal.

## 1.0.10

- Allow clean worktrees on named branches to be removed after squash merges. Keep their branches and commits, and explain this in the confirmation.
- Show changed, untracked, and ignored files before an explicit Discard changes and remove confirmation. No worktree backup is created.
- Keep removal blocked for locks, active chats, open projects, and detached commits that have not reached the primary checkout.

## 1.0.9

- Replace the Start from browser popup with a searchable branch dropdown that also accepts Git references.
- Give the workspace selector more room and omit the codex/ branch prefix from its compact label.
- Respect IDEA keymap shortcuts for word and line navigation in chat text fields, including Shift selection.

## 1.0.8

- Share an IDEA project’s AGENTS.md and .agents/skills with subrepo and worktree chats from their original locations.
- Add a shared guidance folder setting with automatic project detection and an off switch.
- Keep shared guidance when opening a worktree as a new IDEA project.

## 1.0.7

- Remove the redundant Cancel request action from connector approval cards. Use Decline to refuse permission.

## 1.0.6

- Honor connector approval durations for Computer Use, Browser Use, and other MCP approvals. Return the selected duration to the connector and retain form answers.
- Show all supported network decisions, including saved block rules.

## 1.0.5

- Add Always allow for command and network rules offered by Codex. Show the rule scope and support session approvals for commands, file edits, and requested permissions.

## 1.0.4

- Focus the message input when opening a new chat, including chats opened beside the current editor. Wait for the input to render and keep focus in another tab if the user has moved away.

## 1.0.3

- Choose a repository per chat when an IDEA project contains several Git repositories. New chats opened from files use the nearest repository.
- Route worktree creation, branch choices, review, history, and removal to the correct repository. Show repository names beside branches.

## 1.0.2

- Detect the Codex executable from CLI and app installations on macOS when it is missing from the IDE PATH. Keep explicit executable paths unchanged.
- Show WSL settings and startup guidance only where WSL is supported.

## 1.0.1

- Fix chat editors failing to open in IntelliJ IDEA 2026.2 with `NoClassDefFoundError: com/intellij/ui/jcef/JBCefBrowserBase` by declaring the required JCEF dependency.

## 1.0.0

First stable release, including the worktree, context, and chat improvements listed below.

- Copy individual fenced code and Markdown blocks without copying the full response.
- Drop files from the native IDE or file manager into chat, including when Codex runs in WSL.
- Request readable reasoning summaries and keep empty thinking rows from expanding.
- Keep sidebar archive and menu clicks aligned with their icons while chats update.
- Support newer IDEA APIs for worktree actions and use portable worktree menu rendering.

## 0.1.1

- Copy individual code and Markdown blocks from their top-right corner.
- Keep sidebar action targets aligned with their icons, prevent long-title tooltips from covering them, and retain clicks during chat updates.
- Keep terminal and worktree project actions compatible with newer IDEA releases.
- Create and share Git worktrees across chats, with native diffs, terminals, and guarded cleanup.
- Use slash commands and skills from the composer, with compact context and account status.
- Inspect recorded and startup context with search and text export.
- Edit earlier messages in a separate chat, add or remove images, and recall previous messages with Up arrow.
- Preview attachments and copy full-size chat images to the clipboard.
- Expand grouped tool activity and steer a running turn immediately with Ctrl+Enter.
- Keep model and reasoning choices across tabs and restarts.
- Restore saved chats and paged history in order, release idle closed chats, and clear recovered connection errors.
- Keep composer controls compact in narrow panes and restore native text cursors.

## 0.1.0

First release of Codex Tabs for IntelliJ IDEA.

- Native conversation tabs with split layouts, restoration, and work and attention icons.
- One Codex process per project, paged history, and incremental streaming updates.
- A grouped sidebar with search, message previews, quiet row actions, and a clear New chat action.
- Archive, Undo, archived-history browsing, and Restore, with protection for active work and pending questions.
- A compact composer with readable permission and model menus and clear hover and pressed states.
- Flat chat surfaces, warm question interactions, and sidebar selection without expanding hover rows.
- File drops, large text pastes, image attachments, generated-image previews, and WSL file links.
- ChatGPT subscription sign-in, approval requests, asynchronous questions, and selected-code context.
- Portable core tests, an opt-in live Codex test, and a native IDEA smoke harness.
