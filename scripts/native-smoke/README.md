# Native smoke test

This harness opens four real IDEA editor panes against `scripts/fake-codex.py`. It never calls a model. Install it only into a separate IDE test profile.

1. Build the main plugin and harness with `./gradlew buildPlugin nativeSmokePlugin`.
2. Unpack the main plugin ZIP into the test profile's plugin directory.
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

Also check the native sidebar with the pointer. Rows and toolbar buttons must have distinct hover and pressed states. Clicking a row once should open it. The context menu can rename, pin, or hide an idle chat.

Close and restart the test profile normally. Verify that the native split layout, draft, attachment, and image preview return. Inspect the IDEA log for errors naming this plugin. The opt-in real subscription test is separate, under `core/src/test`.

Do not install the smoke-test plugin in your daily IDE profile or include it in a release.
