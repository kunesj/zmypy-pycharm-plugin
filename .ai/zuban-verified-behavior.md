# zuban / zmypy — verified behavior (0.9.0, checked 2026-08-17)

All facts below were verified by running the real binary unless marked
"inference". Reference install:

```
V=/home/jirka642/PycharmProjects/mv-cr/be-api/.venv/bin   # uv venv, has mypy 1.x + zuban 0.9.0
$V/zmypy -V        # -> "zuban 0.9.0"
$V/mypy -V         # mypy is in the same venv (useful for A/B format comparisons)
```

## Identity & distribution

- **zuban** = Python type checker + language server written in Rust by David Halter
  (author of Jedi/parso). Repo: `github.com/zubanls/zuban`; docs: `docs.zubanls.com`;
  site: `zubanls.com`. Open source under AGPL-3.0 (+ paid license option).
  ~20–200x faster than mypy (their claim; irrelevant to the plugin, but explains why
  per-save checks are cheap).
- Two executables: `zuban` (main; subcommands `check`, `mypy`, LSP server) and
  `zmypy` (**alias of `zuban mypy`** = the mypy-compatible drop-in we use).
- **pip package name is `zuban`** (PyPI: https://pypi.org/project/zuban/, 0.9.1 at check
  time; user's venv has 0.9.0). The wheel is `py3-none-<platform>` — pure binary:
  - `zuban/__init__.py` (93 bytes) contains
    `raise Exception("You should be using the zuban executable instead of import this in Python")`
    → **`python -m zuban` is impossible by design.** Therefore SDK mode cannot reuse the
    current `python -m <module>` strategy; it must run the venv's `zmypy` script (agreed
    decision #3).
  - `zuban-*.data/scripts/zmypy` = 4.5 MB ELF (mypy mode) and
    `zuban-*.data/scripts/zuban` = 104 MB ELF (main binary), shipped as raw files in
    `.data/scripts` (no entry_points). On install both land in the environment's bin dir
    (`<venv>/bin/` on POSIX, `<venv>\Scripts\` on Windows → `zmypy.exe`).
  - The wheel also bundles typeshed + django stubs under `zuban/third_party/`
    (`--custom-typeshed-dir` exists as an override).
- `zmypy` **requires the `zuban` binary in the same directory**: without it, it panics
  with `failed to execute zuban: Os { code: 2 ... }` (exit 101), even on `-V`.
  Real pip/uv installs always place both together, so this only matters for manually
  copied binaries. Consequence: version validation of an install broken this way will
  report "Unknown version" (empty stdout) — acceptable.

## Version command

- `zmypy -V` → `zuban 0.9.0`  (note: says **zuban**, not mypy/zmypy).
- The base library's version regex `(\d+\.\d+\.\d+)` (`AbstractToolValidator.kt:49`)
  extracts `0.9.0` fine. Keep `versionFlag = "-V"` for both tools (mypy uses `-V` too).
- `--version` also works (untested; `-V` is the one the plugin uses).

## Flag support (verified against 0.9.0)

Supported (plugin-relevant first):

| Flag | Notes |
|---|---|
| `--show-column-numbers` | YES — use it (mandatory arg for zuban mode) |
| `--config-file` | YES — **mypy.ini format verified** (`[mypy] disallow_untyped_defs = True` was honored). Docs say mypy config + `[tool.zuban]`/`[tool.mypy]` in pyproject all work. |
| `--exclude` | YES, repeatable, regex — same as mypy; keep the plugin's "relative regex only" handling for both tools. |
| `--python-executable`, `--python-version`, `--platform`, `--always-true/false` | YES (help) |
| `--ignore-missing-imports`, `--follow-untyped-imports` | YES (help) |
| strictness family (`--strict`, `--disallow-*`, `--check-untyped-defs`, `--warn-*`, `--strict-equality`, `--extra-checks`, ...) | YES (help); note this build has NO `--warn-unused-ignores` (clap offered suggestions) |
| `--enable-error-code` / `--disable-error-code` | YES (help) |
| `--custom-typeshed-dir` | YES (help) |
| `--show-error-end`, `--show-error-codes`/`--hide-error-codes`, `--pretty`/`--no-pretty`, `--no-error-summary` | YES (help) |
| `-m/--module`, `-p/--package`, `[files...]` | YES (help) |

**NOT supported** (verified: "error: unexpected argument ... found" on **stderr**, **exit 2**):

| Flag | Impact on the plugin |
|---|---|
| `-O/--output json` (any `--output`) | **No JSON output → must parse human-readable text.** This is the core new parser. |
| `--show-absolute-path` | **Output paths are CWD-relative** — even when targets are passed as absolute paths, or for files outside CWD (`../otherdir/bad.py`). Plugin must resolve them against the working directory (process CWD == `configuration.workingDirectory`). |
| `--shadow-file SOURCE SHADOW` | **Real-time/unsaved-content trick used by mypy mode is unavailable.** Resolved by the agreed "check on save" design (document is clean ⇒ on-disk file is checked directly ⇒ no shadow needed). |
| `-v/--verbose`, `--no-incremental`, `--cache-dir`, `--install-types`, `--no-site-packages`, `--num-workers`, report/junit flags | No plugin impact (never used). |

If a user carries mypy-only flags into the free-form Arguments field and switches to
zuban, zmypy exits 2 with the clap error on stderr → the plugin's existing stderr
handling surfaces it in the "…has thrown an error" balloon. Visible, not silent — good
enough; document it in the README.

## Exit codes (same convention as mypy)

| Code | Meaning | Plugin handling today (`MypyExecutor.isError`: `exitCode > 2`) |
|---|---|---|
| 0 | clean | OK |
| 1 | type errors found | OK (not an error) |
| 2 | usage/CLI error (clap) | OK flow-wise; stderr is captured → error balloon. Verified: unknown flags, bad invocations. |
| 3 | internal error (mypy convention; unverified for zuban, but `>2` policy covers it) | error |
| 101 | Rust panic (e.g. `zmypy` without sibling `zuban`, or missing Python env) | error → balloon with stderr (panic text). Acceptable. |

## Output grammar (text mode)

Everything type-check related goes to **stdout** (issues AND the summary). **stderr** is
used only for usage errors/panics (verified by redirection).

Issue line (with `--show-column-numbers`):

```
<path>:<line>[:<col>]: <severity>: <message>  [code]
```

- `<path>` — **relative to the process CWD** (see above).
- `<line>` — 1-based.
- `<col>` — **1-based when printed** (present only with `--show-column-numbers`; the
  plugin always passes it for zuban).
- `<severity>` — `error` | `warning` | `note` (lowercase, exactly like mypy). The plugin's
  `mypySeverityConfigs` key is the **uppercased** value; the existing
  `MypyOutputParser.adjustForPlatform` uppercases it, so the new parser keeps it
  lowercase pre-adjustment.
- `<code>` — bracketed at the end, separated by **two spaces**, e.g. `  [assignment]`.
  Not every line carries a code → the parser must make the bracket group optional
  (use `""` in `MypyMessage.code`, a non-null String).

Sample (exact, from the real binary):

```
bad.py:1:10: error: Incompatible types in assignment (expression has type "str", variable has type "int")  [assignment]
opt.py:1:16: error: Incompatible default for parameter "x" (default has type "None", parameter has type "int")  [assignment]
opt.py:1:16: note: PEP 484 prohibits implicit Optional. Accordingly, mypy has changed its default to no_implicit_optional=True
opt.py:1:16: note: Use https://github.com/hauntsaninja/no_implicit_optional to automatically upgrade your codebase
opt.py:4:5: error: Incompatible types in assignment (expression has type "None", variable has type "int")  [assignment]
Found 2 errors in 1 file (checked 1 source file)
```

Non-issue stdout lines (must NOT be treated as parse errors; today's mypy-JSON mode
balloons on any non-JSON stdout line — the text mode needs an explicit "harmless" set):

```
Found 1 error in 1 file (checked 1 source file)
Found 2 errors in 2 files (checked 3 source files)
Success: no issues found in 1 source file        # exit 0
```

Notes: **current mypy (≥ the version in the reference venv) also prints prefixed
`file:line:col: note:` lines**, but older mypy used indented `    note: ...` without a
position. The text parser is only used for zuban, but defensively treat an indented
`note:` line as a continuation (append to previous message's `hint`) so a future mypy
text-mode experiment wouldn't break.

## Column/line semantics — CRITICAL for correct offsets

Verified A/B on the same error (`x: int = "hello"` — the bad literal starts at 0-based
offset 9 of line 1):

| Source | Output |
|---|---|
| mypy `-O json` | `"line": 1, "column": 9` → **line 1-based, column 0-based** |
| mypy text `--show-column-numbers` | `bad.py:1:10: error:` → **column 1-based** |
| zmypy text `--show-column-numbers` | `bad.py:1:10: error:` → **column 1-based** (same as mypy text) |

Existing pipeline: `MypyOutputParser.adjustForPlatform` does `line = line - 1` and
`severity = uppercase()`, and **leaves `column` untouched** — i.e. `MypyMessage` is
expected at that point to carry **line 1-based, column 0-based**, matching mypy JSON.
Downstream `DocumentUtil.calculateOffset(doc, line, column, tabSize)`
(`ToolAnnotator.findElementFor`, base `ToolAnnotator.kt:61-65`) then gets a 0-based
pair, consistent with IntelliJ's 0-based line/column convention.

⇒ **The new text parser must emit `line = parsedLine` (as-is, 1-based) and
`column = parsedColumn - 1` (0-based; use 0 when the line has no column so that
`calculateOffset` stays sane), lowercase severity, and then run through the same shared
`adjustForPlatform`.** Doing anything else shifts annotation offsets by one.

Newer mypy JSON also includes `end_line`/`end_column` — already ignored
(`ignoreUnknownKeys = true` in `MypyOutputParser`).

## Configuration files

- `zmypy --config-file /path/mypy.ini` with a `[mypy]` section: **verified working**
  (test: `disallow_untyped_defs` from the ini produced `no-untyped-def` errors exactly
  like the CLI flag).
- zuban docs: `[tool.mypy]` in pyproject.toml is picked up (mypy mode); `[tool.zuban]`
  adds its own options (`mode`, `untyped_strict_optional`, `untyped_function_return_mode`,
  `check_untyped_defs`, `mypy_path`, ...). Env vars: `ZUBAN_TYPESHED`, `ZUBAN_LOG`,
  `ZUBAN_LOG_FILE`.
- The plugin's config-file setting (`--config-file <path>`) works unchanged for both
  tools.

## "Check on save" platform API (verified from JetBrains/intellij-community master)

- `FileDocumentManagerListener.TOPIC` (application message bus) —
  `onDocumentSaved(Document)` fires for every save (Ctrl+S, `saveAllDocuments()`,
  save-on-close/exit). Source:
  `platform/platform-impl/src/com/intellij/openapi/fileEditor/impl/FileDocumentManagerImpl.java`.
- `DaemonCodeAnalyzer.restart(@NotNull PsiFile, @NotNull Object reason)` — **public API**
  (interface `platform/analysis-api/src/com/intellij/codeInsight/daemon/DaemonCodeAnalyzer.java`);
  implementation (`platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/DaemonCodeAnalyzerImpl.java`)
  does `myFileStatusMap.markWholeFileScopeDirty(document, reason); stopProcess(true, reason);`
  → the whole highlighting pipeline (including external annotators) re-runs for that file
  **even without text changes**. This is what the platform itself calls from
  `settingsChanged()`. No internal API needed.
- The plugin's annotator is an `ExternalAnnotator` (base `ToolAnnotator`); it already
  runs on file open; the only missing trigger is post-save, which `restart` provides.

## Reproduction snippets

```bash
V=/home/jirka642/PycharmProjects/mv-cr/be-api/.venv/bin; W=/tmp/zbtest; mkdir -p $W && cd $W
printf 'x: int = "hello"\n' > bad.py
"$V/zmypy" bad.py                                   # default text, exit 1
"$V/zmypy" --show-column-numbers bad.py             # with column
"$V/zmypy" --show-absolute-path bad.py              # -> clap error, exit 2 (stderr)
"$V/zmypy" --output json bad.py                     # -> clap error, exit 2 (stderr)
"$V/zmypy" --shadow-file a b bad.py                 # -> clap error, exit 2 (stderr)
"$V/zmypy" /abs/path/bad.py                         # still printed CWD-relative!
"$V/zmypy" -V                                       # "zuban 0.9.0"
printf '[mypy]\ndisallow_untyped_defs = True\n' > m.ini
printf 'def f(x):\n    return x\n' > u.py
"$V/zmypy" --config-file m.ini u.py                 # no-untyped-def error
$V/mypy -O json bad.py                              # A/B: JSON column is 0-based ("column": 9)
```

Note: some sandbox contexts make zuban panic about a missing Python env lib dir
("The Python environment lib folder ... should be readable ... set ZUBAN_TYPESHED").
The reference venv (`$V`) works out of the box; if you copy `zmypy` elsewhere, keep the
sibling `zuban` next to it and run against a real venv.

## Mirror mode (verified 2026-08-25, zuban 0.9.0)

Facts verified against the real binary while designing the temp-dir mirror used for
real-time (unsaved) annotation in v2.4.0. Setup: a source tree, and a mirror with **real
directories + per-file symlinks** (or hard links) into it, CWD = the mirror root.

- **Symlinked files are read through**: a target whose package (`pkg/__init__.py`,
  `pkg/mod.py`) and modules are symlinks imports and type-checks exactly like the real tree
  (no spurious `import-not-found`).
- **Hard links behave identically** to symlinks (they are the same file).
- **CWD-relative output holds inside the mirror**: even when the target is passed as an
  absolute path under the mirror root, issues are printed relative to CWD (e.g.
  `src/pkg/mod.py:1:14: error: ...`), so mirror paths map 1:1 back to real paths.
- **A dirty file replaced by a real (non-link) copy in the mirror is checked with the
  copy's content** — the on-disk original is untouched. This is the whole point of the
  mirror (emulates `--shadow-file`).
- **A broken symlink that is not imported does not panic** the run.
- **Errors are reported only for the TARGETED files, not the whole import graph**
  (a typed error in an imported-but-not-targeted module is NOT reported). Simpler than
  mypy, which reports imported modules too. Consequence: per-file real-time annotation
  never returns issues for other files.
- **Missing target inside the mirror** → `No Python files found to check for <path>` on
  stdout, **exit 2** (same treatment as a usage error: captured, surfaced, not a crash).
- **Performance is not a bottleneck**: on a 373-`.py`/1537-file project (45 MB, venv and
  `.git` excluded), building a full per-file symlink mirror is sub-second, a single-file
  check through the mirror takes ~0.13–0.27 s (same as on the real FS), and a 75-file
  check takes ~1.5 s.
- **Windows caveat (not testable here, by design)**: `Files.createSymbolicLink` requires
  developer mode/admin; the plugin falls back to a hard link when both paths share a volume
  and to a plain copy otherwise.
