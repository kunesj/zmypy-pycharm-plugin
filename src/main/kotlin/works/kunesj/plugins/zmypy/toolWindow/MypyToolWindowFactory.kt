package works.kunesj.plugins.zmypy.toolWindow

import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.toolWindow.AbstractToolWindowFactory
import works.kunesj.plugins.zmypy.ZMypyBundle

class MypyToolWindowFactory : AbstractToolWindowFactory(ZMypyBundle.message("mypy.toolwindow.name")) {
    override fun createPanel(project: Project) = MypyToolWindowPanel(project)
}