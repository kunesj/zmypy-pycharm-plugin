package works.kunesj.plugins.zmypy.services.parser

import junit.framework.TestCase

class ZubanTextOutputParserTest : TestCase() {

    private fun parse(line: String): ZubanParseResult = ZubanTextOutputParser().parse(line)

    private fun parseAll(vararg lines: String): List<ZubanParseResult> =
        lines.map { ZubanTextOutputParser().parse(it) }

    fun `test error with column and code`() {
        val result = parse(
            """bad.py:1:10: error: Incompatible types in assignment (expression has type "str", variable has type "int")  [assignment]"""
        )
        val issue = result as ZubanParseResult.Issue
        assertEquals("bad.py", issue.message.file)
        assertEquals(0, issue.message.line) // 1-based in output -> 0-based after adjustForPlatform
        assertEquals(9, issue.message.column) // 1-based in output -> 0-based
        assertEquals("ERROR", issue.message.severity)
        assertEquals("assignment", issue.message.code)
        assertEquals(
            """Incompatible types in assignment (expression has type "str", variable has type "int")""",
            issue.message.message
        )
        assertNull(issue.message.hint)
    }

    fun `test error without column`() {
        val result = parse("bad.py:1: error: Some message  [some-code]") as ZubanParseResult.Issue
        assertEquals("bad.py", result.message.file)
        assertEquals(0, result.message.line)
        assertEquals(0, result.message.column) // no column -> 0
        assertEquals("ERROR", result.message.severity)
        assertEquals("some-code", result.message.code)
        assertEquals("Some message", result.message.message)
    }

    fun `test error without code`() {
        val result = parse("bad.py:3:2: warning: A warning message") as ZubanParseResult.Issue
        assertEquals("WARNING", result.message.severity)
        assertEquals("", result.message.code)
        assertEquals(2, result.message.line)
        assertEquals(1, result.message.column)
    }

    fun `test prefixed note is a separate note issue`() {
        val results = parseAll(
            "opt.py:1:16: error: Incompatible default for parameter \"x\"  [assignment]",
            "opt.py:1:16: note: PEP 484 prohibits implicit Optional."
        )
        assertEquals(2, results.size)
        val error = results[0] as ZubanParseResult.Issue
        assertEquals("ERROR", error.message.severity)
        val note = results[1] as ZubanParseResult.Issue
        assertEquals("NOTE", note.message.severity)
        assertEquals("opt.py", note.message.file)
        assertEquals("PEP 484 prohibits implicit Optional.", note.message.message)
    }

    fun `test relative path with parent directory`() {
        val result = parse("../otherdir/bad.py:2:5: error: x  [y]") as ZubanParseResult.Issue
        assertEquals("../otherdir/bad.py", result.message.file)
        assertEquals(1, result.message.line)
        assertEquals(4, result.message.column)
        assertEquals("y", result.message.code)
    }

    fun `test path with spaces`() {
        val result = parse("white space/foo.py:1: error: msg  [code]") as ZubanParseResult.Issue
        assertEquals("white space/foo.py", result.message.file)
        assertEquals("msg", result.message.message)
        assertEquals("code", result.message.code)
    }

    fun `test indented note continuation is ignored and attached to previous issue`() {
        val parser = ZubanTextOutputParser()
        val first = parser.parse("bad.py:1:10: error: An error  [assignment]")
        val continuation = parser.parse("    note: an indented note")
        assertTrue(first is ZubanParseResult.Issue)
        assertEquals(ZubanParseResult.Ignored, continuation)
    }

    fun `test indented note without a previous issue is junk`() {
        val parser = ZubanTextOutputParser()
        val result = parser.parse("    note: orphaned note")
        assertTrue(result is ZubanParseResult.Junk)
    }

    fun `test found summary is a summary`() {
        assertEquals(ZubanParseResult.Summary, parse("Found 1 error in 1 file (checked 1 source file)"))
        assertEquals(ZubanParseResult.Summary, parse("Found 2 errors in 2 files (checked 3 source files)"))
    }

    fun `test success is a summary`() {
        assertEquals(ZubanParseResult.Summary, parse("Success: no issues found in 1 source file"))
    }

    fun `test bare number is a summary`() {
        assertEquals(ZubanParseResult.Summary, parse("2"))
    }

    fun `test trailing newline is tolerated`() {
        // process lines arrive with a trailing newline
        assertEquals(ZubanParseResult.Summary, parse("Found 1 error in 1 file (checked 1 source file)\n"))
        val issue = parse("bad.py:1:10: error: x  [y]\n") as ZubanParseResult.Issue
        assertEquals("bad.py", issue.message.file)
    }

    fun `test garbage line is junk`() {
        val result = parse("this is not a mypy line")
        assertTrue(result is ZubanParseResult.Junk)
        assertEquals("this is not a mypy line", (result as ZubanParseResult.Junk).raw)
    }

    fun `test message containing bracketed text still parses code`() {
        val result = parse(
            """a.py:1:10: error: Bracketed expression "[...]" is not valid as a type  [valid-type]"""
        )
        assertTrue(result is ZubanParseResult.Issue)
        val issue = result as ZubanParseResult.Issue
        assertEquals("a.py", issue.message.file)
        assertEquals("valid-type", issue.message.code)
        assertEquals(
            """Bracketed expression "[...]" is not valid as a type""",
            issue.message.message
        )
    }

    fun `test the exact cli mock lines parse as two issues and a summary`() {
        val parser = ZubanTextOutputParser()
        val r1 = parser.parse("""a.py:1:10: error: Bracketed expression "[...]" is not valid as a type  [valid-type]""")
        val r2 = parser.parse("""a.py:2:11: error: Bracketed expression "[...]" is not valid as a type  [valid-type]""")
        val r3 = parser.parse("Found 2 errors in 1 file (checked 1 source file)")
        assertTrue(r1 is ZubanParseResult.Issue)
        assertTrue(r2 is ZubanParseResult.Issue)
        assertEquals(ZubanParseResult.Summary, r3)
    }

    fun `test post adjust shape equals mypy json pipeline shape`() {
        // mypy JSON gives 1-based line and 0-based column; adjustForPlatform makes line 0-based
        val text = parse("bad.py:1:10: error: Incompatible types  [assignment]") as ZubanParseResult.Issue
        // column: text prints 1-based (10) -> stored 0-based (9); mypy JSON column for the same
        // error is already 0-based 9 -> identical
        assertEquals(9, text.message.column)
        assertEquals(0, text.message.line)
        assertEquals("ERROR", text.message.severity)
    }
}
