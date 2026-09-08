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

To verify model defaults and saving without sending a message, use the fixture's Astra and Test model choices:

```sh
CODEX_SMOKE_CDP=http://127.0.0.1:9226 node scripts/native-smoke/check-model-preferences.mjs
```

Start with no saved model preference in the isolated profile. The check verifies Astra with `xhigh`, immediate saving, inheritance by new tabs, supported reasoning levels, and an explicit **Codex default** choice. It leaves **Test model / high** selected. Close and restart that test profile, then run with `--restored` to verify IDEA saved the preference across the restart.

To check the real mouse cursor, add `-Dcodex.smoke.ui.probe=true` to the test profile and run `check-cursor.mjs` from that profile's directory. It moves the mouse over the composer, a button, the inline message editor, and chat search. Keep the mouse idle during this short check. The harness checks AWT cursor state and, on Windows, the cursor returned by `GetCursorInfo`. CSS alone cannot verify the cursor in remote JCEF.

Set `-Dcodex.smoke.sidebar.check=true` to run the native sidebar checks. They click the attention filter, archive a fixture chat, undo that action, browse the archive, and open the archived chat for reading. They also check that active work cannot be archived. Results and hover and pressed captures go to the test log directory. This option requires the fake backend. It leaves an archived chat open so its read-only state and Restore button can be checked in JCEF.

Also check the native sidebar at a narrow width. Rows and toolbar buttons must have distinct hover and pressed states. Clicking a row once should open it. The context menu can open to the side, rename, pin, or archive an idle chat.

Close and restart the test profile normally. Verify that the native split layout, draft, attachment, and image preview return. Inspect the IDEA log for errors naming this plugin. The opt-in real subscription test is separate, under `core/src/test`.

Do not install the smoke-test plugin in your daily IDE profile or include it in a release.
