package works.kunesj.plugins.zmypy.toolWindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.toolWindow.AbstractTreeService
import works.kunesj.plugins.common.toolWindow.ITreeService
import works.kunesj.plugins.zmypy.services.mypySeverityConfigs

@Service(Service.Level.PROJECT)
class MypyTreeService : AbstractTreeService(mypySeverityConfigs.keys) {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): ITreeService = project.service<MypyTreeService>()
    }
}