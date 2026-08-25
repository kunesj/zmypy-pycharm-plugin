# .ai — context for zuban (zmypy) support

> **STATUS 2026-08-25: v2.4.0 — real-time annotation for zuban reworked to a temp-dir
> mirror** (replaces the shipped "check on save", which left stale/shifted underlines);
> 55/55 green. See decision #2 below and the "Mirror mode" section of
> [zuban-verified-behavior.md](zuban-verified-behavior.md).
>
> **STATUS 2026-08-24: IMPLEMENTED, TESTED (54/54 green), AND FORKED.** See the
> "Completion notes" section at the top of [plan-zuban-support.md](plan-zuban-support.md).
> Two major deviations from the original plan:
> 1. **`pycharm-plugin-base` was inlined into this repo** (user decision) — there is no
>    more separate base dependency, no `1.4.0` publish, no git-tag include.
> 2. **The repo was forked and renamed** to `kunesj/zmypy-pycharm-plugin` (plugin id
>    `works.kunesj.zmypy`, name `ZMypy`, vendor Jiří Kuneš; NOT on the JetBrains
>    Marketplace — install from disk). Full package rename:
>    `works.szabope.plugins.mypy` → `works.kunesj.plugins.zmypy`,
>    `works.szabope.plugins.common` → `works.kunesj.plugins.common` (inlined base).
>    Also renamed: `ZMypyBundle` (+`messages/ZMypyBundle.properties`), `@State`
>    `ZMypySettings`/`ZMypyPlugin.xml`, inspection shortName + html `ZMypyInspection`,
>    tool window `"ZMypy "`, notification group `"ZMypy group"`, configurable id
>    `Settings.ZMypy`, all action ids, `pluginGroup=works.kunesj.pycharm`.
>    Sources now live at `src/main/kotlin/works/kunesj/plugins/{zmypy,common}/`
>    (+ test helpers at `src/test/kotlin/works/kunesj/plugins/common/test/`).
>    The zuban feature is documented as AI-assisted (Qwen3.8-27B), "use at your own risk".
>    DynamicBundle resolves bundle names at the **classpath root** (not package-relative):
>    `messages.X` → `resources/messages/X.properties`.

Task (original): extend this PyCharm plugin (currently: mypy integration) so it also
supports **zuban** (Rust mypy-compatible type checker; mypy-mode executable: `zmypy`).

## Files in this directory

| File | Contents |
|---|---|
| [plan-zuban-support.md](plan-zuban-support.md) | **The full implementation plan** — base-library changes, plugin changes file-by-file, tests, release steps, gotchas. Start here. |
| [zuban-verified-behavior.md](zuban-verified-behavior.md) | **Empirically verified facts about zuban/zmypy 0.9.0** — CLI flag support, output grammar, exit codes, column/line semantics, pip wheel layout, reproduction commands. Verified against the real binary on 2026-08-17. |
| [codebase-map.md](codebase-map.md) | **File/line map of both repos** (`mypy-pycharm-plugin` + `pycharm-plugin-base`) with the current behavior of every file the plan touches. |

## Agreed design decisions (confirmed by the user)

1. **Dual mode with a selector.** Settings gets a "Type checker" selector: `mypy` | `zuban`,
   default `mypy` (existing behavior must stay bit-identical, tests are the guard).
2. **Real-time editor annotations for zuban: temp-dir mirror** (supersedes the originally
   shipped "check on save" design, removed 2026-08-25 — stale/shifted underlines). Because
   zmypy does NOT support `--shadow-file`, the sync (real-time) flow:
   - mirrors the working directory into a per-project temp dir (`ZubanMirror`):
     **clean directories as live dir symlinks** (one link = whole subtree, auto-updating),
     per-file links (symlink → hard link on same volume → copy fallback) only inside
     materialized dirs, dirty (unsaved) files as real copies of the in-memory document
     text; a dir is materialized (real mirror dir) only while a file in it is unsaved,
     then compacted back to a dir link;
   - dirty candidates = files **open in an editor** and modified — that bounds the
     per-scan work to O(open files), not O(repo size) (perf fix 2026-08-25, was a full
     per-file walk: 1.3–4.8 s on a project with a 12k-file venv);
   - runs zmypy with **CWD = the mirror root** and mirrored target paths — zmypy's
     CWD-relative output maps 1:1 back to the real files;
   - files **outside** the working directory are not annotated in zuban mode (user
     decision: zmypy should not run on files outside the repo). Manual (tool-window) scans
     keep the real-FS flow (they `saveAllDocuments()` first);
   - Windows without developer mode (no dir symlinks): whole mirror falls back to the
     per-file walk (correct, but the slow path);
   - the mirror run passes `--python-executable <venv python>`, because zmypy's own venv
     discovery (pyvenv.cfg walk-up) fails inside the mirror and would resolve third-party
     imports against the system Python (verified — see "Mirror mode").
   Verified against the real binary 2026-08-25 (see "Mirror mode" in
   `zuban-verified-behavior.md`): symlinks/hard links/dir links are followed, imports
   resolve through them, output stays CWD-relative, timing matches real-FS runs.
3. **SDK mode for zuban** ("Use project SDK" radio): run the **venv's own `zmypy` script**
   (derived from the SDK interpreter path: `<venv>/bin/zmypy`, or
   `<venv>\Scripts\zmypy.exe` on Windows). `python -m zuban` is impossible — see verified facts.
4. **Minimum zuban version: 0.9** (pip package `zuban`, requirement `zuban >= 0.9`;
   user's reference install is 0.9.0 in `/home/jirka642/PycharmProjects/mv-cr/be-api/.venv`).
5. **Inline the base (2026-08-24, supersedes the plan's base-1.4.0 release).** The user
   chose to remove the `pycharm-plugin-base` dependency entirely: all 43 base main sources
   + 10 test fixtures were copied into this repo under the SAME packages
   (`works.szabope.plugins.common.*`), so no import changes were needed. The pylint plugin
   (the other base consumer) keeps using the published base if/when maintained. Release is
   now a single repo/push. The local base checkout at `../pycharm-plugin-base` still holds
   the equivalent (uncommitted) 1.4.0 changes.

## One-paragraph summary of the work

`pycharm-plugin-base` (sibling repo, consumed as a **git included build at tag `latest`**)
needs 3 small backward-compatible additions: (a) an overridable `createProcessHandler`
hook in `ToolExecutor`, (b) a new `pythonSdkToolProcessHandler` (run a tool script from the
selected SDK's bin dir), and (c) an optional tool-selector row + dynamic label hooks in
`GeneralConfigurable`. Then bump base to `1.4.0` and push master so the release workflow
re-tags `latest`. The plugin itself gets: a `MypyTool` enum (per-tool args / file names /
pip requirement / SDK script / mandatory CLI args), a persisted `tool` setting, per-tool
package-management + validator, a **text output parser** for zmypy (mypy stays on JSON),
relative-path resolution against the working directory in both scan services, the
real-time annotator machinery — **originally** "check-on-save" (dirty-guard + cache + save
listener + daemon restart), **replaced 2026-08-25 by the temp-dir mirror** (see decision #2)
— settings UI selector, bundle strings, and new tests (mock `zmypy` bash scripts mirroring
the existing mock `mypy` scripts). Plugin version → `2.3.0`.

## How to work in a fresh session (post-implementation)

The zuban feature is implemented and the base is inlined — this is now a **single repo**.
There is no separate base to build/publish.

1. Read the "Completion notes" at the top of `plan-zuban-support.md` for what was actually
   done and how it deviated from the original plan.
2. If in doubt about any zuban behavior, re-run the reproduction commands from
   `zuban-verified-behavior.md` (`zmypy` lives at
   `/home/jirka642/PycharmProjects/mv-cr/be-api/.venv/bin/zmypy`).
3. Build/test in this environment (no IntelliJ Platform download needed) — there is a local
   PyCharm 2026.2.1 (build 262.9437.214) at `/home/jirka642/bin/pycharm`:
   ```
   ./gradlew test -PlocalPlatformPath=/home/jirka642/bin/pycharm
   ```
   (opt-in property; default builds use the `create("PY", "2026.2")` download path).
   Full suite (54 tests) passes against the local IDE, verified 2026-08-24.
   Toolchain: JDK 25 auto-provisioned in `~/.gradle/jdks` (`jvmToolchain(25)`), Gradle
   wrapper 9.0.0, GitHub SSH push works (remote `git@github.com:szabope/mypy-pycharm-plugin`,
   auth as `kunesj`). In intellij-platform gradle plugin 2.18.1 the local-IDE API is
   `dependencies { intellijPlatform { local(path) } }` (the old `localPlatformPath`
   extension property no longer exists).
4. Keep tests consistent with the documented mock-script contract (exact-argument
   assertions). The mock executables are bash scripts under `src/test/testData/`.

## 2026.2.1 platform API gotchas (verified 2026-08-24, local PyCharm 262.9437.214)

These differ from older platform docs and cost real debugging time:
- `FileDocumentManagerListener.onDocumentSaved` → renamed **`afterDocumentSaved(Document)`**.
- `VirtualFile.getFileExtension()` → **`getExtension()`** (property `extension`).
- `ProjectActivity.execute()` → now **`execute(project: Project)`** (takes the project).
- `Document.isModified()` → **removed**; use `FileDocumentManager.isFileModified(vf)`.
  Caveat: in headless tests `isFileModified` can stay `true` after `commit`/`setText`
  (stale modified-flag), which is why the post-save annotator test is not asserted.
- `com.jetbrains.python.sdk.PythonSdkAdditionalData()` no-arg ctor → **private**; use
  `PythonSdkAdditionalData(PyFlavorAndData(PyFlavorData.Empty, PythonSdkFlavor.UnknownFlavor.INSTANCE))`.
