# Codex Tabs for IntelliJ IDEA

Independent Codex and Claude Code chats in native editor tabs. Keep several tasks beside your code, split them into groups, and see which conversations need your attention.

This is an independent client for the [Codex app server](https://developers.openai.com/codex/app-server/). It also supports [Claude Code CLI](https://code.claude.com/docs/en/cli-reference) through its streaming protocol. Each provider uses its own CLI sign-in. The plugin does not ask for an API key, copy credentials, or run its own cloud service.

## What it does

- Opens each conversation in an ordinary IDEA editor tab. Tabs can be split, moved, pinned, closed, and restored by the IDE. Displayed titles are capped at 40 characters, with the full title in the tooltip.
- Uses blue tab icons for work in progress, amber for questions or approvals, and green for unread results.
- Keeps hidden tabs connected. Closed chats finish active work in the background, then release their subscriptions. Drafts, history, and asynchronous questions remain available.
- Groups chats by attention, work in progress, pins, and recent activity, with search and readable message previews.
- Archives finished chats with Undo, an archive browser, and Restore. History, drafts, and attachments stay available.
- Streams Markdown, command output, file changes, plans, and generated images. Each fenced code or Markdown block has its own copy button.
- Renders Mermaid code blocks and pasted `flowchart` or `graph` diagrams in both providers. Use **Source** to read or copy the definition, scroll wide previews, or expand a diagram to see it at full size. Rendering works offline and follows the IDE theme. Incomplete or invalid diagrams keep their source visible.
- Supports file drops, pasted images, large text attachments, and native file selection. Attached images show a small thumbnail. Click one to preview it, then press Escape to return to the composer. Right-click a chat image, thumbnail, or expanded preview and choose **Copy image** to copy its full-size pixels to the system clipboard.
- Opens local file and image links in the IDE, including files inside WSL.
- Keeps workspace, permissions, model, reasoning effort, and IDE context in a compact composer. Secondary labels shorten in narrow panes to keep the controls on one row.
- Creates and shares Git worktrees across chat tabs, with branch labels, native diffs and terminals, and explicit cleanup.
- Handles permission approvals and both blocking and asynchronous questions.
- Lets you steer a running turn, interrupt it, and discuss selected code in a new tab.
- Edits any user message and continues the revised conversation in a new editor tab, keeping the original chat.
- Groups commands, searches, reasoning, and file edits between messages into compact rows. Expand a row to browse details in a scrollable area. Running and failed work stays visible in the summary, and generated images stay visible in the chat.

Codex uses one app-server process per project. Claude uses one process per open or active chat, with saved sessions for resume. Restored hidden tabs do not start browser renderers. History loads in pages, the transcript initially renders its latest 80 messages or activity groups, and streaming updates contain only changed items. Collapsed groups do not render their tool details.

## Install

Requires IntelliJ IDEA 2026.1 or newer, its bundled JetBrains Runtime with JCEF, and a current Codex CLI or Claude Code CLI. Native Codex integration has been tested with IDEA 2026.1.3 and Codex 0.153.0.

1. Download the plugin ZIP from [Releases](https://github.com/acorn1010/codex-idea-tabs/releases).
2. In IDEA, open **Settings → Plugins → gear menu → Install Plugin from Disk** and select the ZIP.
3. Restart IDEA when prompted.
4. Open **Tools → Codex Tabs → New Chat Tab**.

If Codex is not on the IDE's PATH, set its full executable path under **Settings → Tools → Codex Tabs**. The field accepts an executable path, not a command with arguments.

The plugin reads the available models from Codex. New workspaces start with **GPT-6-Astra / xhigh** when available. Model and reasoning choices are saved as soon as you select them, before sending a message, and return in new tabs and after an IDEA restart. These preferences are kept per workspace. Choosing **Codex default** is also remembered. Switching models keeps a supported reasoning level or uses the new model's default. Sign in through the plugin's ChatGPT sign-in button if the CLI has no account yet.

### Claude Code

Install Claude Code and sign in by running `claude` in a terminal. Set **Claude executable** in **Settings → Tools → Codex Tabs** if needed. Native and npm installations are detected on macOS. An npm installation also uses the Node binary beside its CLI entry point.

Use the **Codex / Claude** menu in the header to select a provider or switch an idle chat. Switching keeps the tab, checkout, draft, attachments, and visible history. The new provider starts a fresh session with prior conversation text as context. Tool output, image contents, and session approvals do not transfer. Very long conversations transfer recent text and show a notice. Messages from earlier providers remain readable and copyable but cannot be edited. Finish active work, answer pending requests, and send or remove queued messages before switching. A failed connection leaves the original chat in place.

Choose a model in the dropdown at the bottom right of the composer. Models come from the selected CLI, and each provider keeps its own model preference. The last provider selected is the default for new chats. Update Claude Code if a newer model is missing, then reconnect the chat.

Claude chats support streamed replies and tool activity, attachments, questions with multiple selections, stop, archive and restore, both Markdown copy modes, session history, and continuation in another worktree. Shared project guidance is supplied as additional system instructions when the CLI starts, with links to the original `.agents/skills` files. Native `CLAUDE.md` instructions remain active. The command picker includes commands reported by Claude and shared project skills.

Send a follow-up while Claude works to queue it for the next turn. Queued messages keep their selected model, effort, and permissions. Stop, a failed turn, or reconnect pauses the queue. Use **Send queued** to continue, or remove individual prompts. Editing an earlier message opens a new chat from the point before that message and leaves the source chat intact. This does not undo file changes.

Use **Resume** to browse Claude terminal sessions in the project and its known repositories and worktrees. The plugin reads Claude's saved main conversation branch, including attached images, and resumes it through the CLI. Session files and credentials stay in Claude's own store.

Claude's permission menu offers **Ask me**, **Accept edits**, **Plan mode**, and **Approve for me** when the selected model supports it. Existing Claude CLI permission rules still apply. Codex sandbox settings do not apply to Claude chats. Approval cards offer **Decline**, **Allow once**, **Allow for session**, and **Always allow**. When Claude suggests an allow rule, the card shows its scope. Session approval applies that rule to the running session. Always approval saves it through Claude to local project settings, where terminal Claude can also use it. If Claude supplies no suitable rule, approval falls back to the exact tool input in this checkout, saved by the plugin.

The status panel shows Claude's context breakdown, loaded instruction files, token usage, reported session cost, and rate-limit events. MCP status lists the CLI's configured servers. Effort, Fast mode, and automatic approval follow the model capabilities reported by Claude. Fast mode can incur additional usage charges. New controls were verified against Claude Code **2.1.281**. Older CLIs retain basic chats and show update guidance for unsupported controls. Codex goals and its startup prompt inspector remain Codex-specific.

If Claude cannot reply, check that `claude -p "Reply only READY"` works in a terminal. A revoked or expired Claude login needs a new sign-in. The plugin does not renew or copy credentials itself.

### Windows with WSL

For projects opened through `\\wsl.localhost\Ubuntu\…` or `\\wsl$\Ubuntu\…`, the plugin detects the distribution and sends Linux paths to Codex. You can also set a distribution and Linux working directory in the connection settings when the project is opened another way.

Set the executable to a Linux Codex binary when using WSL. For example, `/home/yourname/.local/bin/codex`. Native Windows IDEA launches it through `wsl.exe`. Images and attachments travel through the app-server file API, and file links are translated back to paths IDEA can open.

For native Windows projects without WSL, use the Windows Codex executable. For Linux or macOS IDEA, use the local Codex executable.

The **Inspect context** button in the chat header opens a searchable view of recorded rules, skills, messages, and tool results, with size estimates and repeated-passage checks. A separate Startup view builds fresh CLI input on request. [Context inspector guide](docs/context-inspector.md) explains the sources, exports, and limits.

## Worktrees

Click the branch label beside the attachment button to select a checkout or create a worktree. Choose a name and starting branch or commit. **Include current local changes** copies tracked edits and untracked files without changing the original checkout. Copied edits are unstaged. Ignored files, dependencies, and environment setup stay in the original checkout.

New worktrees use a `codex/<name>` branch in a sibling directory, `<repository>.worktrees/<name>`. Git runs in the same local or WSL environment as Codex. Several chats can share a checkout. New chats inherit the active chat's worktree, the sidebar's selected worktree, or the checkout of the selected code file. Otherwise they use the current project.

An unsent chat can change checkout in place. For an established conversation, **Continue in new worktree** or selecting another checkout opens a separate chat tab and keeps the original. Edited messages also keep their source worktree. File links, automatic IDE context, thread history, and the writable sandbox use the chat's checkout. Automatic IDE context only includes files and selections from that checkout. Explicitly attached files remain attached.

The workspace menu offers **Review**, **Terminal**, and **IDEA**. Review compares changes with the worktree's starting commit, including local edits and untracked files. For an existing external worktree, it uses the common commit with the primary checkout. Large and binary files have a placeholder in the review. Open the checkout in IDEA for those files. Terminal opens at the checkout directory. On Windows with WSL, it loads your Bash settings before selecting that directory. IDEA opens the checkout as a separate project. The sidebar's branch button filters workspaces while keeping questions and active work first.

Closing or archiving a chat does not remove its worktree. Use the trash button in the workspace menu for explicit removal. Removal refuses primary checkouts, open IDEA projects, active chats, pending questions, locked worktrees, and detached commits that have not reached the primary checkout. Clean worktrees on named branches can be removed even after a squash merge or before a merge, because their branches, commits, and chat history are kept. Changed, untracked, and ignored files are listed before **Discard changes and remove**. This permanently deletes local files without making a backup. **Cancel** leaves the worktree intact. A removed or missing checkout remains readable in chat, but needs another checkout before sending. Changes made by other IDE processes or command-line tools should be finished before cleanup.

If local changes cannot all be copied, the plugin keeps the new checkout and explains the problem. If the conversation cannot be forked, the original remains open and the new checkout stays available for retry. Automatic dependency setup and bringing changes back into the primary checkout are not included. Use the terminal or IDEA's Git tools for those steps.

Type `/` in the composer for [chat commands and skills](docs/slash-commands.md), including `/status` for a compact view of context and account limits.

## Shared guidance for multiple repositories

Keep the IDEA project open at a folder that contains your repositories. By default, Codex Tabs detects that folder's `AGENTS.override.md` or `AGENTS.md` and `.agents/skills`. It supplies this shared guidance to chats in child repositories and worktrees, including worktrees outside the project folder.

Use **Settings → Tools → Codex Tabs → Shared guidance folder** to select another source folder. Leave the field empty for automatic detection, or clear **Use shared project guidance** to disable it. The workspace menu shows the active shared folder and opens its settings. Applying settings reconnects chats.

Shared instructions are refreshed before idle turns and when chats start, resume, fork, or continue from an edited message. The selected checkout remains the working directory and writable sandbox. Its own instructions remain active. More specific checkout instructions override shared defaults unless the user specifies another precedence rule. Shared skills are registered with the project's Codex process, without changing global skill settings.

Files stay in their original locations. Relative links in shared instructions resolve from the shared folder, and links inside skills resolve from each skill's original directory. This also supports resources outside `.agents`, such as a sibling repository of shared skills. Nothing is copied into your repositories or worktrees. The **IDEA** workspace action carries the resolved shared folder into a new project's settings, while keeping an existing project's saved preferences.

Shared instructions are added as user context, without replacing built-in or developer instructions.

This requires a Codex version that supports `skills/extraRoots/set` and `thread/inject_items`. If shared skills cannot be registered, the plugin reports the failure instead of silently starting without them. Update Codex or turn off shared guidance to continue.

## Daily use

| Action | How |
| --- | --- |
| New chat | `Ctrl+Alt+N` or the Codex sidebar's **New chat** button |
| New chat beside this one | **Tools → Codex Tabs → New Chat to Side** |
| Find a chat | `Ctrl+K` inside a chat |
| Copy the whole chat | Chat header’s **Copy chat as Markdown** icon opens a menu. **Copy conversation** is first and copies only user messages and Codex replies. **Copy full chat** also includes thinking and tool activity. Both include older messages and work for archived chats |
| Go to a chat that needs input | `Ctrl+Alt+A` or the sidebar's **Needs you** filter |
| Add selected code | Select code, then **Codex: Discuss Selected Code** in the editor context menu |
| Send a message | `Enter` |
| Steer the running turn immediately | `Ctrl+Enter` (`⌘Enter` on macOS) in the composer |
| Edit a sent message | Hover the message, choose the pencil, then **Edit and resend** or `Ctrl+Enter` (`⌘Enter` on macOS) |
| Recall a previous message | `↑` in an empty composer, then keep pressing `↑` for older messages. Any other key ends recall |
| Add a line | `Shift+Enter` |
| Add files or images | Drop, paste, or use the plus button |
| Change permissions | Open **Approve for me**, **Ask me**, or **Read only** in the composer |
| Archive a finished chat | Hover its sidebar row and click the archive icon, or select the row and press `Delete` |
| Browse archived chats | **Archived** at the bottom of the sidebar |
| Restore a chat | Hover its archived row and click Restore, or open it and use **Restore chat** |
| More chat actions | Row's **…** button, right-click, or `Shift+F10` |

IDEA's keymap settings can change the native shortcuts. Its normal editor split actions support a four-pane layout or any other arrangement.

The sidebar has one primary **New chat** action. Questions and active work appear above pinned and recent chats. Hover a row for archive and more actions. The **…** menu also offers rename, pin, and open to the side.

Archiving closes that chat's editor tabs and moves its Codex history into the archive. It does not delete messages, files, or a saved draft. **Undo** restores the last archived chat. You can read archived conversations without resuming them. Restore a chat before sending a new message. Chats with active work or unanswered requests cannot be archived.

A failed chat load can retry on the same connection. A successful load or renewed streaming clears its temporary connection error. A failed send or failed turn remains visible until addressed. Reconnect restores all open chats.

Thinking rows expand when Codex provides a readable reasoning summary. The plugin requests detailed summaries for new turns, including resumed chats. Some short steps still have no summary, and old empty blocks cannot be filled in afterward. Empty rows show activity without an expand button.

Questions stay above the composer until answered or dismissed. An asynchronous question can remain after the turn finishes. A blocking approval belongs to its live server connection, so it is cleared after a disconnect.

Drop files from Windows Explorer or IDEA’s project tree into the chat input to attach them, including PDFs. The plugin copies each file into Codex’s attachment storage, including when Codex runs in WSL. Files can be up to 50 MB each. Use the **×** on an attachment to remove it before sending. Sending waits until uploads finish. If one file fails, the other attachments and your draft stay in place.

Editing opens an inline text field with removable image thumbnails. Use its **+** button to choose images, or paste or drop images into that editor. Click a thumbnail to preview it and use its **×** button to remove it from the revision. **Cancel** or `Escape` keeps the original text and images. **Edit and resend** opens a new chat using history before the edited turn and sends the revised image selection. Other attached context and earlier images remain available. The original chat, its draft, and any active work stay intact. This also works when reading an archived chat. Existing file changes are kept, so editing a message does not undo work on disk.

Codex branches at turn boundaries. When you edit a follow-up sent during a turn, earlier user inputs from that same turn are resent together with the edited message. Assistant responses and tool output from that turn are generated again. Later messages are excluded. If sending fails, the new tab keeps the revised text and attachments ready to retry.

## Data and execution

Codex owns authentication, thread history, tools, sandboxing, and model execution. The plugin stores its local drafts, recent transcript data, and pending asynchronous questions under the IDE configuration directory's `codex-tabs` folder. It writes attachment files under the execution environment's `~/.codex/codex-idea-tabs/attachments` directory.

**Approve for me** uses Codex's automatic approval review with a workspace-write sandbox. **Ask me** routes approval requests to you. **Read only** uses a read-only sandbox. None of these modes enables unrestricted execution.

Model, reasoning, and permission changes apply to the next turn. A follow-up sent while Codex is working steers the existing turn with its current settings.

The web UI is bundled in the plugin. It has no development server or remote CDN dependency. Markdown raw HTML is disabled, and local images are read through the native bridge.

Closing the last editor for an idle chat sends `thread/unsubscribe`. A closed chat with active work or a blocking request stays subscribed until that work finishes. Reopening it resumes the thread. Codex owns the final memory cleanup: its documented grace period is 30 minutes without subscribers or thread activity, so process memory may not fall as soon as a tab closes. Closing the project stops its app-server process. Reopening a project restores its chats and drafts, but does not restart interrupted work without a new message.

## Build and test

Use JDK 21 and Node.js 22. Install the UI dependencies first:

```sh
cd web
npm ci
npm test
cd ..
./gradlew :core:test buildPlugin verifyPluginStructure
```

The ZIP is written to `build/distributions`. Gradle downloads IDEA's SDK unless `-PlocalIde=/path/to/idea` points to an existing installation. Use an SDK on the same filesystem as the build for faster incremental compilation. The portable `core` tests can run in WSL while compiling the plugin against a Windows IDEA SDK.

For an opt-in integration test using your existing Codex account:

```sh
CODEX_TEST_BINARY=/full/path/to/codex ./gradlew :core:test --rerun-tasks
```

This sends five tiny read-only turns. Two ephemeral chats check ChatGPT authentication, image reads, streaming, and separation between conversations. A separate source chat and edited branch check that earlier context is kept and the original history stays unchanged. Those two saved test chats are archived afterward. The tests use a small amount of your subscription allowance.

The [native smoke-test instructions](scripts/native-smoke/README.md) cover the real IDEA editor bridge, four-pane restoration, attachments, and menu interactions. The fixture does not contact a model. Test tooling is not included in the release plugin.

## Scope

This release focuses on concurrent local work in IDEA, including shared Git worktrees. It does not include cloud tasks, voice mode, or every experimental Codex app-server extension. Unknown server requests receive an explicit unsupported response. Remote HTTP images are opened as links rather than fetched into the local preview.

The design takes ideas from [T3 Code](https://github.com/pingdotgg/t3code), the Codex editor extension, and a workflow with several chats visible at once. This repository is an original implementation, not a repackaged OpenAI extension or a fork of T3 Code's server.

## License

The original plugin code is [MIT licensed](LICENSE). Bundled dependencies keep their own licenses. The packaged web resources include `THIRD_PARTY_LICENSES.txt`. Codex, IntelliJ IDEA, and their services are separate products with their own terms. This project is not affiliated with OpenAI or JetBrains.

### Projects with several repositories

You can open a parent folder in IDEA and keep independent Git repositories below it. Use **Workspace → Choose repository** to select the repository for a chat. The picker shows each repository's own branches and worktrees. A new chat opened from a source file uses its nearest Git repository. An existing chat keeps its checkout when you browse other files.

Selecting another checkout for an established chat opens a new chat tab and carries the draft with it. The original chat stays in its original checkout. Worktrees use the selected repository's existing layout: `<primary-checkout>.worktrees/<name>`. A worktree of a parent repository does not include independent nested repositories.

Discovery checks the project folder and nested repository folders, skips dependency and generated folders, and does not follow directory symlinks. For a deeply nested repository or one outside the project folder, open a file there and create a chat to add it. Repositories are grouped by their primary checkout, so linked worktrees are not listed as separate repositories.

Codex loads the selected checkout's own instructions and skills. Codex Tabs also supplies the IDEA project's shared guidance by default. See [Shared guidance for multiple repositories](#shared-guidance-for-multiple-repositories) to choose another source or disable it. Keep repository-specific guidance in that repository and commit it if new worktrees need it. The extension does not copy or rewrite instruction files. The context inspector's Recorded view includes shared guidance, while its separate CLI Startup preview does not include plugin-added context.

### Remembering approvals

Approval cards show **Always allow** when Codex offers a saved command-prefix or network-host rule. The card shows the rule before you select it. **Allow for session** remembers a command or file approval, or keeps the requested permissions for later turns in that session. **Allow once** and **Decline** remain separate choices. Computer Use, Browser Use, and other MCP connectors also get **Allow for session** and **Always allow** when they offer those durations. The selected duration is sent back to that connector with your form answers. Available buttons follow the choices supported by the current Codex request.
