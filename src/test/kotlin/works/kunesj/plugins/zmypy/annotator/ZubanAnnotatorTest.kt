package works.kunesj.plugins.zmypy.annotator

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestDataPath
import works.kunesj.plugins.zmypy.AbstractToolWindowTestCase
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.MypySettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.absolutePathString

/**
 * Real-time annotations in zuban mode (zmypy has no --shadow-file):
 *  - the working directory is mirrored into a temp dir; zmypy runs with CWD = the mirror root
 *  - a clean file is checked through a link (symlink on Linux), a dirty (unsaved) file
 *    is checked from its in-memory content, at the updated position
 *  - a file outside the working directory is not checked at all
 *
 * The fixture's project files live in the TempFileSystem, which the OS (and therefore the
 * mirror) cannot see, so the tests work with real files on the local file system.
 */
@TestDataPath($$"$CONTENT_ROOT/testData/annotation_zuban")
class ZubanAnnotatorTest : AbstractToolWindowTestCase() {

    private lateinit var workDir: Path
    private lateinit var outsideDir: Path

    override fun getTestDataPath() = "src/test/testData/annotation_zuban"

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(MypyInspection())
        callsFile.delete()
        workDir = Files.createTempDirectory("zmypy_test_workdir_")
        outsideDir = Files.createTempDirectory("zmypy_test_outside_")
    }

    override fun tearDown() {
        callsFile.delete()
        deleteRecursively(workDir)
        deleteRecursively(outsideDir)
        super.tearDown()
    }

    fun `test a clean file is scanned through the mirror`() {
        openLocalFile("bad.py")
        setUpZubanSettings()
        val annotations = annotate()
        assertEquals(1, annotations.size)
        assertEquals("A test error", annotations.single().description)
        assertEquals(0, annotationLine(annotations.single()))
        val (cwd, args, markerLine, lineCount, kind) = lastCall()
        assertFalse("zmypy must run with CWD = the mirror root, not the project dir", cwd == workDir.toString())
        assertEquals("--show-column-numbers $cwd/bad.py", args)
        // the on-disk content was checked (marker on line 1 of the 3-line file)
        assertEquals("1", markerLine)
        assertEquals("3", lineCount)
        if (SystemInfo.isLinux) assertEquals("SYMLINK", kind)
    }

    fun `test a dirty file is scanned with its in-memory content`() {
        openLocalFile("bad.py")
        setUpZubanSettings()
        myFixture.doHighlighting()
        assertEquals(1, callLines().size)
        // insert four lines above the marker (the caret is at the start of the file):
        // the marker moves from line 1 to line 5, the document grows from 3 to 7 lines
        myFixture.type("\n\na = 1\nb = 2\n")
        // in a real IDE the daemon re-analyzes on typing; in the headless test env a
        // one-shot doHighlighting reuses the cached external-annotator result, so fire
        // the file-change event that drives the re-analysis
        currentFile().refresh(true, true)
        val annotations = annotate()
        assertEquals(1, annotations.size)
        // the underline follows the unsaved content (0-based line 4 == line 5)
        assertEquals(4, annotationLine(annotations.single()))
        val (cwd, args, markerLine, lineCount, kind) = lastCall()
        assertEquals(2, callLines().size)
        assertEquals("--show-column-numbers $cwd/bad.py", args)
        // the in-memory (7-line) content was checked, not the 3-line on-disk file
        assertEquals("5", markerLine)
        assertEquals("7", lineCount)
        assertEquals("REGULAR", kind)
        // the real file on disk was not touched
        assertEquals(3, Files.readAllLines(workDir.resolve("bad.py")).size)
    }

    fun `test clean subdirectories are dir links and get materialized on edit`() {
        Files.createDirectories(workDir.resolve("pkg"))
        Files.copy(Paths.get(testDataPath).resolve("pkg/mod.py"), workDir.resolve("pkg/mod.py"))
        Files.copy(Paths.get(testDataPath).resolve("main.py"), workDir.resolve("main.py"))
        openLocalFile("main.py")
        setUpZubanSettings()
        myFixture.doHighlighting()
        val mirrorRoot1 = Paths.get(lastCall().first())
        // clean package subdirectory = one live directory link
        assertTrue(Files.isSymbolicLink(mirrorRoot1.resolve("pkg")))
        assertTrue(Files.isDirectory(mirrorRoot1.resolve("pkg")))
        // now edit a file inside the linked package
        openLocalFile("pkg/mod.py")
        myFixture.type("W2 = 1  # E_MARKER2\n")
        currentFile().refresh(true, true)
        myFixture.doHighlighting()
        val (cwd2, _, markerLine2, lineCount2, kind2) = lastCall()
        val mirrorRoot2 = Paths.get(cwd2)
        // the package was materialized: a real dir with the edited file as a content copy
        assertTrue(!Files.isSymbolicLink(mirrorRoot2.resolve("pkg")))
        assertTrue(Files.isDirectory(mirrorRoot2.resolve("pkg")))
        assertEquals("1", markerLine2)
        assertEquals("2", lineCount2)
        assertEquals("REGULAR", kind2)
        assertEquals(myFixture.editor.document.text, Files.readString(mirrorRoot2.resolve("pkg/mod.py")))
    }

    fun `test the venv python is passed as python-executable`() {
        openLocalFile("bad.py")
        // a venv next to the project: the mirror must pass its python to zmypy
        Files.createDirectories(workDir.resolve(".venv/bin"))
        Files.writeString(workDir.resolve(".venv/pyvenv.cfg"), "home = /usr/bin\n")
        Files.writeString(workDir.resolve(".venv/bin/python"), "#!/bin/sh\n")
        setUpZubanSettings()
        myFixture.doHighlighting()
        val parts = lastCall()
        assertEquals(workDir.resolve(".venv/bin/python").toString(), parts[5])
    }

    fun `test a file outside the working directory is not scanned`() {
        openLocalFile("bad.py")
        setUpZubanSettings()
        openLocalFile("outside.py", outsideDir)
        myFixture.doHighlighting()
        assertTrue(callLines().isEmpty())
        assertTrue(annotate().isEmpty())
    }

    private fun setUpZubanSettings() {
        with(MypySettings.getInstance(project)) {
            tool = MypyTool.ZUBAN
            executablePath = Paths.get(testDataPath).resolve("zmypy").absolutePathString()
            workingDirectory = workDir.toString()
            useProjectSdk = false
            configFilePath = ""
            arguments = ""
            excludeNonProjectFiles = false
            scanBeforeCheckIn = false
        }
    }

    private fun openLocalFile(name: String, dir: Path = workDir): VirtualFile {
        Files.copy(
            Paths.get(testDataPath).resolve(name),
            dir.resolve(name),
            StandardCopyOption.REPLACE_EXISTING
        )
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.resolve(name).toString())
            ?: error("file not found in the local file system: $name")
        myFixture.openFileInEditor(file)
        return file
    }

    private fun deleteRecursively(dir: Path) {
        runCatching {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Suppress("UnstableApiUsage")
    private fun annotate() = myFixture.doHighlighting().filter { it.toolId == MypyAnnotator::class.java }

    private fun annotationLine(highlight: HighlightInfo): Int =
        myFixture.editor.document.getLineNumber(highlight.startOffset)

    private fun currentFile(): VirtualFile =
        requireNotNull(FileDocumentManager.getInstance().getFile(myFixture.editor.document))

    private fun lastCall(): List<String> = callLines().last().split("|")

    private fun callLines(): List<String> = if (callsFile.exists()) callsFile.readLines() else emptyList()

    companion object {
        val callsFile = File("/tmp/zmypy_annotation_calls.txt")
    }
}
