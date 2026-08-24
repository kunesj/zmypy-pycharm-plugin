package works.kunesj.plugins.zmypy.testutil

import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.test.toolWindow.AbstractTestToolWindowHeadlessManagerImpl
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowPanel

class TestToolWindowHeadlessManagerImpl(project: Project) :
    AbstractTestToolWindowHeadlessManagerImpl(project) {

    override val toolWindowId = MypyToolWindowPanel.ID
}
