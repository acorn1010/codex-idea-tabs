# Codex Tabs for IntelliJ IDEA

Independent Codex chats in native editor tabs. Keep several tasks beside your code, split them into groups, and see which conversations need your attention.

This is an independent client for the [Codex app server](https://developers.openai.com/codex/app-server/). It uses the Codex CLI's existing ChatGPT sign-in. The plugin does not ask for an API key, copy credentials, or run its own cloud service.

## What it does

- Opens each conversation in an ordinary IDEA editor tab. Tabs can be split, moved, pinned, closed, and restored by the IDE.
- Uses blue tab icons for work in progress, amber for questions or approvals, and green for unread results.
- Keeps sessions and pending questions alive when their tabs are hidden or closed.
- Groups chats by attention, work in progress, pins, and recent activity, with search and readable message previews.
- Archives finished chats with Undo, an archive browser, and Restore. History, drafts, and attachments stay available.
- Streams Markdown, command output, file changes, plans, and generated images.
- Supports file drops, pasted images, large text attachments, and native file selection.
- Opens local file and image links in the IDE, including files inside WSL.
- Keeps workspace, permissions, model, reasoning effort, and IDE context in a compact composer. Controls wrap in narrow panes.
- Handles permission approvals and both blocking and asynchronous questions.
- Lets you steer a running turn, interrupt it, and discuss selected code in a new tab.

There is one app-server process per project. Restored hidden tabs do not start browser renderers. History loads in pages, the transcript initially renders its latest 80 items, and streaming updates contain only changed items.

## Install

Requires IntelliJ IDEA 2026.1 or newer, its bundled JetBrains Runtime with JCEF, and a current Codex CLI. Version 0.1.0 was tested with IDEA 2026.1.3 and Codex 0.153.0.

1. Download the plugin ZIP from [Releases](https://github.com/acorn1010/codex-idea-tabs/releases).
2. In IDEA, open **Settings → Plugins → gear menu → Install Plugin from Disk** and select the ZIP.
3. Restart IDEA when prompted.
4. Open **Tools → Codex Tabs → New Chat Tab**.

If Codex is not on the IDE's PATH, set its full executable path under **Settings → Tools → Codex Tabs**. The field accepts an executable path, not a command with arguments.

The plugin reads the available models from Codex. An empty model choice uses your Codex default. Sign in through the plugin's ChatGPT sign-in button if the CLI has no account yet.

### Windows with WSL

For projects opened through `\\wsl.localhost\Ubuntu\…` or `\\wsl$\Ubuntu\…`, the plugin detects the distribution and sends Linux paths to Codex. You can also set a distribution and Linux working directory in the connection settings when the project is opened another way.

Set the executable to a Linux Codex binary when using WSL. For example, `/home/yourname/.local/bin/codex`. Native Windows IDEA launches it through `wsl.exe`. Images and attachments travel through the app-server file API, and file links are translated back to paths IDEA can open.

For native Windows projects without WSL, use the Windows Codex executable. For Linux or macOS IDEA, use the local Codex executable.

## Daily use

| Action | How |
| --- | --- |
| New chat | `Ctrl+Alt+N` or the Codex sidebar's **New chat** button |
| New chat beside this one | Chat header's split button or **Tools → Codex Tabs → New Chat to Side** |
| Find a chat | `Ctrl+K` inside a chat |
| Go to a chat that needs input | `Ctrl+Alt+A` or the sidebar's **Needs you** filter |
| Add selected code | Select code, then **Codex: Discuss Selected Code** in the editor context menu |
| Send a message | `Enter` |
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

Questions stay above the composer until answered or dismissed. An asynchronous question can remain after the turn finishes. A blocking approval belongs to its live server connection, so it is cleared after a disconnect.

## Data and execution

Codex owns authentication, thread history, tools, sandboxing, and model execution. The plugin stores its local drafts, recent transcript data, and pending asynchronous questions under the IDE configuration directory's `codex-tabs` folder. It writes attachment files under the execution environment's `~/.codex/codex-idea-tabs/attachments` directory.

**Approve for me** uses Codex's automatic approval review with a workspace-write sandbox. **Ask me** routes approval requests to you. **Read only** uses a read-only sandbox. None of these modes enables unrestricted execution.

Model, reasoning, and permission changes apply to the next turn. A follow-up sent while Codex is working steers the existing turn with its current settings.

The web UI is bundled in the plugin. It has no development server or remote CDN dependency. Markdown raw HTML is disabled, and local images are read through the native bridge.

Closing a chat tab leaves the project-owned session running. Closing the project stops its app-server process. Reopening a project restores its chats and drafts, but does not restart interrupted work without a new message.

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

This sends two tiny read-only turns in temporary, ephemeral threads. It checks ChatGPT authentication, image reads, streaming, and separation between conversations. It uses a small amount of your subscription allowance.

The [native smoke-test instructions](scripts/native-smoke/README.md) cover the real IDEA editor bridge, four-pane restoration, attachments, and menu interactions. The fixture does not contact a model. Test tooling is not included in the release plugin.

## Scope

The first release focuses on concurrent local work in IDEA. It does not implement T3 Code's worktree management, cloud tasks, voice mode, or every experimental Codex app-server extension. Unknown server requests receive an explicit unsupported response. Remote HTTP images are opened as links rather than fetched into the local preview.

The design takes ideas from [T3 Code](https://github.com/pingdotgg/t3code), the Codex editor extension, and a workflow with several chats visible at once. This repository is an original implementation, not a repackaged OpenAI extension or a fork of T3 Code's server.

## License

The original plugin code is [MIT licensed](LICENSE). Bundled dependencies keep their own licenses. The packaged web resources include `THIRD_PARTY_LICENSES.txt`. Codex, IntelliJ IDEA, and their services are separate products with their own terms. This project is not affiliated with OpenAI or JetBrains.
