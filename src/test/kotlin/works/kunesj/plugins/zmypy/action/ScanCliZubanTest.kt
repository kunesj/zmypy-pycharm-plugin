package works.kunesj.plugins.zmypy.action

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.ex.temp.TempFileSystem
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.virtualFile
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestDataPath
import io.mockk.every
import io.mockk.mockkObject
import junit.framework.AssertionFailedError
import org.jetbrains.concurrency.asPromise
import works.kunesj.plugins.common.test.action.markExcluded
import works.kunesj.plugins.common.test.action.unmark
import works.kunesj.plugins.common.test.dialog.TestDialogWrapper
import works.kunesj.plugins.zmypy.AbstractToolWindowTestCase
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.dialog.DialogManager
import works.kunesj.plugins.zmypy.dialog.MypyExecutionErrorDialog
import works.kunesj.plugins.zmypy.services.MypySettings
import works.kunesj.plugins.zmypy.testutil.TestDialogManager
import works.kunesj.plugins.zmypy.testutil.dataContext
import works.kunesj.plugins.zmypy.testutil.scan
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import javax.swing.event.HyperlinkEvent
import kotlin.io.path.absolutePathString

@TestDataPath($$"$CONTENT_ROOT/testData/action/scan_cli_zuban")
class ScanCliZubanTest : AbstractToolWindowTestCase() {

    private val dialogManager = TestDialogManager()

    override fun getTestDataPath() = "src/test/testData/action/scan_cli_zuban"

    override fun setUp() {
        mockkObject(DialogManager.Companion)
        every { DialogManager.dialogManager } answers { dialogManager }
        super.setUp()
    }

    override fun tearDown() {
        dialogManager.cleanup()
        super.tearDown()
    }

    @Suppress("removal")
    fun testManualScan() {
        myFixture.copyDirectoryToProject("/", "/")
        val excludedDir = TempFileSystem.getInstance().findFileByPath("/src/excluded_dir")!!
        setUpSettings("zmypy")
        val exclusionContext = dataContext(project) {
            add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(excludedDir))
        }
        markExcluded(exclusionContext)
        var assertionError: Error? = null
        toolWindowManager.onBalloon {
            assertionError = AssertionFailedError("Should not happen: $it")
        }
        val target = TempFileSystem.getInstance().findFileByPath("/src")!!
        scan(dataContext(project) { add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(target)) })
        PlatformTestUtil.waitWhileBusy { MypyScanJobRegistryService.getInstance(project).isActive() }
        assertionError?.let { throw it }
        val resolvedFile = Paths.get(testDataPath).resolve("a.py").normalize().absolutePathString()
        treeUtil.assertStructure("+Found 2 issue(s) in 1 file(s)\n")
        treeUtil.expandAll()
        treeUtil.assertStructure(
            """|-Found 2 issue(s) in 1 file(s)
                | -$resolvedFile
                |  Bracketed expression "[...]" is not valid as a type [valid-type] (0:9) 
                |  Bracketed expression "[...]" is not valid as a type [valid-type] (1:10) 
                |""".trimMargin()
        )
        unmark(exclusionContext)
    }

    // the "Success: ..." summary line must not open an error balloon
    fun `test clean exit code 0 results in no dialog`() {
        myFixture.copyDirectoryToProject("/", "/")
        val excludedDir = TempFileSystem.getInstance().findFileByPath("/src/excluded_dir")!!
        setUpSettings("zmypy_exit_with_0")
        val exclusionContext = dataContext(project) {
            add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(excludedDir))
        }
        markExcluded(exclusionContext)
        var assertionError: Error? = null
        toolWindowManager.onBalloon {
            assertionError = AssertionFailedError("Should not happen")
        }
        val target = TempFileSystem.getInstance().findFileByPath("/src")!!
        scan(dataContext(project) { add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(target)) })
        PlatformTestUtil.waitWhileBusy { MypyScanJobRegistryService.getInstance(project).isActive() }
        assertionError?.let { throw it }
        unmark(exclusionContext)
    }

    fun `test exit code 2 and stderr results in dialog`() {
        myFixture.copyDirectoryToProject("/", "/")
        setUpSettings("zmypy_exit_with_2_and_stderr")
        toolWindowManager.onBalloon {
            it.listener?.hyperlinkUpdate(
                HyperlinkEvent(
                    "dumb", HyperlinkEvent.EventType.ACTIVATED, URI("http://localhost").toURL()
                )
            )
        }
        val dialogShown = CompletableFuture<TestDialogWrapper>()
        dialogManager.onDialog(MypyExecutionErrorDialog::class.java) {
            it.close(DialogWrapper.OK_EXIT_CODE)
            dialogShown.complete(it)
            it.getExitCode()
        }
        val target = WorkspaceModel.getInstance(project).currentSnapshot.entities(ContentRootEntity::class.java)
            .first().url.virtualFile!!
        scan(dataContext(project) { add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(target)) })
        PlatformTestUtil.assertPromiseSucceeds(dialogShown.asPromise())
        assertTrue(dialogShown.isDone && with(dialogShown.get()) { isShown() && getExitCode() == DialogWrapper.OK_EXIT_CODE })
    }

    fun `test exit code other than 0, 1, and 2 results in dialog`() {
        myFixture.copyDirectoryToProject("/", "/")
        setUpSettings("zmypy_exit_with_3")
        toolWindowManager.onBalloon {
            it.listener?.hyperlinkUpdate(
                HyperlinkEvent(
                    "dumb", HyperlinkEvent.EventType.ACTIVATED, URI("http://localhost").toURL()
                )
            )
        }
        val dialogShown = CompletableFuture<TestDialogWrapper>()
        dialogManager.onDialog(MypyExecutionErrorDialog::class.java) {
            it.close(DialogWrapper.OK_EXIT_CODE)
            dialogShown.complete(it)
            it.getExitCode()
        }
        val target = WorkspaceModel.getInstance(project).currentSnapshot.entities(ContentRootEntity::class.java)
            .first().url.virtualFile!!
        scan(dataContext(project) { add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(target)) })
        PlatformTestUtil.assertPromiseSucceeds(dialogShown.asPromise())
        assertTrue(dialogShown.isDone && with(dialogShown.get()) { isShown() && getExitCode() == DialogWrapper.OK_EXIT_CODE })
    }

    private fun setUpSettings(executable: String) {
        with(MypySettings.getInstance(project)) {
            tool = MypyTool.ZUBAN
            executablePath = Paths.get(testDataPath).resolve(executable).absolutePathString()
            workingDirectory = Paths.get(testDataPath).absolutePathString()
            useProjectSdk = false
            configFilePath = ""
            scanBeforeCheckIn = false
            arguments = ""
            excludeNonProjectFiles = true
        }
    }
}
