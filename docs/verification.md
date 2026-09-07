# Release 0.1.0 verification

Checked on September 7, 2026 with Windows IntelliJ IDEA 2026.1.3, a WSL Ubuntu backend, Codex 0.153.0, Java 21 for compilation, and Node.js 22.

| Check | Result |
| --- | --- |
| Core state and path tests | 9 passed |
| Web state and attachment tests | 2 passed |
| Plugin package structure | Passed |
| Dependency audit | No reported vulnerabilities |
| Two concurrent real Codex chats | Passed using ChatGPT sign-in and ephemeral read-only threads |
| Real message sent from the IDEA plugin | Expected response received in 4.5 seconds, with no error banner |
| Four native chat panes | Opened and restored by IDEA |
| Draft and attachment restoration | Passed after closing and reopening the test profile |
| Image preview | Decoded through the native file bridge |
| Large Unicode paste | Attached successfully |
| File drop | Attached successfully |
| Permission menu | Full label, selectable choices, distinct hover and pressed states |
| Stop control | At least 30 pixels across, with a filled square of at least 10 pixels |
| Native sidebar feedback | Different idle, hover, and pressed colors confirmed from window captures |
| Native IDE errors | No errors naming the plugin in the final real-backend test profile |

The UI tests use a separate IDEA profile and a deterministic fixture. They test the native editor and file bridge without model calls. The two real-backend checks separately verify authentication, streaming, and session isolation. The 4.5-second message time is one observed response, not a performance guarantee.

The production plugin ZIP excludes the smoke harness and fixture. Only the main plugin is installed in the daily IDEA profile. Restarting that profile is required before it can load the new plugin.
