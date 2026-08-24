package works.kunesj.plugins.zmypy.action

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.kunesj.plugins.common.action.AbstractScanAction
import works.kunesj.plugins.common.action.AbstractScanJobRegistry
import works.kunesj.plugins.common.services.AbstractPluginPackageManagementService
import works.kunesj.plugins.common.services.IncompleteConfigurationNotifier
import works.kunesj.plugins.common.services.ToolExecutorConfiguration
import works.kunesj.plugins.common.services.Settings
import works.kunesj.plugins.common.toolWindow.ITreeService
import works.kunesj.plugins.zmypy.services.AsyncScanService
import works.kunesj.plugins.zmypy.services.MypyIncompleteConfigurationNotifier
import works.kunesj.plugins.zmypy.services.MypyPluginPackageManagementService
import works.kunesj.plugins.zmypy.services.MypySettings
import works.kunesj.plugins.zmypy.services.parser.MypyMessageConverter
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowPanel
import works.kunesj.plugins.zmypy.toolWindow.MypyTreeService

open class ScanAction : AbstractScanAction() {

    override val toolWindowId = MypyToolWindowPanel.ID

    override fun getTreeService(project: Project): ITreeService = MypyTreeService.getInstance(project)
    override fun getSettings(project: Project): Settings = MypySettings.getInstance(project)
    override fun getScanJobRegistry(project: Project): AbstractScanJobRegistry = MypyScanJobRegistryService.getInstance(project)
    override fun getIncompleteConfigurationNotifier(project: Project): IncompleteConfigurationNotifier = MypyIncompleteConfigurationNotifier.getInstance(project)
    override fun getPackageManagementService(project: Project): AbstractPluginPackageManagementService = MypyPluginPackageManagementService.getInstance(project)

    override suspend fun scanAndAdd(
        project: Project,
        targets: Collection<VirtualFile>,
        configuration: ToolExecutorConfiguration,
        treeService: ITreeService
    ) {
        AsyncScanService.getInstance(project).scan(targets, configuration).forEach {
            val mypyMessage = MypyMessageConverter.convert(it)
            withContext(Dispatchers.EDT) {
                treeService.add(mypyMessage)
            }
        }
    }

    companion object {
        const val ID = "works.kunesj.plugins.zmypy.action.ScanAction"
    }
}
