# Native smoke test

This harness opens four real IDEA editor panes against `scripts/fake-codex.py`. It never calls a model. Install it only into a separate IDE test profile.

1. Build the main plugin and harness with `./gradlew buildPlugin nativeSmokePlugin`.
2. Unpack the main plugin ZIP into the test profile's plugin directory. Move any previous Codex Tabs folder outside that directory first so an old plugin JAR cannot be loaded.
3. Put `build/native-smoke/codex-tabs-native-smoke.jar` in a second plugin folder, under `smoke/lib`.
4. Make `scripts/fake-codex.py` executable in the backend environment.
5. Start IDEA with a temporary project and separate configuration, system, plugin, and log paths.

Set these VM properties in the test profile:

```text
-Didea.config.path=.../config
-Didea.system.path=.../system
-Didea.plugins.path=.../plugins
-Didea.log.path=.../logs
-Dcodex.smoke.binary=/path/to/repo/scripts/fake-codex.py
-Dcodex.smoke.cwd=/tmp/codex-idea-smoke-project
-Dide.browser.jcef.debug.port=9226
```

For Windows IDEA with a WSL backend, also set `-Dcodex.smoke.distro=Ubuntu`. The executable and working directory use Linux paths. Start the Windows IDE from a Windows working directory, not a WSL UNC directory.

The harness imports four named fixture conversations and opens a split layout if no editors were restored. It writes `smoke-ready.txt` or `smoke-error.txt` in the test log directory. On a second launch, IDEA should restore the existing panes without the harness creating them again.

Run the UI check with Node.js on the same host as IDEA:

```sh
node scripts/native-smoke/check-ui.mjs
```

It connects only to the test JCEF debugging port. It checks visible panes, the permission menu, hover and pressed styling, the Stop button, image loading, and large-paste and file-drop handlers. Results and screenshots go under `output/playwright`. It leaves a named draft and an attachment for the next restart check.

To check editing through the native bridge, run:

```sh
CODEX_SMOKE_CDP=http://127.0.0.1:9226 node scripts/native-smoke/check-edit.mjs
```

This checks editing old messages, follow-ups sent during a turn, and the first message. It also verifies separate editor tabs, retained context and images, unchanged original drafts, cancel and focus behavior, and retry after a failed send. The fixture returns one turn per history page to exercise pagination. Run the four-pane check first, since editing adds tabs to the test profile.

For responsive layout checks, build the UI and run:

```sh
CODEX_SMOKE_CHROME=/path/to/chrome node scripts/native-smoke/check-edit-layout.mjs
```

This opens the bundled UI in a real browser at 360, 520, and 720 pixels wide. It checks image previews, visible edit controls, keyboard submission, and draft recovery after an error. It uses a local fixture bridge and does not launch IDEA or call a model.

For Mermaid previews, build the UI and run `CODEX_SMOKE_CHROME=/path/to/chrome node scripts/native-smoke/check-mermaid.mjs`. It uses the same inline bundle and security policy as JCEF. It checks fenced and pasted flowcharts in both providers, light and dark themes, narrow layouts, source copy, keyboard access to expanded diagrams, streamed updates, invalid input, and offline rendering.

To verify model defaults and saving without sending a message, use the fixture's Astra and Test model choices:

```sh
CODEX_SMOKE_CDP=http://127.0.0.1:9226 node scripts/native-smoke/check-model-preferences.mjs
```

Start with no saved model preference in the isolated profile. The check verifies Astra with `xhigh`, immediate saving, inheritance by new tabs, supported reasoning levels, and an explicit **Codex default** choice. It leaves **Test model / high** selected. Close and restart that test profile, then run with `--restored` to verify IDEA saved the preference across the restart.

For command grouping, run `check-command-groups.mjs` with `CODEX_SMOKE_CHROME` set to a local browser. It checks compact summaries, bounded scrolling, single-command expansion, visible failures and images, keyboard controls, and streamed updates at 360, 520, and 900 pixels wide. Run `check-native-command-groups.mjs` against the isolated IDEA profile to check a 20-command batch received through the native app-server bridge. Neither check calls a model.

To check the real mouse cursor, add `-Dcodex.smoke.ui.probe=true` to the test profile and run `check-cursor.mjs` from that profile's directory. It moves the mouse over the composer, a button, the inline message editor, and chat search. Keep the mouse idle during this short check. The harness checks AWT cursor state and, on Windows, the cursor returned by `GetCursorInfo`. CSS alone cannot verify the cursor in remote JCEF.

With the same native probe enabled, run `check-steer-shortcut.mjs` from the test profile's directory. It presses Ctrl+Enter through the operating system and checks that the composer sends exactly one message through `turn/steer`, clears the draft, and keeps the existing turn active. Keep the mouse and keyboard idle during this check.

Set `-Dcodex.smoke.sidebar.check=true` to run the native sidebar checks. They click the attention filter, archive a fixture chat, undo that action, browse the archive, and open the archived chat for reading. They also check that active work cannot be archived. Results and hover and pressed captures go to the test log directory. This option requires the fake backend. It leaves an archived chat open so its read-only state and Restore button can be checked in JCEF.

Set `-Dcodex.smoke.sidebar.actions.check=true` to check long-title tooltips, both sidebar button targets at multiple widths and display scales, clicks during list refreshes, Undo, and protection when a different chat moves under a held click. This opens only the native sidebar with fresh fake chats. Results and captures use the `sidebar-actions-` prefix in the test log directory.

Also check the native sidebar at a narrow width. Rows and toolbar buttons must have distinct hover and pressed states. Clicking a row once should open it. The context menu can open to the side, rename, pin, or archive an idle chat.

Close and restart the test profile normally. Verify that the native split layout, draft, attachment, and image preview return. Inspect the IDEA log for errors naming this plugin. The opt-in real subscription test is separate, under `core/src/test`.

Do not install the smoke-test plugin in your daily IDE profile or include it in a release.

Set `-Dcodex.smoke.lifecycle.check=true` to check real editor disposal, idle unsubscribe, close and reopen, background completion, retry recovery, reconnecting multiple open tabs, and shortened tab titles. The harness uses fresh fixture IDs and writes `lifecycle-result.json`. Its subscription checks verify RPC receipt, not the real app server's memory cleanup delay. It leaves a chat with an image attached for preview checks.

Run `check-attachment-preview.mjs` with `CODEX_SMOKE_CHROME` set to a local Chrome executable to check thumbnails and image previews at 360, 520, and 900 pixels wide. It verifies keyboard focus, missing-image fallback, attachment removal, unchanged drafts, transcript previews, and error recovery. Results are written under `output/playwright`.

After the native lifecycle check, run `check-native-attachment-preview.mjs` against that profile to verify a WSL attachment thumbnail, enlarged preview, Escape and focus, and retained draft through the actual IDEA file bridge.

Run `check-message-recall.mjs` with `CODEX_SMOKE_CHROME` set to a local browser to check Up-arrow recall, older history pages, normal editing, cancellation of slow history loads, and Ctrl+Enter. With the native UI probe enabled, run `check-native-message-recall.mjs` against the isolated profile to press physical Up keys in IDEA. The native check recalls the review fixture's user messages and restores its saved draft without sending a message.

For images in the inline message editor, run `check-edit-images.mjs` with `CODEX_SMOKE_CHROME` set. It checks image selection, paste/drop, removal, previews, cancellation, empty-message protection, retries, and delayed-upload cancellation at 360, 520, and 900 pixels wide. Run `check-native-edit-images.mjs` against the isolated IDEA profile for actual WSL uploads, revised-image payloads, text-only replacement, preserved originals, and failed-send recovery. Both use fixtures and make no model calls.

For image copying, run `check-copy-image.mjs` with `CODEX_SMOKE_CHROME` set. It checks context menus, full-resolution PNG pixels, modal stacking, Escape, and clipboard-error retry. Run `check-native-copy-image.mjs` on Windows against the test IDEA profile to read back the actual clipboard image and compare dimensions and pixel colors.

Run `check-context-inspector.mjs` with `CODEX_SMOKE_CHROME` to verify on-demand loading, search, repeated-paragraph highlighting, exports, keyboard focus, and startup retry at 360, 520, and 900 pixels. The 500-block case checks bounded rendering. Run `check-native-context-inspector.mjs` against a separate IDEA test profile to verify real WSL session-file reads, compaction replacements, startup execution, clipboard, and text export. Both checks use fixtures and never send a model turn.

### Git worktrees

Run `check-worktrees.mjs` with the built webview to check the picker, create form, removal confirmation, keyboard focus, and narrow layouts at 360, 520, and 900 pixels. Removal checks cover the affected file list, ignored files, Cancel, explicit discard requests, and retry after a Git error. `GitWorktreesTest` verifies squash-merge cleanup, preserved branches, dirty and ignored-only worktrees, rename parsing, and protections that discard cannot bypass.

Run `check-archived-worktree.mjs` with `CODEX_SMOKE_CHROME` to check cleanup from the archived-chat footer. It covers other chats using the checkout, explicit discard, cancellation, retry, keyboard focus, and retained history and drafts. Primary and missing worktrees must not offer removal. All removal requests use fixtures.

For native checks, use a separate IDEA profile with `-Dcodex.smoke.worktrees.check=true` and a fixture working directory that is a Git repository on branch `main`. Commit `layout.txt` with `original layout`, then change it to `current local layout` with a trailing newline. Ignore the fake server's preview and JSON state files. Set the fake server's `CODEX_SMOKE_ROOT` to that same repository. The native harness creates a uniquely named linked checkout, copies the edit through WSL, forks a chat, sends to its sandbox, checks history scope, and refuses active or dirty cleanup. It writes `worktrees-result.json` in the profile's log directory.

Run `check-native-worktrees.mjs` against that profile's CDP port to check the actual picker and native diff and terminal actions. The native harness runs once per IDE process so opening a checkout as another IDEA project cannot start a second test. Use only disposable Git repositories. These checks never call a model.

### Restored history

Run `check-native-history-restore.mjs` in an isolated profile whose `review` fixture has 160 agent messages with IDs `restore-0` through `restore-159`. Use `Latest reply 159` as the final message text and enough text in earlier replies to require scrolling. The fake server must honor the requested page size and `excludeTurns`.

The first run checks that the newest 100 items are in order and the final reply is visible. It then loads earlier history and saves a draft. Close the test IDE normally, reopen the same profile, and run the script with `--restored`. It checks the latest reply, the saved draft, and all 160 items after loading earlier history. Results and screenshots go under `output/playwright`.

Run `check-slash-commands.mjs` for the command and skill picker at 360, 520, and 900 pixels. It checks keyboard selection, compact status, draft skill restoration, explicit skill inputs, action forms, retry, and ordinary sends. `check-native-slash-commands.mjs` checks the real JCEF bridge with the deterministic fixture, including account limits, skills, goals, and MCP status. Never run the native script against a real account backend.

Run `check-reasoning.mjs` with `CODEX_SMOKE_CHROME` to check readable thinking summaries, content arrays, empty states, and streamed text at 360, 520, and 900 pixels. It uses a headless browser with fixture data and does not start IDEA. Working-state recovery, delayed resume replies, late completed-turn events, and final reasoning text are covered by `ConversationActivityTest` and `ConversationReasoningTest`.

Run `check-file-drops.mjs` with `CODEX_SMOKE_CHROME` to check browser file bytes and the native drop bridge at 360, 520, and 900 pixels, including scaled coordinates. It covers PDFs, multiple files, removal, saved drafts, send blocking, partial failures, retry, inline image edits, and archived chats. The host file reader has core tests for unchanged PDF bytes, image restrictions, missing files, directories, and the 50 MB limit. These headless checks do not perform a Windows Explorer or IDEA project-tree drag. For that check, drop a local PDF from each source into an already running IDEA, confirm one attachment, send it, and confirm the source file remains in place. Do not start another IDEA process for this check.

## Native process pipes

`NativeProcessTransportTest` runs automatically with `:core:test`. It opens three real fixture subprocesses per provider in a JVM with two virtual-thread workers, then checks that background tasks and CLI requests still finish. This catches idle pipe readers blocking the shared scheduler, which in-memory stream fixtures cannot reproduce. The fixture never calls a model.

## Multiple repositories

`check-repositories.mjs` checks the repository search, branch choices, draft retention, and project-folder option at 360, 520, and 900 pixels. Run it with `CODEX_SMOKE_CHROME` set to the browser executable after building the web UI. `check-worktrees.mjs` also checks that the original worktree controls still work.

For native coverage, prepare a disposable parent Git repository with `outer.txt` on `main`, a `client` repository with `client.txt` on `client-main`, and a `server` repository with `server.txt` on `server-main`. Commit `original\n` as the content of each file. Then set the client file to `local client changes\n`. Ignore `client/`, `server/`, and `*.worktrees/` in the outer repository.

Launch the isolated profile with `-Dcodex.smoke.repositories.check=true`, the fake backend, and the parent directory as `codex.smoke.cwd`. The harness writes `logs/repositories-result.json` after checking file selection, branch isolation, creation, review, removal, drafts, sandbox paths, history, and saved chat state. Run `check-native-repositories.mjs` from that profile directory with `CODEX_SMOKE_CDP` pointing to its JCEF port to check the picker and new native tab.

## New chat focus

Enable `-Dcodex.smoke.focus.check=true` in the disposable profile, then run `check-native-focus.mjs` from that profile directory. Keep the test IDEA window active. When running outside the profile directory, set `CODEX_SMOKE_FOCUS_STATE` to its `logs/focus-state.json` path. It verifies DOM focus and the native keyboard focus owner, types without selecting the input, and checks ordinary and split tabs. State updates must preserve focus in an open workspace menu. No model messages are sent.

## Approval choices

Run `./gradlew :core:test buildPlugin`, then `check-approvals.mjs` with `CODEX_SMOKE_CHROME` set. The browser check uses approval choices written by the Java tests to `core/build/approval-fixtures.json`. It checks saved command rules, session grants, once and decline responses, retries, draft retention, and visible actions at 360, 520, and 900 pixels. The Java tests reject changed command prefixes, changed network hosts, and decisions excluded by the server. All requests are fixtures and no real permissions are granted.

The approval fixtures also cover Computer Use app access, Browser Use scalar persistence metadata, session-only connectors, missing durations, form answers, and cancellation. The Java response tests verify `_meta.persist` and reject a duration the connector did not offer. They also cover standard and OpenAI form modes without treating ordinary questions or device verification as saved grants.

Shared guidance checks live in `SharedGuidanceTest` and `SharedGuidanceLiveTest`. The opt-in live test uses `CODEX_TEST_BINARY` and three small read-only turns to verify shared skill discovery, local instruction priority, changed instructions after resume, and a fork into an external Git worktree. Its temporary test chats are archived afterward. `check-repositories.mjs` also checks the shared guidance source and settings action at 360, 520, and 900 pixels.

## Text navigation

Run `check-text-navigation.mjs` with `CODEX_SMOKE_CHROME` after building the UI. It checks word and line movement, Shift selection, selection direction, composition, inline editing, and unchanged drafts at 360 and 900 pixels.

For native action and bridge coverage, use a disposable IDEA profile with `-Dcodex.smoke.ui.probe=true`. Copy the custom test keymap into that profile: Command+Left/Right for previous/next word, Home/End for line start/end, and Shift variants for selection. Run `check-native-text-navigation.mjs` with `CODEX_SMOKE_CDP` and `CODEX_SMOKE_PROBE_ROOT` pointing to the test profile. The check finds actions by their registered shortcuts and invokes them through IDEA into real JCEF. It does not inject physical keys or verify macOS focus and event delivery. Check those manually after installation by typing a draft and using the shortcuts. No model messages are sent.

Run `check-copy-chat.mjs` with `CODEX_SMOKE_CHROME` after building the web UI. It checks the copy menu, conversation-only default, full-history Markdown option, keyboard navigation and dismissal, active and archived chats, progress feedback, failure and retry, and unchanged drafts at 360 and 900 pixels. Clipboard writes use a fixture. `ChatTranscriptTest` checks server pagination, duplicate items, live output, and failed history reads.

Run `check-providers.mjs` with `CODEX_SMOKE_CHROME` after building the UI. It checks new-chat provider selection, retained drafts, separate models and permissions, hidden Codex-only controls, Markdown speaker names, and narrow layouts. It uses a fixture and starts neither CLI.

`ClaudeClientTest` and `ClaudeProtocolTest` cover transport responses, disconnects, partial messages, tool results, approvals, saved provider identity, and WSL argument boundaries. Set `CLAUDE_TEST_BINARY` to a signed-in Claude executable to run `LiveClaudeTest`. It sends tiny tool-free prompts in temporary folders and checks streaming, resume, and a fork into another folder. It leaves isolated test sessions in Claude's own history.

Run `./gradlew claudeSessionsSmoke` to exercise the actual Claude session controller against `fake-claude.py`. This checks queued turn order and settings, stop, reconnect, native approval rules, multiple answers, context, MCP status, command discovery, and reopening an edited conversation without reapplying its original cutoff. It uses an isolated Claude config folder and no network, model, credentials, or IDE window.

`ClaudeFeaturesTest` covers saved branch selection, incomplete history lines, images, edit cutoffs, queue persistence, capability mapping, and native approval scope. `check-providers.mjs` also checks the modern Claude controls, terminal history picker, queued prompts, and multiple-answer questions at 360 and 900 pixels. It checks `/status` limit bars in both themes, refresh, zero and exhausted quotas, and unavailable data.

For switching existing chats between providers, run `CODEX_SMOKE_CHROME=/path/to/chrome node scripts/native-smoke/check-provider-switch.mjs`. It checks preserved history, drafts and files, failure recovery, blocked switches during work or approvals, repeated switches, and Opus 5.5 model and effort selection. `./gradlew claudeSessionsSmoke` also verifies context transfer and fresh Claude session IDs through the subprocess bridge.

Set `-Dcodex.smoke.providerPreference.check=true` in the isolated IDEA profile to check that the saved provider applies to plain new tabs, new or split chats from an older tab, and file-context chats. It also checks selecting the active provider, permission choices for each provider before sending, invalid permission modes, and reloading settings through IDEA's XML serializer. Results go to `provider-preference-result.json`. Neither CLI is started.

Set `-Dcodex.smoke.providerRecovery.check=true` in a disposable IDEA profile to test recovery after the Claude subprocess exits. Point `codex.smoke.binary` at `scripts/fake-codex.py` and `codex.smoke.claudeBinary` at `scripts/native-smoke/fake-claude.py`. Set `codex.smoke.cwd`, `CLAUDE_CONFIG_DIR`, and `CODEX_SMOKE_ROOT` to separate temporary directories. The check stops only its fixture process, reconnects the same chat, switches to Codex while Claude is disconnected, and verifies the draft survives. Results go to `provider-recovery-result.json`. `check-provider-switch.mjs` also checks the disconnected banner, Reconnect action, and provider menu at 360 and 900 pixels.

Run `check-submit-recovery.mjs` with `CODEX_SMOKE_CHROME` after building the UI. It checks pending-send feedback, failed sends, native bridge errors, timeout recovery, late replies, draft and attachment retention, and an explicit retry for both providers at 360 and 900 pixels. No real messages are sent. The native provider recovery check also stalls Claude's model setup, verifies that no user message reaches the fixture, and retries exactly once after reconnecting.

## Performance

Run `profile-plugin.py --plugin <plugin.zip> --label <name>` after building `nativeSmokePlugin`. It measures a fixed large chat library in a disposable IDEA profile and records CPU time, allocations, elapsed time, output hashes, and JFR samples. Use `--restore <profile-path>` to validate complete history and draft restoration after restarting that profile. See [the measured results](performance-2026-10-01.md) for scope and limitations.

`check-native-refresh.mjs` checks 120 draft changes across four fixture panes. It waits for the latest state and rejects revisions arriving out of order. If the isolated IDEA profile displays its first-run tour, disable `ide.experimental.ui.onboarding` in that test profile.
