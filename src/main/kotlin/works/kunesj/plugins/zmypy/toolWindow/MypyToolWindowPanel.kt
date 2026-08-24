package works.kunesj.plugins.zmypy.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.Tree
import org.jetbrains.annotations.VisibleForTesting
import works.kunesj.plugins.common.services.Settings
import works.kunesj.plugins.common.toolWindow.AbstractToolWindowPanel
import works.kunesj.plugins.common.toolWindow.ITreeService
import works.kunesj.plugins.zmypy.services.MypySettings

class MypyToolWindowPanel(project: Project, @VisibleForTesting val tree: Tree = Tree()) :
    AbstractToolWindowPanel(project, tree) {

    override val treeService: ITreeService = MypyTreeService.getInstance(project)
    override val settings: Settings = MypySettings.getInstance(project)

    init {
        super.init(ID, MAIN_ACTION_GROUP, SCROLL_TO_SOURCE_ID)
    }

    companion object {
        private const val MAIN_ACTION_GROUP = "works.kunesj.plugins.zmypy.MypyPluginActions"
        const val ID = "ZMypy "
        const val SCROLL_TO_SOURCE_ID = "works.kunesj.plugins.zmypy.action.ScrollToSourceAction"
    }
}