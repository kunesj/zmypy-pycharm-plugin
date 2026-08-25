# Codebase map — mypy-pycharm-plugin (+ inlined pycharm-plugin-base)

> **STALENESS NOTICE (2026-08-24):** the base is now **inlined into this repo** —
> `works.szabope.plugins.common.*` main sources live at
> `src/main/kotlin/works/szabope/plugins/common/`, its test fixtures at
> `src/test/kotlin/works/szabope/plugins/common/test/` (package names unchanged). The
> "How the two repos connect" section below (git included build at tag `latest`,
> GitHub Packages, base 1.4.0 release) **no longer applies** — `settings.gradle.kts` is a
> plain single build and there is no `myPluginCommon` dependency. Line numbers below
> refer to the pre-zuban, pre-inline state (2026-08-17); treat them as approximate.

Current state (before zuban changes). Paths are repo-relative; line numbers refer to the
`master` state as of 2026-08-17 (plugin v2.2.4, base v1.3.3). Everything mypy-specific
delegates heavily to the base library; the plugin's own code is thin.

## How the two repos connect (CRITICAL build fact)

- Base repo: `../pycharm-plugin-base` (local checkout), Maven coordinates
  `works.szabope.plugins:common` (group `works.szabope.plugins`, artifact `common`,
  version `1.3.3` in its `gradle.properties`). It is a **library, not a plugin**
  (its `plugin.xml` is a placeholder that the jar publish step strips).
- Plugin consumes it as an **included git build** — NOT the local path!
  `settings.gradle.kts:10-14` (plugin):
  ```kotlin
  gitRepositories {
      include("pycharm-plugin-base") {
          uri.set("https://github.com/szabope/pycharm-plugin-base.git")
          tag.set("latest")
      }
  }
  ```
  Plugin `gradle/libs.versions.toml:5,16`: `myPluginCommon = "latest"`,
  `myPluginCommon = { group = "works.szabope.plugins", name = "common", version.ref = "myPluginCommon" }`.
  → Local edits in `../pycharm-plugin-base` are **ignored by the plugin build** until
  the base is released and re-tagged. For iteration, temporarily switch the include to
  the local directory (includegit supports `include("../pycharm-plugin-base")`), and
  restore the git include before release.
- Base publishing: maven to GitHub Packages (`maven.pkg.github.com/szabope/pycharm-plugin-base`),
  and its `.github/workflows/release.yml` pushes to master → auto-tags HEAD with
  `<pluginVersion>` **and `latest`** (if the version tag is missing). So a base release =
  bump `pluginVersion` in base `gradle.properties` + push master.
- Plugin identity: `META-INF/plugin.xml` id `works.szabope.mypy`, optional hard dep
  `works.szabope.mypy-PythonCore.xml`; `gradle.properties`: `pluginVersion=2.2.4`,
  since/until build `262.8665.309`/`262.*` (platform `PY` 2026.2),
  `platformBundledPlugins=org.toml.lang,PythonCore`, Kotlin 2.4.10, IJ platform gradle 2.18.1,
  junit 4.13.2 + mockk 1.14.4.
  `build.gradle.kts` (since 2026-08-18): opt-in `-PlocalPlatformPath=<abs path>` builds
  against a locally installed IDE via `dependencies { intellijPlatform { local(path) } }`
  instead of `create("PY", "2026.2")` download (in IJ platform gradle plugin 2.18.1 the
  old `localPlatformPath` extension property is gone). Locally available:
  `/home/jirka642/bin/pycharm` (PyCharm 2026.2.1, build 262.9437.214).

## Base repo layout (package `works.szabope.plugins.common`)

### Execution (the process machinery)
- `run/ToolExecutor.kt` — `abstract class ToolExecutor(project: Project, moduleToRun: String)`;
  `fun execute(configuration: ToolExecutorConfiguration, parameters: List<String>): Flow<ProcessLine>`
  builds the handler at **lines ~27-31**:
  `if (configuration.useProjectSdk) pythonModuleProcessHandler(project, moduleToRun, parameters, workingDir = configuration.workingDirectory) else commandLineProcessHandler(configuration.executablePath, configuration.workingDirectory, parameters)`.
  Streams stdout/stderr lines into `Flow<ProcessLine(text, isError)>`; on termination with
  `isError(event)` (open, default `exitCode > 0`) closes with
  `ToolExecutionTerminatedException(exitCode)`. Exposes `@Volatile exitCode` and
  `@Volatile commandLine` (used by error dialogs). This is the file that gets an
  **open `createProcessHandler` hook** (plan §1.1).
- `run/PythonModuleExecutionStrategy.kt` — `pythonModuleProcessHandler(...)`: resolves SDK
  via `project.resolveModulePythonSdkNow()`, envs via `addDefaultEnvironments(sdk, envs)`,
  `GeneralCommandLine` with `withExePath(sdk.homePath)`, `withParameters("-m", moduleToRun)`,
  `withParameters(parameters)`, `withEnvironment(patchedEnvs)` → `ToolProcessHandler`.
  New sibling `run/PythonSdkToolExecutionStrategy.kt` mirrors this without `-m` (plan §1.2).
- `run/CommandLineExecutionStrategy.kt` — `commandLineProcessHandler(exePath, workingDir, parameters)`.
- `run/ToolProcessHandler.kt` — `OSProcessHandler` wrapper, line-splitting readers.
- `run/Exclusions.kt` — WorkspaceModel excluded-URL lookup (backs `excludeNonProjectFiles`).
- `util.kt` — `Project.resolveModulePythonSdk()` (suspend) / `resolveModulePythonSdkNow()`;
  singleton SDK or **null when multiple distinct SDKs** are configured across modules.

### Settings / configuration
- `services/Settings.kt` — `Settings` interface: `isAutoScrollToSource, scanBeforeCheckIn,
  excludeNonProjectFiles, workingDirectory, useProjectSdk, arguments, configFilePath,
  executablePath, isToolApplicable()`, `suspend initSettings(old)`,
  `suspend getValidConfiguration(): Result<ToolExecutorConfiguration>`. (No `tool` member —
  the tool is a plugin-side concept on `MypySettings`.)
- `services/AbstractToolSettings.kt` — `SimplePersistentStateComponent` base.
  `isToolSet()` (≈L64-70): SDK mode → SDK resolvable AND
  `getPackageManagementService().checkInstalledRequirement().isSuccess`; direct mode →
  `executablePath.isNotBlank()`. `getValidConfiguration` (≈L43-62) builds the
  `ToolExecutorConfiguration` snapshot from state. `initSettings` (≈L24-41) migrates legacy
  `BasicSettingsData`, forces `useProjectSdk=false` if legacy executable + no SDK,
  defaults workingDirectory to `project.guessProjectDir()`. **No base change needed** for
  zuban (the pip requirement switch happens in the plugin's package service).
- `services/ToolExecutorConfiguration.kt` — data class
  `(executablePath, useProjectSdk, configFilePath, arguments, workingDirectory,
  excludeNonProjectFiles, scanBeforeCheckIn)`. Unchanged.
- `services/BasicSettingsData.kt` / `SettingsData.kt` — legacy migration tiers.
- `services/AbstractPluginPackageManagementService.kt` — `getRequirement(): PyRequirement`
  (abstract), `checkInstalledRequirement()` (refreshes SDK packages via
  `PyPackageManager.refreshAndGetPackages` and matches the requirement),
  `installRequirementWithCallback`, `canInstallNow()`, `isLocalEnvironment()` (venv/conda),
  `isRemote()`, sealed `PluginPackageManagementException`
  (`PackageNotInstalledException`, `PackageVersionObsoleteException`).
- `services/IncompleteConfigurationNotifier.kt` — sticky "configuration incomplete"
  balloon with Open-Settings / Install actions.
- `services/SeverityConfig.kt` — `level, text, description, icon` (tree display).

### Validation
- `validator/AbstractToolValidator.kt` — abstract `versionFlag`, `packageName`,
  `getPackageManagementService()`; `validateExecutablePath` (exists/dir/canExecute);
  `validateVersion(path)` runs `<path> <versionFlag>` (5s `CapturingProcessHandler`),
  extracts version via regex `(\d+\.\d+\.\d+)` (L49) and checks
  `getRequirement().match(PyPackage(packageName, version))`; `validateProjectSdk()`.
  Fully parameterized → the plugin just adds a zuban-parameterized subclass (no base change).
- `validator/FileValidator.kt` — config-file path checks.
- `validator/ToolValidatorMessages.kt` — message bundle for validators.

### UI (settings dialog)
- `configurable/GeneralConfigurable.kt` — `ConfigurableConfiguration` data class (L39-53):
  `displayName, helpTopic, id, installActionId, installButtonText, pickerTitle,
  pickerDirectOptionTitle, pickerDirectOptionFileFilter (FileFilter),
  pickerDirectOptionEmptyWarning, pickerDirectOptionVersionCheckProgressTitle,
  pickerSdkOptionTitle, configFilePickerRowComment, argumentsDescription`.
  `createPanel()` (≈L94-107) rows: `toolPicker(); configFilePicker(); argumentsField();
  workingDirectoryPicker(); excludeNonProjectFilesCheckbox()` inside an indented panel.
  `toolPicker()` (≈L156-205): buttonsGroup (radio **direct executable** with
  `TextFieldWithBrowseButton` + `FileFilter` by file **name**; radio **Use project SDK**
  with SDK name label + **Install button** wired to `config.installActionId`), bound to
  `settings.useProjectSdk` (L205); row comments for multiple-SDKs / remote SDK / system-wide
  install warning. `apply()` (≈L109-125) validates executable on a pooled thread under
  modal progress + `validateSdk()` (modal for local SDKs) before `super.apply()`.
  `FileFilter(fileNames: List<String>)` (L127-131) — matches by exact file name.
  **This file gets the optional tool-selector row + dynamic text hooks (plan §1.3).**
- `activity/AbstractSettingsInitializationActivity.kt` — ProjectActivity:
  `settings.initSettings(oldSettings)` then shows incomplete-config bubble if invalid.
- `dialog/IDialogManager.kt` / `AbstractDialogManager.kt` / `PluginErrorDialog.kt` —
  `createToolExecutionErrorDialog(commandLine, result, resultCode)`,
  `createToolOutputParseErrorDialog(commandLine, targets, json, error)` (note the `json`
  param — JSON tools in mind), install + general error dialogs.
- `action/*` — `AbstractScanAction` (see below), `AbstractStopScanAction`,
  `AbstractInstallToolAction` (pip-installs the requirement via package service),
  `AbstractOpenSettingsAction`, `AbstractScanJobRegistry` (single active scan Job),
  `ScrollToSourceDummyAction`.
- `toolWindow/*` — `AbstractToolWindowFactory`, `AbstractToolWindowPanel`
  (`uiDataSnapshot` L42-50: **`VfsUtil.findFile(Path(userObject.file), true)` — assumes
  `issue.file` is an ABSOLUTE path**; zuban relative paths are fixed by resolving at parse
  time, no base change), `ITreeService`/`AbstractTreeService` (add/lock; add throws when
  locked), `ToolWindowTreeModel` (RootNode/StringNode/IssueNode + `IssueNodeUserObject:
  NavigationItem`), `TreeModelManager` (issues set, severity filtering, per-file grouping
  by the file string, root "Found N issue(s) in M file(s)"), `TreeModelDataItem`
  (`file: String, line, column, message, code, severity, toRepresentation()`).
- `annotator/ToolAnnotator.kt` — `abstract class ToolAnnotator<T : ToolMessage> :
  ExternalAnnotator<AnnotatorInfo, List<T>>(), DumbAware`. `collectInformation` (L31-35)
  skips `LightVirtualFile`; **`doAnnotate` (L37-42):
  `runBlocking { getSettings(project).getValidConfiguration() }.getOrNull() ?: return emptyList()`
  then `scan(info, configuration)` — it is OPEN (Kotlin), the plan overrides it in
  `MypyAnnotator` for the dirty-guard**; `apply` (L44-55) maps messages → PSI via
  `findElementFor` (`DocumentUtil.calculateOffset(fileDocument, issue.line, issue.column,
  tabSize)` + `findElementAt`), annotation severity from the paired inspection profile
  (default Error), attaches `createIntention(issue)`; `getPairedBatchInspectionShortName()`
  returns `inspectionId`.
- `annotator/ToolMessage.kt` — `message, line, column` contract.
- `messages/MessageConverter.kt` — `MessageConverter<in S, out T>` single-method interface.

### Test fixtures (published `testFixtures` jar)
`AbstractPluginTestCase`, `AbstractPluginHeavyPlatformTestCase`, action/dataContext
utilities, `PythonMockSdk` (mock Sdk with `homePath = <sdkPath>/bin/python`),
`AbstractPluginPackageManagementServiceStub` (in-memory installed packages **per SDK** —
extend for a `zuban` package), headless test dialog manager + balloon manager.

## Plugin repo layout (package `works.szabope.plugins.mypy`)

### Core mypy specifics (all touched by the plan)
- `MypyArgs.kt:4` —
  `val MANDATORY_ARGS = listOf("--show-column-numbers", "--show-absolute-path", "--output", "json")`
  → becomes the MYPY entry of a per-tool table.
- `services/MypyExecutor.kt` — `class MypyExecutor(project) : ToolExecutor(project, "mypy")`;
  `isError = exitCode > 2` (mypy exits 1 on errors, 2 on usage). Gains a `tool` param +
  `createProcessHandler` override for ZUBAN+SDK.
- `services/MypySettings.kt` — `@State(name="MypySettings", storages=[Storage("MypyPlugin.xml")])`;
  `MypyState { mypyExecutable, useProjectSdk=true, configFilePath, arguments,
  autoScrollToSource=false, excludeNonProjectFiles=true, projectDirectory,
  scanBeforeCheckIn=false }`. Gains `tool by string("mypy")` + `val tool: MypyTool`.
- `services/OldMypySettings.kt` — legacy `MypyConfigService`/`mypy.xml` migration
  (`customMypyPath`, `mypyConfigFilePath`, `mypyArguments`). Legacy settings are mypy's →
  tool stays default `mypy`; no change.
- `services/mypyParamListBuilders.kt` —
  - `buildMypyParamList(configuration, shadowMap: Map<VirtualFile, Path>)` (L18-24):
    emits `--shadow-file <orig> <temp>` per entry (mypy-mode real-time trick).
  - `buildMypyParamList(configuration, targets: Collection<VirtualFile>, extraArgs)` (L26-40):
    order = `MANDATORY_ARGS` → `--config-file <p>` → user `ParametersListUtil.parse(arguments)` →
    `--exclude <relPath>` (per excluded dir, relativized vs content root, L42-50, because
    mypy's `--exclude` doesn't accept absolute paths) → extraArgs → target canonical paths.
  Gains tool-aware mandatory args + a direct-path (non-shadow) sync variant for zuban.
- `services/SyncScanService.kt` — real-time path. `scan(targets, configuration)`:
  `copyTempFrom` (L75-83: temp file `pycharm_mypy_<rand>_<name>` in system temp,
  `deleteOnExit`, contents = unsaved document text) → shadow param list →
  `MypyExecutor(project).execute` → JSON parse per stdout line → map `message.file`
  (absolute) via `targetsByPath` (keyed by original `canonicalPath`, L32) fallback
  `VfsUtil.findFile(Path(...), false)` (L43) → cleanup temps + stderr warn
  (L60-65) → `silent = true` error handling → `Map<VirtualFile, List<MypyMessage>>`
  (fold, L67-72). Zuban: skip temp copy, use direct builder, resolve CWD-relative
  `message.file` against `configuration.workingDirectory`.
- `services/AsyncScanService.kt` — tool-window path. Builds param list for on-disk targets
  (L23), parses JSON per line (L32); **non-JSON stdout lines are appended to
  `nonJsonStdout` (L34-37)** and, if anything accumulated (or stderr), a clickable balloon
  "Mypy has thrown an error — Details" opens the execution-error dialog (L44-56).
  `handleScanException` (L57). `handleScanException.kt` — shared exception handling,
  `silent` flag distinguishes sync/async.
- `services/parser/MypyOutputParser.kt` — `parse(json): Result<MypyMessage>`;
  `MypyParseException` (mypy mixed its own exceptions into stdout);
  `adjustForPlatform` = `line - 1`, `severity.uppercase()` (L38-41). The new text parser
  reuses `adjustForPlatform`.
- `services/parser/MypyMessage.kt` — `@Serializable data class MypyMessage(file, line,
  column, message, hint=null, code, severity) : ToolMessage`. **Serves both tools** (text
  parser fills the same shape).
- `services/parser/MypyMessageConverter.kt` — message → `MypyTreeModelDataItem`;
  unknown severity → throws with "report this issue" text.
- `services/mypySeverityConfigs.kt` — map `"ERROR"/"WARNING"/"NOTE"` → `SeverityConfig`
  (Error/Warning/Information icons). Unchanged.
- `services/MypyPluginPackageManagementService.kt` —
  `pyRequirement("mypy", GTE, "1.11")`, `MINIMUM_VERSION = "1.11"`. Gains tool branching
  (`"zuban", GTE, "0.9"`).
- `services/MypyIncompleteConfigurationNotifier.kt` — incomplete-config balloons (mypy-branded strings).

### Annotator/inspection
- `annotator/MypyAnnotator.kt` — `class MypyAnnotator : ToolAnnotator<MypyMessage>()`;
  `inspectionId = MypyInspectionId`; `scan = SyncScanService.scan([info.file],
  configuration)[info.file] ?: emptyList()`; `createIntention = MypyIgnoreIntention`.
  **Gets the ZUBAN dirty-guard override of `doAnnotate` + cache population (plan §2.7).**
- `annotator/MypyInspection.kt` — `MypyInspectionId = "MypyInspection"`;
  `PyInspection + ExternalAnnotatorBatchInspection` (shortName pairs with the annotator so
  the user can disable/tune it in Inspections settings; enabled by default).
- `annotator/MypyIgnoreIntention.kt` — "Suppress mypy [code]" quick fix: inserts/extends
  `# type: ignore[codes]` on the offending line; regex
  `^#\s+type:\s+ignore(\[(?<codes>[a-zA-Z\s,-]+)])?` (L24); unavailable in multiline
  strings. Works unchanged for zuban (type: ignore is supported by zuban).

### Configuration UI / validation
- `configurable/MypyConfigurable.kt` — `GeneralConfigurable` with
  `ConfigurableConfiguration(...)`; executable `FileFilter` =
  Windows: `mypy.exe, mypyc.exe, mypy.bat` else `mypy, mypyc` (L25-31) → combined list for
  both tools; `validateExecutable(path)` =
  `MypyValidator(project).validateExecutablePath(path) ?: validateVersion(path)` (L42-46);
  `validateLocalSdk()` = `MypyValidator.validateProjectSdk()` (L48); `ID = "Settings.Mypy"`.
  Registration: `works.szabope.mypy-PythonCore.xml` → `projectConfigurable` id
  `...MypyConfigurable`, parentId `tools`, key `mypy.configuration.name`.
- `configurable/MypyValidator.kt` — `versionFlag = "-V"`, `packageName = "mypy"`,
  `ToolValidatorMessages` from bundle keys. Gains a tool parameter.

### Actions / tool window / dialogs / activity
- `action/ScanAction.kt` ("Scan with Mypy": EditorPopupMenu, EditorTabPopupMenu,
  ProjectViewPopupMenu, ChangesViewPopupMenu) — extends base `AbstractScanAction` with
  `scanAndAdd` = `AsyncScanService.scan` + `MypyMessageConverter` + tree add.
  Note base `AbstractScanAction.actionPerformed` (L42-60): `treeService.reinitialize(targets)`
  → **`saveAllDocuments()` (L48!)** → coroutine on `Dispatchers.IO` → valid config (else
  incomplete-config bubble) → `scanAndAdd` → `treeService.lock()`.
  The `saveAllDocuments()` in manual scans is why manual scans never see unsaved content —
  and why the planned save-listener also refreshes editor underlines after a manual scan.
- `action/RescanAction` ("Rescan Latest"), `ScanCurrentlyFocusedOneInEditorAction`
  ("Scan Editor"), `StopScanAction`, `OpenSettingsAction`, `InstallMypyAction`,
  `MypyScanJobRegistryService` — all thin, unchanged (maybe parameterized strings).
- `toolWindow/MypyToolWindowPanel.kt` — `ID = "Mypy "`, panel wiring;
  `MypyTreeModelDataItem.kt` — `toRepresentation() = "$message [$code] ($line:$column)
  ${hint...}"`; `MypyTreeService`, `MypyToolWindowFactory` — unchanged.
- `dialog/DialogManager.kt`, `MypyErrorDialog.kt` — execution/parse/install/general error
  dialogs (base `AbstractDialogManager` + `PluginErrorDialog`); strings get tool
  parameterization where cheap.
- `activity/SettingsInitializationActivity.kt` — extends base
  `AbstractSettingsInitializationActivity` (migration + incomplete-config bubble on open).
  **New sibling activity for the save listener (plan §2.7).**
- `MypyBundle.kt` → `resources/messages/MypyBundle.properties` (64 strings: config labels,
  action texts, dialog titles, notifications, `mypy.configuration.tool_not_set`).
- `resources/META-INF/plugin.xml` (id `works.szabope.mypy`, name "Mypy") and
  `works.szabope.mypy-PythonCore.xml` (all registrations: toolWindow id `"Mypy "`,
  configurable, `externalAnnotator` `MypyAnnotator`, `localInspection`
  shortName `MypyInspection` group "Mypy" enabled by default, notificationGroup, action group
  `works.szabope.plugins.mypy.MypyPluginActions`, actions + icons,
  **add `<projectActivity>` here for the save listener**).
- `resources/inspectionDescriptions/MypyInspection.html` — inspection help text.

### Tests
- Framework: junit4 + mockk over the base testFixtures; base classes
  `AbstractMypyTestCase` (light) / `AbstractMypyHeavyPlatformTestCase`; heavy tests are the
  norm (real services, mock executables).
- `testData/action/scan_cli/mypy` (+ `mypy_exit_with_1/_2/_2_and_stderr/_3`,
  `mypy_non_json_output`) — **mock `mypy` bash scripts**: on invocation they assert the
  **exact expected argument list** (e.g. `--show-column-numbers --show-absolute-path
  --output json --exclude excluded_dir <last>`) and print a canned JSON line(s)
  (`manualScan.json`), or stderr/exit variants. Zuban tests mirror this with a mock
  `zmypy` asserting `--show-column-numbers ...` (no `--output json`,
  no `--show-absolute-path`) and printing text lines.
- `testData/action/scan_sdk/MockSdk/bin/python` — mock SDK python answering `-m mypy ...`;
  a zuban SDK test needs `MockSdk/bin/zmypy` (bash; `-V` → `zuban 0.9.0`; on scan, assert
  args + emit text) — the base `PythonMockSdk` fixture gives `<sdk>/bin/python` so the
  planned base helper resolves `<sdk>/bin/zmypy` next to it.
- `testData/action/rescan/{mypy,mypy2,results.json}`, `testData/action/stop_scan/mypy`
  (infinite loop for Stop test), `testData/annotation/*` (mock `mypy` honoring
  `--shadow-file`, `mypy_*.py_result.json`, exit-3 / non-json variants,
  `white space/mypy` for paths-with-spaces + `mypy.ini`), `testData/initialization/*`
  (legacy `.idea/mypy.xml`, local/remote SDK fixtures).
- Suites: `action/ScanCliTest` (exit codes 0/1/2/3, non-JSON dialog, incomplete-config
  dedup), `ScanSdkTest` (SDK mode + install), `RescanTest`, `StopScanTest`,
  `annotator/AnnotatorTest` (shadow-file flow, in-memory files, paths with spaces,
  non-json, exit 3), `annotator/MypyIgnoreIntention` (`IntentionTest`),
  `initialization/*` (legacy migration → `$PROJECT_DIR$/.venv/bin/mypy`, local/remote SDK
  bubbles, from-scratch defaults). **All must keep passing** — one pre-existing exception
  (fixed 2026-08-18, unrelated to zuban): in PyCharm 2026.2.1 (262.9437.214) the
  no-arg constructor of `com.jetbrains.python.sdk.PythonSdkAdditionalData()` is now
  **private**, which broke
  `initialization/MypyInitializationWithRemotePythonSdkTest` (it subclassed with `()`).
  It now extends the public flavor constructor:
  `PythonSdkAdditionalData(PyFlavorAndData(PyFlavorData.Empty, PythonSdkFlavor.UnknownFlavor.INSTANCE))`.
  The `isRemote()` check it relies on is just
  `sdk.sdkAdditionalData is PyRemoteSdkAdditionalDataMarker` (verified in the 262.9437.214
  jars). The download path (`create("PY","2026.2")`) resolves to the latest 2026.2.x, so
  this fix is likely needed for CI too.
- CI: `.github/workflows/` in both repos (base: release-on-master with auto-tag
  `latest`; plugin: standard IntelliJ platform Gradle build/test).
