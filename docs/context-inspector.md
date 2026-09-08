# Context inspector

Use **Inspect context**, the layers button beside connection settings in a chat header. The inspector stays inside that chat tab. In narrow panes, selecting a block opens its details in the same space. Wide panes show the list and details beside each other. Escape returns to the chat and keeps its draft.

## Recorded content

This view reads the session file reported by Codex. It uses the latest recorded compaction replacement and later items. It excludes duplicate display events in the log, so the same message is not counted twice just because Codex saved an event and a response item.

Blocks include recorded instructions, the skill catalog, messages, tool calls and results, visible summaries, and image references. Base instructions from thread creation are labeled with that origin. A later runtime change can make those instructions differ from the ones used in the latest request.

If the session file is unavailable, the inspector shows only the transcript already loaded in the tab and states that limit. It never resumes a closed thread or sends a model turn to inspect history.

## Find large or repeated content

- **Largest first** puts the largest text blocks at the top. **Recorded order** restores their sequence.
- Search checks block text, source labels, and related tool calls. Matches are highlighted in the selected block.
- The repeated-passage button filters to blocks containing exact repeated paragraphs of at least 120 characters. Those paragraphs are highlighted. Internal whitespace is preserved when comparing code.
- Counts cover the displayed text. Token estimates use characters divided by four. They exclude image tokens, hidden content, and tool definitions, and are not billing totals or proof that all displayed text was sent together.
- **Paths mentioned** links to files referenced by a block or its related call. A mention alone does not prove that a file was read. A skill catalog entry means the skill was available, not that its full instructions were loaded.

Repetition can be useful. Review the text and its role before changing any rule or skill. The inspector does not remove content or alter a chat.

## Startup input

Open **Startup**, then **Build startup snapshot**. This runs the configured Codex executable's `debug prompt-input` command in the chat workspace, using the currently selected model and reasoning setting. On Windows with WSL, the command runs in the configured distribution.

This creates a fresh CLI startup snapshot. It excludes the existing conversation, draft, and IDE context. Other CLI defaults can differ from the plugin's active turn settings. Runtime reads and implicit skill use that happen later will not appear in this initial snapshot.

Startup snapshots are generated only when requested. Opening or refreshing Recorded does not launch the startup debugger. Startup failures stay in the inspector and can be retried without reconnecting the chat.

## Export and limits

Use **Copy context JSON** to copy a report with its source and scope labels. **Open context as text** opens a readable snapshot in an IDEA text editor. Each block also has a copy button.

Inspection is local and on demand. It does not add anything to the model context. Reports are held by the inspector while it is open. Opening it again reads a new snapshot.

The reader scans at most the last 64 MB of a session file, stops at the file size seen when scanning starts, and displays at most 2,000 blocks and 2 million text characters. Individual blocks are limited to 100,000 characters. Any shortening or missing history is shown in the scope details. Large lists initially render 80 rows. Encrypted reasoning and image bytes are omitted.

Codex does not currently expose a complete per-request input capture through this plugin's app-server API. Recorded is a reconstruction, and Startup is a new CLI snapshot. Neither should be treated as an exact view of a running chat's complete model input.
