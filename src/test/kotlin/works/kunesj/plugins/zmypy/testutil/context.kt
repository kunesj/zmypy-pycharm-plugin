package works.kunesj.plugins.zmypy.testutil

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.test.context.dataContext
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowPanel

fun dataContext(
    project: Project, customizer: SimpleDataContext.Builder.() -> Unit
): DataContext = dataContext(project, MypyToolWindowPanel.ID, customizer)
