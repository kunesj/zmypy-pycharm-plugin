package works.kunesj.plugins.zmypy.services.parser

sealed interface ZubanParseResult {
    /** A parsed issue. `message.file` is still as printed (possibly CWD-relative) — the scan services resolve it. */
    data class Issue(val message: MypyMessage) : ZubanParseResult
    /** Harmless output lines ("Found N errors...", "Success: no issues found in ...", bare counts). */
    data object Summary : ZubanParseResult
    /** Lines that are neither issues nor summaries — suspicious, callers surface them. */
    data class Junk(val raw: String) : ZubanParseResult
    /** Recognized but not worth reporting (e.g. old-mypy-style indented note continuations). */
    data object Ignored : ZubanParseResult
}

/**
 * Stateful parser for zmypy (zuban mypy-mode) text output. One instance per execution:
 * it remembers the last issue so that indented note continuations (old-mypy style, not
 * emitted by zuban 0.9.0) can be attached to it.
 *
 * Line grammar (with --show-column-numbers, which the plugin always passes for zuban):
 *   <path>:<line>[:<col>]: <severity>: <message>  [code]
 * where <path> is CWD-relative, <line> is 1-based, <col> is 1-based and <code> is
 * separated by two spaces (optional).
 */
class ZubanTextOutputParser {

    private var lastIssue: MypyMessage? = null

    fun parse(rawLine: String): ZubanParseResult {
        // process lines arrive with a trailing newline; trim it (keep leading spaces for note detection)
        val line = rawLine.trimEnd()
        if (line.isBlank()) return ZubanParseResult.Ignored

        issueLineRegex.find(line)?.let { match ->
            val groups = match.groups
            // all of these groups must participate in a match
            val file = groups["file"]!!.value
            val parsedLine = groups["line"]!!.value.toInt()
            val parsedColumn = groups["col"]?.value?.toIntOrNull()
            val severity = groups["sev"]!!.value
            val message = groups["msg"]!!.value
            val code = groups["code"]?.value ?: ""
            val issue = MypyMessage(
                file = file,
                line = parsedLine, // 1-based, adjusted later
                column = (parsedColumn ?: 1) - 1, // 0-based to match mypy's JSON contract
                message = message,
                code = code,
                severity = severity.lowercase()
            )
            lastIssue = issue
            return ZubanParseResult.Issue(adjustForPlatform(issue))
        }

        // indented "    note: ..." continuation (old mypy text style); attach to the previous issue
        indentedNoteRegex.find(line)?.let { match ->
            val previous = lastIssue ?: return ZubanParseResult.Junk(line)
            val note = match.groupValues[1]
            lastIssue = previous.copy(hint = (previous.hint?.let { "$it\n$note" } ?: note))
            return ZubanParseResult.Ignored
        }

        return if (line.matches(summaryRegex)) {
            ZubanParseResult.Summary
        } else {
            ZubanParseResult.Junk(line)
        }
    }

    companion object {
        private val issueLineRegex =
            "^(?<file>.+?):(?<line>\\d+)(?::(?<col>\\d+))?:\\s+(?<sev>error|warning|note):\\s+(?<msg>.*?)(?:\\s{2}\\[(?<code>[A-Za-z0-9_-]+)\\])?\\s*$"
                .toRegex()
        private val indentedNoteRegex = "^\\s+note:\\s+(.*)$".toRegex()
        private val summaryRegex = "^(Found .*|Success: .*|\\d+)$".toRegex()
    }
}
