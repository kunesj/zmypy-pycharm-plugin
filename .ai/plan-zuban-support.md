# Plan: zuban (zmypy) support — implementation

Status: design agreed with the user (see `README.md` for the decisions). This file is the
complete implementation plan. Companion files: `zuban-verified-behavior.md` (what zmypy can
and can't do — verified), `codebase-map.md` (current file/line map).

> **ADDENDUM 2026-08-25 (v2.4.0):** the §2.7 "check on save" real-time design (dirty-guard
> + `ScanResultCache` + `DocumentSavedActivity` → daemon restart) was shipped in 2.3.0 and
> then **removed** — underlines stayed stale while typing and could shift to wrong lines.
> It was replaced by a **temp-dir mirror**: `ZubanMirror` mirrors the working directory,
> zmypy runs with CWD = the mirror root, and its CWD-relative output maps 1:1 back to the
> real files. Files outside the working directory are not annotated in zuban mode. §2.8
> (text parser), §2.9 (async scan, which stays on the real FS because manual scans save all
> documents first) and everything else of the plan is unchanged. Verified binary facts are
> in the "Mirror mode" section of `zuban-verified-behavior.md`.
>
> **Perf rework (same day):** a full per-file mirror walk took 1.3–4.8 s per edit on a
> project whose working dir contains a 12k-file venv. `ZubanMirror` now represents clean
> subtrees as **live directory symlinks** and materializes real dirs only along paths to
> files that are open-in-editor and modified (the only possible dirty files), compacting
> them back to dir links when clean. Per-scan cost drops from O(repo) to O(open files);
> a per-file walk remains as the fallback when dir symlinks are unavailable (Windows w/o
> developer mode). See decision #2 in `README.md`.

## Completion notes (2026-08-24) — READ FIRST

**Implemented; full suite 54/54 green** (26 pre-existing + 28 new) against local PyCharm
2026.2.1 via `./gradlew test -PlocalPlatformPath=/home/jirka642/bin/pycharm`.

Deviations from this plan:
1. **Base inlined, not released** (user decision): all of `pycharm-plugin-base` (43 main
   files + 10 test fixtures + `CommonBundle.properties`, same packages) was copied into
   `src/main/kotlin/works/szabope/plugins/common/` and
   `src/test/kotlin/works/szabope/plugins/common/test/`. §1 (base changes) and §4/§5
   (base release, git-include local loop) are **moot** — no `1.4.0` publish, no
   includegit, no GitHub Packages. `settings.gradle.kts` is back to a plain single build.
   The local `../pycharm-plugin-base` checkout still has the equivalent uncommitted 1.4.0
   changes (keep only if the pylint plugin still needs them).
2. **Text parser**: added a 4th result variant `ZubanParseResult.Ignored` (indented
   note-continuations — attached to `lastIssue.hint`, not re-emitted, never balloon).
   Critical fix found in testing: `ProcessLine.text` arrives **with a trailing newline** —
   the parser `trimEnd()`s input (leading spaces preserved for note detection); without
   that, "Found …" summary lines became `Junk` and ballooned.
3. **Annotator**: dirty-check uses `FileDocumentManager.isFileModified(vf)` (not
   `Document.isModified()`, which is gone in 262.9437.214). The §3.4 *save → re-scan*
   test was dropped as flaky (stale modified-flag after commit in headless TempFS);
   `ZubanAnnotatorTest` keeps the deterministic clean→scan and dirty→cache(+no process,
   no temp file) tests. `DocumentSavedActivity` (save → `DaemonCodeAnalyzer.restart`) is
   implemented + registered but un-asserted.
4. **2026.2.1 API renames** hit during implementation: `onDocumentSaved` →
   `afterDocumentSaved`; `VirtualFile.fileExtension` → `extension`;
   `ProjectActivity.execute(project)`; `PythonSdkAdditionalData()` ctor now private
   (remote-SDK init test patched — see README gotcha list).
5. **Configurable**: one extra dynamic hook beyond §1.3's list —
   `versionCheckTitleForTool` (the `apply()` progress title).
6. Tests added: `ZubanTextOutputParserTest` (16), `ScanCliZubanTest` (4),
   `ScanSdkZubanTest` (1), `ZubanAnnotatorTest` (2), `MypyValidatorTest` (4, §3.5),
   default-tool init test (§3.6). §3.3's SDK *negative* case (zuban missing → bubble)
   not added. SDK-mode test proves `<sdk>/bin/zmypy` (no `-m`) is invoked because the
   mock lives there and asserts the arg list.

Everything else follows the plan (§2.1–§2.13). `mypy`-mode CLI args are byte-identical —
the untouched `ScanCliTest`/`ScanSdkTest`/`AnnotatorTest` are the guard and they pass.

## Decisions recap

1. **Dual mode** — Settings "Type checker" selector `mypy | zuban`, default `mypy`.
   mypy path stays bit-identical (its tests assert exact CLI args — that's the guard).
2. **Real-time editor annotations for zuban = "check on save"**: no temp files, no
   `--shadow-file`. Dirty document → cached result; clean document → check the real on-disk
   file; `onDocumentSaved` → `DaemonCodeAnalyzer.restart(psiFile, reason)` re-runs the
   annotator. Underlines = on open + after each save (user accepted the delay).
3. **SDK mode for zuban** = run the selected SDK's own `zmypy` script
   (POSIX `<sdkHomeParent>/zmypy`, i.e. `<venv>/bin/zmypy`; Windows
   `<venv>\Scripts\zmypy.exe`), with the SDK environment; `python -m zuban` is impossible.
   Detection/install flow reuses the pip package `zuban` (>= 0.9) through the existing
   `AbstractPluginPackageManagementService`.
4. **Zuban output = text mode** (zmypy has no `--output json`, no
   `--show-absolute-path`). New stateful text parser; output paths CWD-relative → resolve
   against `configuration.workingDirectory` (== process CWD). Columns: text 1-based →
   store 0-based to match the existing JSON pipeline's `MypyMessage` contract.

## 0. Work order

1. Base changes (§1) + base tests → base `1.4.0`.
2. Local-iterate the plugin against the base (§ "Local development loop").
3. Plugin changes (§2) + tests (§3).
4. Release (§4): base tag `latest`, plugin `2.3.0`, README/CHANGELOG.

## 1. `pycharm-plugin-base` changes (→ v1.4.0)

All additions are defaulted/optional — the pylint plugin (the other consumer) must compile
and behave unchanged.

### 1.1 `run/ToolExecutor.kt` — overridable handler creation

Extract the strategy switch (currently inline in `execute()`, ~L27-31) into an open hook:

```kotlin
protected open fun createProcessHandler(
    configuration: ToolExecutorConfiguration,
    parameters: List<String>
): OSProcessHandler =
    if (configuration.useProjectSdk)
        pythonModuleProcessHandler(project, moduleToRun, parameters, workingDir = configuration.workingDirectory)
    else
        commandLineProcessHandler(configuration.executablePath, configuration.workingDirectory, parameters)
```

`execute()` calls the hook. Everything else (flow, ProcessLine, `commandLine` exposure,
`isError`, cancellation) unchanged.

### 1.2 NEW `run/PythonSdkToolExecutionStrategy.kt`

Sibling of `pythonModuleProcessHandler` — runs a **tool script from the SDK environment**
instead of `python -m module`:

```kotlin
fun pythonSdkToolProcessHandler(
    project: Project,
    toolName: String,                 // e.g. "zmypy"
    parameters: List<String> = emptyList(),
    envs: Map<String, String> = emptyMap(),
    workingDir: String?
): OSProcessHandler
```

- Resolve SDK exactly like `pythonModuleProcessHandler` (`resolveModulePythonSdkNow()`,
  `requireNotNull` with the same `tool_executor.python_sdk_null` message).
- Patch envs with `addDefaultEnvironments(sdk, envs.toMutableMap())`.
- Exe path derived from `sdk.homePath`:
  - Windows (`SystemInfo.isWindows`): `Path(sdk.homePath).parent / "Scripts" / (toolName + ".exe")`
    (venv layout: `<venv>\python.exe` + `<venv>\Scripts\*.exe`).
  - POSIX: `Path(sdk.homePath).parent / toolName` (venv layout: `<venv>/bin/python` +
    `<venv>/bin/<tool>`).
- Otherwise identical `GeneralCommandLine` shape (CONSOLE parent env, workingDir,
  parameters) → `ToolProcessHandler`.

Base unit test (if the base has runnable unit tests for strategies; otherwise cover via
the plugin's SDK test §3): command line exe path + params + env assertions.

### 1.3 `configurable/GeneralConfigurable.kt` — optional tool selector

`ConfigurableConfiguration` gains (all defaulted → pylint unchanged):

```kotlin
val toolNames: List<String> = emptyList(),                    // e.g. ["mypy", "zuban"]; empty = no selector
val toolSelectorLabel: String = "",                           // combo row label, e.g. "Type checker:"
val pickerTitleForTool: ((String) -> String)? = null,         // e.g. { "Execute " + cap(it) + " as" }
val directOptionTitleForTool: ((String) -> String)? = null,   // e.g. { cap(it) + " executable:" }
val emptyWarningForTool: ((String) -> String)? = null,        // e.g. { cap(it) + " is not specified" }
val installButtonTextForTool: ((String) -> String)? = null,   // e.g. { "Install " + cap(it) }
```

`GeneralConfigurable` gains:

```kotlin
protected open fun selectedTool(): String = ""
protected open fun selectTool(tool: String) {}
```

`createPanel()`: when `config.toolNames.isNotEmpty()`, insert as the first indented row a
`comboBox` (items = `toolNames`, selected = `selectedTool()`) on change →
`selectTool(value)` + `refreshToolTexts()` + `pnl.validateAll()`.

`toolPicker()` / dynamic texts:
- radio title = `config.directOptionTitleForTool?.invoke(selectedTool()) ?: config.pickerDirectOptionTitle`
- buttonsGroup title = `config.pickerTitleForTool?.invoke(selectedTool()) ?: config.pickerTitle`
- empty-warning = `config.emptyWarningForTool?.invoke(selectedTool()) ?: config.pickerDirectOptionEmptyWarning`
- install button text = `config.installButtonTextForTool?.invoke(selectedTool()) ?: config.installButtonText`
- `refreshToolTexts()`: update the JComponent texts of the radio label, group title and
  install button held in cells/refs (captured at creation) to the new tool's values.
- File filter stays static (the plugin passes one combined name list covering both tools —
  no dynamic-filter API needed).

Behavioral notes:
- `apply()` already re-runs validation; the tool-dependent validators (executable version,
  SDK package) run against whatever `settings.tool` is at apply time because the plugin's
  `validateExecutable`/`validateLocalSdk` read the settings.
- Keep `installButton` enable-logic unchanged.

### 1.4 Nothing else in the base

- `AbstractToolSettings.isToolSet()` already works for zuban (SDK mode = SDK resolvable +
  installed requirement; the requirement is plugin-controlled) — verified against
  `AbstractToolSettings.kt` L64-70.
- `AbstractToolValidator` is already (versionFlag, packageName, packageService)-parameterized.
- `ToolExecutorConfiguration` needs no new field: the tool is carried by the plugin's own
  executor/annotator, exactly like `moduleToRun` already is.
- Tree navigation's absolute-path assumption (`AbstractToolWindowPanel.kt:46`) is fixed
  upstream by resolving paths at parse time in the plugin — no base change.

### 1.5 Base release

- Bump `pluginVersion=1.4.0` in base `gradle.properties`.
- Push master → `.github/workflows/release.yml` tags `1.4.0` + `latest` automatically.
- (Optional: CHANGELOG entry in base if it keeps one.)

## 2. `mypy-pycharm-plugin` changes

### 2.1 NEW `MypyTool.kt` (package root, next to `MypyArgs.kt`)

```kotlin
enum class MypyTool(
    val displayName: String,            // "Mypy" / "Zuban" — for dynamic UI strings
    val pipPackage: String,             // requirement + PyPackage name
    val minVersion: String,
    val moduleToRun: String?,           // SDK mode: mypy="mypy", zuban=null
    val sdkScriptName: String?,         // SDK mode: mypy=null, zuban="zmypy"
    val executableFileNames: List<String>,
    val mandatoryArgs: List<String>,
) {
    MYPY("Mypy", "mypy", "1.11", "mypy", null,
        exeNames("mypy", "mypyc"),
        MypyArgs.MANDATORY_ARGS),
    ZUBAN("Zuban", "zuban", "0.9", null, "zmypy",
        exeNames("zmypy", "zuban"),
        listOf("--show-column-numbers"));
}

// Windows: [n, n.exe, n.bat] (existing convention from MypyConfigurable L25-31),
// else [n, ...]
private fun exeNames(vararg names: String): List<String> =
    names.flatMap { listOf(it) + if (SystemInfo.isWindows) listOf("$it.exe", "$it.bat") else emptyList() }

companion object {
    fun fromName(name: String?): MypyTool =
        runCatching { valueOf(name?.uppercase() ?: "") }.getOrDefault(MYPY)
}
```

`MypyArgs.MANDATORY_ARGS` stays (now the MYPY entry's list). All other mypy-CLI constant
knowledge lives here — no other file hardcodes per-tool values.

### 2.2 `services/MypySettings.kt`

- `MypyState`: add `var tool by string("mypy")`.
- Add `val tool: MypyTool get() = MypyTool.fromName(state.tool)` and a setter used by the
  configurable: `fun setTool(t: MypyTool) { state.tool = t.name.lowercase() }`
  (or a `var tool` property with the getter mapping). Backward compatible: existing
  `MypyPlugin.xml` without the field → null → `MYPY`.
- `toolNotSetMessage()` → bundle string parameterized by `tool.displayName` (e.g.
  "mypy.configuration.tool_not_set={0} tool is not set").

### 2.3 `services/MypyPluginPackageManagementService.kt`

```kotlin
override fun getRequirement(): PyRequirement {
    val tool = MypySettings.getInstance(project).tool
    return pyRequirement(tool.pipPackage, PyRequirementRelation.GTE, tool.minVersion)
}
```
(Remove/keep the `MINIMUM_VERSION` const — mypy's "1.11" now lives in the enum; keep a
`const` mirror for test stubs if referenced — check `MypyPluginPackageManagementServiceStub`
in test sources: it currently stubs `pyRequirement("mypy", GTE, "1.11")`; keep it working by
defaulting to the MYPY requirement.)

### 2.4 `configurable/MypyValidator.kt`

- Constructor `MypyValidator(project: Project, tool: MypyTool)`.
- `packageName = tool.pipPackage` (affects `validateVersion`'s `PyPackage` name and the
  `match()` against the plugin package service's requirement).
- `versionFlag = "-V"` (both tools; verified zmypy `-V` → "zuban 0.9.0").
- `ToolValidatorMessages`: build with tool name where strings name the tool:
  `unknown_version` ("Unable to identify {0} version (-V)"),
  `invalid_version` ("Obsolete {0} version. Expected >= {1}"),
  `not_installed` ("{0} not installed"). Add matching `{0}`-parameterized bundle keys
  (old keys may be kept as non-parameterized aliases for mypy to minimize churn — pick one
  style; parameterized is cleaner).

### 2.5 `MypyExecutor.kt`

```kotlin
class MypyExecutor(project: Project, private val tool: MypyTool) :
    ToolExecutor(project, tool.moduleToRun ?: "mypy") {

    override fun isError(event: ProcessEvent): Boolean = event.exitCode > 2   // unchanged

    override fun createProcessHandler(configuration: ToolExecutorConfiguration, parameters: List<String>): OSProcessHandler =
        if (tool == MypyTool.ZUBAN && configuration.useProjectSdk)
            pythonSdkToolProcessHandler(project, tool.sdkScriptName!!, parameters, workingDir = configuration.workingDirectory)
        else super.createProcessHandler(configuration, parameters)
}
```

Update call sites: `AsyncScanService.kt:25` and `SyncScanService.kt:35`
(`MypyExecutor(project, tool)`, tool from `MypySettings.getInstance(project).tool`).

### 2.6 `services/mypyParamListBuilders.kt`

- `buildMypyParamList(configuration, targets, extraArgs)`: first param list seed becomes
  `tool.mandatoryArgs` instead of `MypyArgs.MANDATORY_ARGS` (add `tool: MypyTool`
  parameter — explicit is better than reading settings inside a builder). Everything else
  (config-file, user arguments via `ParametersListUtil.parse`, relativized
  `--exclude`, target append order) unchanged for both tools.
- The `--shadow-file` overload (L18-24) stays **MYPY-only** (zuban never calls it — the
  real-time flow changed per decision #2; guard with a require if ever called for ZUBAN, or
  leave it simply unused).
- No other changes: for zuban real-time, the *direct* overload is used with the real
  files as targets (no temp files any more).

### 2.7 Real-time (sync) flow — "check on save" for zuban

Touch points: `SyncScanService.kt`, `MypyAnnotator.kt`, new `ScanResultCache`,
new `DocumentSavedActivity`, `META-INF/works.szabope.mypy-PythonCore.xml`.

**(a) `SyncScanService.scan(targets, configuration)`** becomes tool-aware:

```kotlin
val tool = MypySettings.getInstance(project).tool
val useShadow = tool == MypyTool.MYPY
val shadowedTargetMap = if (useShadow) targets.associateWith { copyTempFrom(it) } else emptyMap()
val parameters = if (useShadow)
    buildMypyParamList(configuration, shadowedTargetMap, tool)
else
    buildMypyParamList(configuration, targets, tool)      // real files, no temp copies
```

- Parser dispatch: `useShadow` (MYPY) → existing `MypyOutputParser` (JSON) behavior
  untouched. ZUBAN → new `ZubanTextOutputParser` (§2.8); `Junk` lines → same channels as
  today's non-JSON (async: accumulate for balloon; sync: warn log). `Summary` lines →
  silently dropped.
- File resolution: for ZUBAN, `message.file` may be CWD-relative. Resolve once in the parse
  mapping step (both services):
  `val resolved = if (file.isAbsolute) file else Path.of(configuration.workingDirectory).resolve(file).normalize()`
  then the existing `targetsByPath[canonical] ?: VfsUtil.findFile(Path(resolved), false)`
  lookup. Store the **resolved absolute path** in the emitted `MypyMessage.file` so the
  tool-window tree (base `AbstractToolWindowPanel.kt:46` `VfsUtil.findFile(Path(issue.file))`
  + `TreeModelManager` grouping by file string) works unmodified. MYPY path already yields
  absolute paths (`--show-absolute-path`) — behavior identical.
- Cleanup/stderr/`silent=true`/`handleScanException` unchanged (skip temp cleanup when no
  temps were created).
- MYPY flow must remain byte-identical (its tests assert exact args & mapping).

**(b) NEW `services/ScanResultCache.kt`** — project-level service:

```kotlin
@Service(Service.Level.PROJECT)
class ScanResultCache {
    // key: VirtualFile canonical path; value: (configHash, List<MypyMessage>)
    fun get(file: VirtualFile, configHash: String): List<MypyMessage>?
    fun put(file: VirtualFile, configHash: String, messages: List<MypyMessage>)
    fun clear(file: VirtualFile?)   // on dispose / settings change if desired
}
```
(ConcurrentHashMap; values immutable lists.) `configHash` = hash of the relevant
`ToolExecutorConfiguration` fields (executablePath/useProjectSdk/configFilePath/arguments/
workingDirectory/excludeNonProjectFiles) + tool name — so a settings change naturally
invalidates stale entries.

**(c) `annotator/MypyAnnotator.kt`** — dirty-guard + cache:

```kotlin
override fun doAnnotate(info: AnnotatorInfo): List<MypyMessage> {
    if (MypySettings.getInstance(info.project).tool == MypyTool.ZUBAN) {
        val doc = FileDocumentManager.getInstance().getCachedDocument(info.file)
        if (doc?.isModified == true) {
            // hash = tool + current MypySettings fields (executablePath, useProjectSdk,
            // configFilePath, arguments, workingDirectory, excludeNonProjectFiles)
            return ScanResultCache.getInstance(info.project)
                .get(info.file, ConfigHash.hash(info.project)) ?: emptyList()
        }
    }
    return super.doAnnotate(info)
}
```
and in `scan(info, configuration)`: after computing the (unfiltered) result for
`info.file`, `if (tool == ZUBAN) ScanResultCache.put(info.file, hash, result)` and then
return `result[info.file]` as today (or restructure: compute the map, store, return).

Notes:
- `Document.isModified()` is a flag read, safe off-EDT (`doAnnotate` runs on background —
  `ToolAnnotator.doAnnotate` already does `runBlocking` there).
- While dirty the editor keeps showing the last saved-state result (no flicker, no
  per-keystroke processes — a perf win over today's mypy behavior).
- On first open: document clean → real scan → underlines immediately.

**(d) NEW `activity/DocumentSavedActivity.kt`** — save → re-highlight:

```kotlin
class DocumentSavedActivity(private val project: Project) : ProjectActivity {
    override suspend fun execute() {
        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun onDocumentSaved(document: Document) {
                    if (MypySettings.getInstance(project).tool != MypyTool.ZUBAN) return
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    if (vf.fileType !in SUPPORTED_FILE_TYPES) return   // top-level val in common.action (`.py`/`.pyi`)
                    if (vf is LightVirtualFile) return
                    // only bother for files with an open editor
                    if (FileEditorManager.getInstance(project).allEditors.none { it.file == vf }) return
                    if (!MypySettings.getInstance(project).isToolApplicable()) return    // cheap sync check, no process
                    val psi = PsiManager.getInstance(project).findFile(vf) ?: return
                    ApplicationManager.getApplication().invokeLater {
                        DaemonCodeAnalyzer.getInstance(project).restart(psi, "mypy-pycharm-plugin: file saved")
                    }
                }
            })
    }
}
```
- `FileDocumentManagerListener.TOPIC` is on the **application** message bus (subscribe via
  `ApplicationManager.getApplication().messageBus`, connect to the project disposable).
- `DaemonCodeAnalyzer.restart(psiFile, reason)` = `markWholeFileScopeDirty` + re-run —
  public API (verified in intellij-community; see `zuban-verified-behavior.md`).
- Register in `works.szabope.mypy-PythonCore.xml`:
  `<projectActivity implementation="works.szabope.plugins.mypy.activity.DocumentSavedActivity"/>`.
- Consequences to be aware of: `AbstractScanAction` calls `saveAllDocuments()` before
  manual scans → the listener fires for all saved files → editor underlines refresh after
  a manual scan too (desirable synergy, coalesced by the daemon).
- Guard against re-entrancy loops: `restart` never fires `onDocumentSaved` (no save
  happens), so there's no loop.

**(e) No temp files for zuban anywhere.** The `pycharm_mypy_` temp mechanism remains a pure
mypy-mode thing.

### 2.8 NEW `services/parser/ZubanTextOutputParser.kt`

Stateful per run (one instance per `execute()` call — keep a `lastMessage` for note
continuations). Result shape so both scan services can share handling:

```kotlin
sealed interface ZubanParseResult {
    data class Issue(val message: MypyMessage) : ZubanParseResult   // file still needs workdir resolution
    data object Summary : ZubanParseResult                           // "Found ...", "Success: ..."
    data class Junk(val raw: String) : ZubanParseResult              // unexpected line
}
```

- Issue regex (DOTALL not needed; single-line):
  `^(?<file>.+?):(?<line>\d+)(?::(?<col>\d+))?:\s+(?<sev>error|warning|note):\s+(?<msg>.*?)(?:\s{2}\[(?<code>[A-Za-z0-9_-]+)\])?\s*$`
  - file group is greedy-safe because the rest of the line is anchored to the
    `:digits:` shape; verify against `../otherdir/bad.py` and paths with spaces
    (`white space/foo.py:1: error: ...` — the first `:` after the file name is the
    delimiter; keep the file group non-greedy-to-first-matching-tail or split-once on the
    LAST `:digit:digit...` — test both).
  - Build `MypyMessage(file=rawFile, line=line, column=(col?.minus(1) ?: 0), message=msg,
    code=code ?: "", severity=sev.toLowerCase())` — i.e. **line stays 1-based, column
    converted to 0-based** (verified mypy-JSON contract, see verified-behavior doc) — and
    run it through the shared `adjustForPlatform` (extract it or duplicate the 2-line
    logic) so `MypyMessage` ends up identical in shape to mypy-mode output.
  - Indented `^\s+note:\s+(.*)$` → continuation: append to `lastMessage.hint` (defensive;
    older-mypy-style; zuban 0.9.0 uses prefixed notes).
  - `\d+$` tail or `Found .*` / `Success: no issues found in .*` → `Summary`.
  - blank after trim → ignore (callers already filter blanks).
  - everything else → `Junk`.
- **`Junk` semantics differ by mode**: in MYPY mode any non-JSON stdout is suspicious →
  balloon (today's behavior, keep). In ZUBAN mode, `Junk` also → same accumulation
  (stderr already goes to the balloon path), but `Summary` never does. This prevents the
  normal "Found N errors" line from opening the "Zuban has thrown an error" dialog.
- Location of path resolution: NOT in the parser (keep it pure/testable); in the scan
  services where `workingDirectory` is available (§2.7a).

### 2.9 `services/AsyncScanService.kt`

Mirror of `SyncScanService` changes: tool from settings; param builder with tool; parser
dispatch (JSON vs text); ZUBAN: `Summary` dropped, `Junk`→`nonJsonStdout` accumulation;
resolve+absolutize `message.file` against `configuration.workingDirectory` in the `emit`
step so the tree's per-file grouping and navigation (absolute-path assumed) are correct.
MYPY branch stays textually equivalent to today.

### 2.10 `configurable/MypyConfigurable.kt`

- `ConfigurableConfiguration`:
  - `toolNames = listOf("mypy", "zuban")`,
  - `toolSelectorLabel = bundle("mypy.configuration.type_checker.label")` ("Type checker:"),
  - `pickerTitleForTool = { bundle("mypy.configuration.mypy_picker_title", cap(it)) }` ("Execute {0} as"),
  - `directOptionTitleForTool = { bundle("mypy.configuration.path_to_executable.label", cap(it)) }`
    (keep key, add `{0}` = "Mypy executable:" / "Zuban executable:"),
  - `emptyWarningForTool = { bundle("mypy.configuration.path_to_executable.empty_warning", cap(it)) }`,
  - `installButtonTextForTool = { bundle("mypy.intention.install_mypy.text", cap(it)) }` →
    "Install {0}".
  - `FileFilter` = combined `MypyTool.MYPY.executableFileNames + MypyTool.ZUBAN.executableFileNames` (dedup),
    replacing the current Windows/other branching (L25-31).
  - unchanged: `id`, `helpTopic`, config-file picker/comment/help, arguments description.
- Override `selectedTool()` / `selectTool(t)` → read/write `MypySettings tool`.
- `validateExecutable(path)` → `with(MypyValidator(project, settings.tool)) { ... }`
  (existing chain `validateExecutablePath ?: validateVersion`).
- `validateLocalSdk()` → `MypyValidator(project, settings.tool).validateProjectSdk()`.
- Keep `ID = "Settings.Mypy"` (stable).

### 2.11 `resources/messages/MypyBundle.properties`

Add/adjust (exact final wording is implementation detail; keep keys stable where users
might reference them):
- `mypy.configuration.type_checker.label=Type checker:`
- parameterize (or add zuban variants of):
  - `mypy.configuration.mypy_picker_title=Execute {0} as`
  - `mypy.configuration.path_to_executable.label={0} executable:`
  - `mypy.configuration.path_to_executable.empty_warning={0} is not specified`
  - `mypy.configuration.path_to_executable.unknown_version=Unable to identify {0} version (-V)`
  - `mypy.configuration.mypy_invalid_version=Obsolete {0} version. Expected >= {1}`
  - `mypy.configuration.mypy_not_installed={0} not installed`
  - `mypy.configuration.path_to_executable.version_validation_title=Validating {0} version`
  - `mypy.configuration.tool_not_set={0} tool is not set`
  - `mypy.toolwindow.balloon.external_error={0} has thrown an error <a href='#a'>Details</a>`
  - `mypy.toolwindow.balloon.failed_to_execute=Failed to execute {0} <a href='#a'>Details</a>`
  - `mypy.dialog.execution_error.title={0} Execution Error`
  - `mypy.executable.parsing-result-failed=Parsing {0} result failed!\nConfiguration: {1}`
  - `mypy.intention.install_mypy.text=Install {0}` (and `action....InstallMypyAction.text`,
    `action.InstallMypyAction.done_html` if they name the tool)
- `mypy.configuration.config_file.comment` — currently links mypy docs. Either parameterize
  the link per tool (zuban → `https://docs.zubanls.com`) or use one tool-neutral text with
  both links. Minor.
- Keep plugin-identity strings as-is: notification group "Mypy group", tool window "Mypy ",
  `action....ScanAction.text=Scan with Mypy`, `mypy.configuration.name=Mypy` (Settings node
  title), inspection names — they name the **plugin**, not the running tool.
  (Optional polish, out of scope: dynamic action text via `update()`/presentation.)

### 2.12 Misc plugin tweaks

- `MypyMessageConverter` / `mypySeverityConfigs` / `MypyTreeModelDataItem` — unchanged
  (text parser emits the same shape/severities).
- `handleScanException.kt` / `MypyIncompleteConfigurationNotifier` — parameterize
  tool-naming bundle keys via `tool.displayName` (balloon texts).
- `dialog/DialogManager.kt` / `MypyErrorDialog.kt` — titles/content that say "Mypy" →
  tool-parameterized where the string is produced at runtime with the tool known; keep
  static where it would over-complicate (cosmetic).
- `README.md` — new "Zuban" section: install (`pip install zuban` / uv), requirements
  (`zuban >= 0.9`), the two tools' differences, **limitations for zuban mode**:
  real-time underlines refresh on save (not on every edit), manual scans see saved content
  (they save all documents first), mypy-only CLI flags in custom Arguments produce a
  visible usage-error balloon, config files: mypy.ini/`[tool.mypy]`/`[tool.zuban]` all work.
- `CHANGELOG.md` — 2.3.0 entry (changelog gradle plugin format used by the repo).

### 2.13 Explicitly OUT of scope

- `zuban check` (pyright-like mode) — only `zmypy` (mypy mode) is integrated.
- Dynamic action-menu text per tool; per-tool severity configs; auto-detection of the tool
  (the selector is explicit by decision); LSP integration.
- The unused `scanBeforeCheckIn` flag (still not consumed anywhere — pre-existing).

## 3. Tests

Framework facts: heavy platform tests (junit4 + mockk + base testFixtures); mock
executables are **bash scripts** asserting the exact expected args before emitting canned
output. Default tool = MYPY ⇒ all existing tests must pass (one known exception, already
fixed, unrelated to zuban: `MypyInitializationWithRemotePythonSdkTest` needed a platform
API drift patch for PyCharm 2026.2.1's private `PythonSdkAdditionalData()` constructor —
see codebase-map tests section).

Run command for this environment (uses local PyCharm 2026.2.1, no platform download):
`./gradlew test -PlocalPlatformPath=/home/jirka642/bin/pycharm`

### 3.1 Parser unit tests (new `ZubanTextOutputParserTest`, lightweight)
Golden lines (from the verified-behavior doc, real output):
- `bad.py:1:10: error: ...  [assignment]` → Issue(file=bad.py, line=1, col=10-1=9 pre-adjust, code=assignment, severity=error-lower)
- with/without column; `note:` and `warning:` severities; code-less message → code ""
- `Found 2 errors in 1 file (checked 1 source file)` / `Success: no issues found in 3 source files` → Summary
- `../otherdir/bad.py:2:5: error: x [y]` (relative w/ `..`), `white space/foo.py:1: error: msg [code]` (spaces in path)
- indented `    note: ...` → appended to previous issue's hint (defensive)
- garbage line → Junk
- post-`adjustForPlatform` shape equals the mypy-JSON pipeline shape (0-based line/col,
  uppercase severity).

### 3.2 Tool-window (async) action tests — mirror `ScanCliTest`
New `testData/action/scan_cli_zuban/` with a `zmypy` mock script:
- `-V` → `zuban 0.9.0`
- scan → assert args exactly: `--show-column-numbers [--config-file ...] [user args]
  --exclude excluded_dir <absTarget>` (NO `--output json`, NO `--show-absolute-path`),
  print text lines (relative paths `manualScan.py:...` + a `Found 1 error ...` line),
  exit per variant.
- Cases: clean exit 0; errors exit 1 (issues appear in tree, **no** "has thrown an error"
  balloon for the `Found ...` line); usage error exit 2 with stderr → balloon; exit 3 →
  balloon; settings with `tool = "zuban"`.
- Tree content: issue text contains `[code] (line:col)`; double-click navigation resolves
  (relative path → absolute → VFS), i.e. the resolved absolute path is what the tree stores.

### 3.3 SDK tests — mirror `ScanSdkTest`
`testData/action/scan_sdk_zuban/MockSdk/bin/` with `python` (existing pattern) plus a
`zmypy` script (`-V` → `zuban 0.9.0`; args assert; emit text).
- Assert `executor.commandLine` runs `<sdk>/bin/zmypy` (the base helper's derived path)
  with the SDK env, **not** `-m`.
- Package stub: `AbstractPluginPackageManagementServiceStub` configured with a `zuban`
  package ≥ 0.9 for the SDK (and a negative case: missing → "incomplete configuration"
  bubble, SDK radio disabled-ish validation message "Zuban not installed").

### 3.4 Annotator (real-time) tests
- **clean file**: open file with errors on disk → underlines appear; mock `zmypy` invoked
  with the **real file canonical path** (assert in script); no temp file created in the
  project dir nor system temp for that run (assert directory listing / script args).
- **dirty file**: modify the document (unsaved) → force the daemon re-run (test fixture
  equivalent of today's AnnotatorTest flow, e.g. `myFixture.doHighlighting()`) → mock
  `zmypy` NOT invoked again (it can fail the test loudly if called) → old underlines
  (cached) remain.
- **save**: `PsiDocumentManager.commitDocument` / `FileDocumentManager.saveDocument` →
  (listener → `restart` → daemon re-run) → `zmypy` invoked again with the on-disk path →
  underlines reflect new content.
  If driving the daemon after a save is flaky in the test env, fallback: call
  `DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "test")` explicitly after
  asserting the listener registered (split the assertion: listener wiring vs daemon
  scheduling).
- **mypy mode unchanged**: existing `AnnotatorTest` cases (shadow-file flow, temp files,
  non-json, exit 3, spaces) must pass as-is.

### 3.5 Configurable / validation tests
- Selector present with [mypy, zuban]; switching updates label/install-button texts
  (assert component text) and re-validates.
- Fake `zmypy` answering `-V` with `zuban 0.8.0` → "Obsolete Zuban version. Expected >= 0.9";
  `zuban 0.9.0` → valid; garbage `-V` output → "Unable to identify Zuban version (-V)".
- Fake `mypy` still validated as before.

### 3.6 Initialization tests
- From scratch: `tool` defaults `mypy` (state XML without the field → MYPY).
- Legacy migration (`OldConfiguration/.idea/mypy.xml`): ends up `tool=mypy` + existing
  executable/args (unchanged expectations).

## 4. Release & rollout

1. Base branch: implement §1 → run base tests → bump `pluginVersion` to `1.4.0` → push
   master (workflow tags `1.4.0` + `latest`).
2. Plugin: `settings.gradle.kts` can stay on `tag.set("latest")` (or pin `"1.4.0"` for
   determinism — pinning is nicer for the release commit).
   Implement §2 → full test suite (all old + new) → bump `gradle.properties`
   `pluginVersion=2.3.0` → CHANGELOG + README.
3. Plugin release follows its existing workflow (check `.github/workflows/`).

## 5. Local development loop (gotcha!)

The plugin resolves the base from the **GitHub tag**, so:
- For iteration, temporarily edit `settings.gradle.kts`:
  `gitRepositories { include("../pycharm-plugin-base") }` (includegit local-include form)
  — verify the exact local-include syntax on `me.champeau.includegit` v0.3.2 docs if unsure
  (`https://github.com/GradleUp/includegit`).
- Always restore the git include + tag before final build/release.
- The local `../pycharm-plugin-base` checkout is the same upstream repo
  (`github.com/szabope/pycharm-plugin-base`); commit base changes to a branch there.
- **IDE download not needed in this environment**: PyCharm 2026.2.1 (262.9437.214) at
  `/home/jirka642/bin/pycharm` + the opt-in `build.gradle.kts` switch
  (`-PlocalPlatformPath=/home/jirka642/bin/pycharm`; implemented via
  `dependencies { intellijPlatform { local(path) } }` — the 2.18.1 plugin has no
  `localPlatformPath` extension property) make build/test fully local. Verified 2026-08-18:
  compile + full 26-test suite green against it. Caveat: 2026.2.1 ≠ the 262.8665.309
  the repo pins as since-build; if a behavior looks platform-driven, double-check against
  the `create("PY", "2026.2")` download path once.

## 6. Risk checklist (re-verify while implementing)

- [ ] mypy-mode CLI args byte-identical (ScanCliTest/ScanSdkTest/AnnotatorTest assert).
- [ ] `MypyMessage.file` absolute in BOTH modes (tree nav depends on it).
- [ ] text parser: column −1 (0-based), line 1-based pre-`adjustForPlatform`.
- [ ] `Summary` lines never balloon in zuban mode; `Junk` still does.
- [ ] no temp files / no VFS writes for zuban real-time.
- [ ] `restart()` only for ZUBAN + `.py/.pyi` + open editor + valid config.
- [ ] Windows exe naming (`zmypy.exe`, `<venv>\Scripts\`) — logic only (no Win CI, presumably).
- [ ] settings XML backward compat (no field → mypy mode, nothing else changes).
- [ ] base stays backward compatible for the pylint plugin (all new config fields defaulted;
      new open hooks only).
- [ ] CI resolves the platform from `create("PY", "2026.2")` (latest 2026.2.x download),
      while this env builds against local 2026.2.1: the `PythonSdkAdditionalData()`
      private-constructor test patch (2026-08-18) must hold for both.
- [ ] `PyPackage` name for version validation is `zuban` (not `zmypy`) — the `-V` output
      says "zuban 0.9.0".
