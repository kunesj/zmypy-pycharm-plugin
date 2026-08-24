package works.kunesj.plugins.zmypy.action

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.ex.temp.TempFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.common.waitUntilAssertSucceeds
import io.mockk.every
import io.mockk.mockkObject
import junit.framework.Assert
import junit.framework.AssertionFailedError
import kotlinx.coroutines.runBlocking
import works.kunesj.plugins.common.test.action.markExcluded
import works.kunesj.plugins.common.test.action.unmark
import works.kunesj.plugins.common.test.action.waitForIt
import works.kunesj.plugins.zmypy.AbstractToolWindowTestCase
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.dialog.DialogManager
import works.kunesj.plugins.zmypy.services.MypySettings
import works.kunesj.plugins.zmypy.testutil.*
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

@TestDataPath($$"$CONTENT_ROOT/testData/action/scan_sdk_zuban")
class ScanSdkZubanTest : AbstractToolWindowTestCase() {

    private val dialogManager = TestDialogManager()

    override fun getTestDataPath() = "src/test/testData/action/scan_sdk_zuban"

    override fun setUp() {
        mockkObject(DialogManager.Companion)
        every { DialogManager.dialogManager } answers { dialogManager }
        super.setUp()
    }

    @Suppress("removal")
    fun testManualScan() = withMockSdk("${Paths.get(testDataPath).absolutePathString()}/MockSdk") {
        myFixture.copyDirectoryToProject("/", "/")
        // set the tool before installing so the (stub) package manager installs zuban, not mypy
        setUpSettings()
        installMypy(dataContext(project) { add(CommonDataKeys.PROJECT, project) })
        val excludedDir = TempFileSystem.getInstance().findFileByPath("/src/excluded_dir")!!
        val exclusionContext = dataContext(project) {
            add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(excludedDir))
        }
        markExcluded(exclusionContext)
        var assertionError: Error? = null
        toolWindowManager.onBalloon {
            val expected = ZMypyBundle.message("action.InstallMypyAction.done_html")
            if (expected != it.htmlBody) {
                assertionError = AssertionFailedError(Assert.format("Should not happen", expected, it.htmlBody))
            }
        }
        val target = TempFileSystem.getInstance().findFileByPath("/src")!!
        val context = dataContext(project) { add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(target)) }
        waitForIt(ScanAction.ID, context)
        scan(context)
        PlatformTestUtil.waitWhileBusy { MypyScanJobRegistryService.getInstance(project).isActive() }
        assertionError?.let { throw it }
        val resolvedFile = Paths.get(testDataPath).resolve("a.py").normalize().absolutePathString()
        runBlocking {
            waitUntilAssertSucceeds {
                treeUtil.assertStructure("+Found 1 issue(s) in 1 file(s)\n")
            }.also {
                treeUtil.expandAll()
                treeUtil.assertStructure(
                    """|-Found 1 issue(s) in 1 file(s)
                       | -$resolvedFile
                       |  Bracketed expression "[...]" is not valid as a type [valid-type] (0:9) 
                       |""".trimMargin()
                )
            }
        }
        unmark(exclusionContext)
    }

    private fun setUpSettings() {
        with(MypySettings.getInstance(project)) {
            tool = MypyTool.ZUBAN
            executablePath = ""
            workingDirectory = Paths.get(testDataPath).absolutePathString()
            useProjectSdk = true
            configFilePath = ""
            scanBeforeCheckIn = false
            arguments = ""
            excludeNonProjectFiles = true
        }
    }
}
