package works.kunesj.plugins.zmypy.annotator

import com.intellij.testFramework.TestDataPath
import works.kunesj.plugins.zmypy.AbstractToolWindowTestCase
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.MypySettings
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

/**
 * Real-time annotations in check-on-save mode (zuban):
 *  - a clean (saved) file is scanned on disk (never via a temp file)
 *  - a dirty (unsaved) file does NOT spawn a process; the last saved-state result is kept
 *
 * Note: the post-save re-scan is driven by [works.kunesj.plugins.zmypy.activity.DocumentSavedActivity]
 * (save listener -> DaemonCodeAnalyzer.restart). It is not asserted here because forcing the
 * daemon to re-run after a save is flaky in the headless test environment; the clean/dirty
 * branches that the activity's restart ultimately exercises are covered below.
 */
@TestDataPath($$"$CONTENT_ROOT/testData/annotation_zuban")
class ZubanAnnotatorTest : AbstractToolWindowTestCase() {

    override fun getTestDataPath() = "src/test/testData/annotation_zuban"

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(MypyInspection())
        callsFile.delete()
    }

    override fun tearDown() {
        callsFile.delete()
        super.tearDown()
    }

    fun `test a clean file is scanned on disk without a temp file`() {
        setUpZubanSettings()
        myFixture.configureByFile("bad.py")
        val annotations = myFixture.doHighlighting().filter { it.toolId == MypyAnnotator::class.java }
        assertEquals(1, annotations.size)
        assertEquals("A test error", annotations.single().description)
        val calls = callLines()
        assertEquals(1, calls.size)
        // the real on-disk file is passed, never a pycharm_mypy_ temp copy
        assertFalse(calls.single().contains("pycharm_mypy_"))
        assertTrue(calls.single().endsWith("/bad.py"))
    }

    fun `test a dirty file does not spawn a new process and keeps the cached result`() {
        setUpZubanSettings()
        myFixture.configureByFile("bad.py")
        myFixture.doHighlighting()
        assertEquals(1, callLines().size)
        // make the document dirty (unsaved change)
        myFixture.type("y")
        val annotations = myFixture.doHighlighting().filter { it.toolId == MypyAnnotator::class.java }
        // the last saved-state underlines are still shown
        assertEquals(1, annotations.size)
        // but no new process was spawned for the unsaved content
        assertEquals(1, callLines().size)
    }

    private fun setUpZubanSettings() {
        with(MypySettings.getInstance(project)) {
            tool = MypyTool.ZUBAN
            executablePath = Paths.get(testDataPath).resolve("zmypy").absolutePathString()
            workingDirectory = Paths.get(testDataPath).absolutePathString()
            useProjectSdk = false
            configFilePath = ""
            arguments = ""
            excludeNonProjectFiles = false
            scanBeforeCheckIn = false
        }
    }

    private fun callLines(): List<String> = if (callsFile.exists()) callsFile.readLines() else emptyList()

    companion object {
        val callsFile = File("/tmp/zmypy_annotation_calls.txt")
    }
}
