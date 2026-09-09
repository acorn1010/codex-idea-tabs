# Chat commands and skills

Type `/` at the start of the composer to show commands and available skills. Keep typing to filter. Use Up and Down to choose, then Enter or Tab to select. Clicking a row also selects it. Escape closes the menu and keeps the text.

The compact menu shows each action's icon, description, and current setting. Skills appear below the actions with their description and scope. Personal skills and skills from the chat's project come from Codex's `skills/list` response. Disabled skills are hidden. A failed read has a Retry action.

Selecting a skill adds it to the draft. Add your task, then send. The request includes the skill's name and path as an explicit skill input. Selected skills survive tab restoration and can be removed before sending.

| Command | Action |
| --- | --- |
| `/status` | Show a small panel above the composer with thread ID, context remaining, quota remaining, and reset times. |
| `/review` | Choose uncommitted changes or a base branch, then start a review in this chat. |
| `/fast` | Toggle faster responses when the selected model advertises support. |
| `/feedback` | Write feedback, then send it to OpenAI with the chat ID. Logs are excluded. |
| `/goal` | Set an objective with an optional token budget, or clear the existing goal. |
| `/ide-context` | Toggle the open-file and selection context attached to messages. |
| `/init` | Prepare a request to create or update AGENTS.md. Review the draft before sending. |
| `/mcp` | Show connected MCP server status and tool counts. |
| `/memories` | Open the Context inspector filtered for memory instructions. |
| `/model` | Open the model picker. |
| `/plan` | Toggle Plan mode for subsequent messages. |
| `/reasoning` | Open the reasoning effort picker. |
| `/context` | Open the full Context inspector. |
| `/permissions` | Open the permission picker. |
| `/new` | Open a new chat tab in the same workspace. |
| `/resume` | Find an earlier conversation. |
| `/settings` | Open connection settings. |
| `/stop` | Stop the current turn. Listed only while Codex is working. |

Opening a panel, changing a setting, or selecting a skill does not send a model message. Review, goal, and feedback forms act only when submitted. Fast and Plan preferences are remembered. Changes made during a running turn apply to the next turn. Ctrl+Enter still steers ordinary messages immediately.

Only a single command token is handled. Paths such as `/home/acorn/project`, text with arguments, and multiline messages remain ordinary text. Shift+Enter adds a newline. An unknown single command stays in the composer instead of being sent by mistake.

The status panel uses the latest token counts reported for this chat. It fetches account limits when opened, preferring named rate-limit buckets over the duplicate legacy bucket. Both percentages show how much remains. Missing data is shown as unavailable. Refresh or Retry reads limits again without reconnecting or sending a model message. Typing remains available while the panel is open.

The picker includes actions implemented by this plugin. It does not claim to run every command from the Codex terminal UI. Memories searches the recorded context and does not edit memory files.
