# Codex Tabs performance check, 2026-10-01

The live IDE recorded a 35.8-second UI freeze in `SessionWindow.Sessions.render`, through `CodexService.summaries` and `GitWorktrees.normalized`. A 45-second Java Flight Recorder profile also showed path normalization, conversation copying, and complete-cache serialization as plugin hot paths.

The fixes cache workspace matches for each repository catalog, remove regex use from path comparisons, prepare chat and sidebar updates outside the UI thread, and write only changed chats to atomic cache files. Each chat pane and sidebar allows only one background refresh at a time. Cache restoration avoids copying each transcript twice.

## Measured results

These are ranges of per-run medians from two measured runs of each build. CPU time and allocated bytes come from `ThreadMXBean` on the benchmark's platform thread. MB means 1,000,000 bytes.

| Work | Before | After |
| --- | ---: | ---: |
| Refresh CPU time | 44.0–58.8 ms | 2.9–4.1 ms |
| Refresh elapsed time | 45.6–60.0 ms | 3.0–4.5 ms |
| Refresh allocated bytes | 205.1 MB | 2.83 MB |
| Draft save CPU time | 226.0–311.6 ms | 1.5–2.2 ms |
| Draft save elapsed time | 312.2–397.2 ms | 1.5–2.2 ms |
| Draft save allocated bytes | 1,187.7 MB | 0.32 MB |

The fixture has 223 chats, 47,499 tool-output items, 156 worktrees, 40 chat directories, 64 active chats, and a 239 MB cache. A refresh reads both sidebar lists and builds and serializes four incremental chat snapshots. A save changes one draft. Each refresh run has 30 warmups and 30 measured iterations. Each save run has one warmup and five measured iterations. The warmup save includes initial cache creation or migration, which is outside the steady-state save measurements.

Both builds used IntelliJ IDEA 2026.2.2 and its Java 25 runtime on the same Mac, with the same JFR profile settings. Runs used separate disposable profiles in baseline/optimized/baseline/optimized order and never contacted a model. Earlier calibration runs showed startup variation, so the final comparison increased refresh warmup from five to 30 iterations. Remaining variation is shown as ranges instead of selecting one favorable result.

The complete refresh output SHA-256 was identical in every measured run: `0a354677bbe149c161fa05c7f7ddea23e74587d9f3c298deedec01d8784c4462`. Raw samples are in [performance-2026-10-01.json](performance-2026-10-01.json).

## Correctness and limits

- Native four-pane checks passed: 120 draft changes reached the correct chats, with monotonically increasing published revisions.
- Core checks passed: 148 tests, zero failures, four opt-in live tests skipped.
- A real IntelliJ restart restored all 223 fixture chats, all 47,499 items, the unsent draft, directories, and archive states.
- Migration of a private copy of the real cache preserved every full conversation snapshot: 223 chats and 47,441 items. Only aggregate results are recorded here.
- Unit checks cover unchanged-file preservation, one-chat writes, a replacement conversation with the same revision, interrupted migration, unindexed files, nested workspaces, and Windows/WSL paths.
- The old aggregate cache remains untouched during migration. The new index is installed only after the per-chat files are written. The first migration still writes all chats once in the background.

The table measures plugin data preparation and persistence, not end-to-end display latency or model response time. It does not establish a retained-memory or whole-IDE CPU reduction. The live IDE was not restarted during profiling, so verification in the user's full workspace after loading the new build remains separate from these controlled results.

## Reproduce

Build `:core:test buildPlugin nativeSmokePlugin`, then run `python3 scripts/native-smoke/profile-plugin.py --plugin <plugin.zip> --label <name>`. The runner prints the disposable profile path, saves all samples in `logs/performance-result.json`, and records `logs/performance.jfr`. It stops only the IDE process it created.

Reopen that profile with the same command plus `--restore <profile-path>` to validate all saved content. Use `check-native-refresh.mjs` with the standard four-pane smoke fixture to check 120 draft changes and ordered background updates. No model calls are made.

Measured package SHA-256 values:

- Baseline: `daba12585a4941cafbf404905405d91bb8f5cb975eecf7461cf0d813e367484f`
- Optimized: `bc8129ceeaffe9d03ab0ac457e4638d60dd5e29fe2fe111bdeb6746ca7ae5e17`
