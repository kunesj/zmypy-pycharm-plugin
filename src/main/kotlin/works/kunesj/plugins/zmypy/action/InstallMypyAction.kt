package works.kunesj.plugins.zmypy.action

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.action.AbstractInstallToolAction
import works.kunesj.plugins.common.services.PluginPackageManagementException
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.dialog.DialogManager
import works.kunesj.plugins.zmypy.services.MypyPluginPackageManagementService
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowPanel

class InstallMypyAction : AbstractInstallToolAction(ZMypyBundle.message("action.InstallMypyAction.done_html")) {
    override val toolWindowId = MypyToolWindowPanel.ID
    override fun getPackageManager(project: Project) = MypyPluginPackageManagementService.getInstance(project)

    override fun handleFailure(failure: Throwable) {
        when (failure) {
            is PluginPackageManagementException.InstallationFailedException -> {
                DialogManager.showPyPackageInstallationErrorDialog(failure)
            }

            else -> {
                thisLogger().error(failure)
                DialogManager.showGeneralErrorDialog(failure)
            }
        }
    }

    companion object {
        const val ID = "works.kunesj.plugins.zmypy.action.InstallMypyAction"
    }
}
