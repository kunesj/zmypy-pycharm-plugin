package works.kunesj.plugins.zmypy.action

import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.action.AbstractScanJobRegistry
import works.kunesj.plugins.common.action.AbstractStopScanAction
import works.kunesj.plugins.common.toolWindow.ITreeService
import works.kunesj.plugins.zmypy.toolWindow.MypyTreeService

class StopScanAction : AbstractStopScanAction() {

    override fun getScanJobRegistry(project: Project): AbstractScanJobRegistry = MypyScanJobRegistryService.getInstance(project)
    override fun getTreeService(project: Project): ITreeService = MypyTreeService.getInstance(project)

    companion object {
        const val ID = "works.kunesj.plugins.zmypy.action.StopScanAction"
    }
}
