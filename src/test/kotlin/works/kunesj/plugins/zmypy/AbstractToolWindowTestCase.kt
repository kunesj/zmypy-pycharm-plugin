package works.kunesj.plugins.zmypy

import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.tree.TreeTestUtil
import works.kunesj.plugins.zmypy.services.mypySeverityConfigs
import works.kunesj.plugins.zmypy.testutil.TestToolWindowHeadlessManagerImpl
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowFactory
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowPanel
import works.kunesj.plugins.zmypy.toolWindow.MypyTreeService

abstract class AbstractToolWindowTestCase : AbstractMypyTestCase() {

    protected lateinit var treeUtil: TreeTestUtil
    protected lateinit var toolWindowManager: TestToolWindowHeadlessManagerImpl

    override fun setUp() {
        super.setUp()
        toolWindowManager = TestToolWindowHeadlessManagerImpl(project)
        project.replaceService(ToolWindowManager::class.java, toolWindowManager, testRootDisposable)
        setUpToolWindow()
        val panel = ToolWindowManager.getInstance(project)
            .getToolWindow(MypyToolWindowPanel.ID)!!.contentManager.contents.single().component as MypyToolWindowPanel
        treeUtil = TreeTestUtil(panel.tree)
        // ensure severities are on default setting
        with(MypyTreeService.getInstance(project)) {
            mypySeverityConfigs.keys.forEach { assertTrue(isSeverityLevelDisplayed(it)) }
        }
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()
    }

    override fun tearDown() {
        toolWindowManager.cleanup()
        super.tearDown()
    }

    private fun setUpToolWindow() {
        val toolWindowManager = ToolWindowManager.getInstance(project) as ToolWindowHeadlessManagerImpl
        val toolWindow = toolWindowManager.doRegisterToolWindow(MypyToolWindowPanel.ID)
        MypyToolWindowFactory().createToolWindowContent(myFixture.project, toolWindow)
    }
}
