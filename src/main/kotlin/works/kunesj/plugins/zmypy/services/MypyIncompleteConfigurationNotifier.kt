package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.services.IncompleteConfigurationNotifier
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.action.InstallMypyAction
import works.kunesj.plugins.zmypy.action.OpenSettingsAction

@Service(Service.Level.PROJECT)
class MypyIncompleteConfigurationNotifier(project: Project) : IncompleteConfigurationNotifier(
    project,
    ZMypyBundle.message("notification.group.mypy.group"),
    ZMypyBundle.message("mypy.notification.incomplete_configuration", MypySettings.getInstance(project).tool.displayName),
    OpenSettingsAction.ID,
    InstallMypyAction.ID,
) {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): MypyIncompleteConfigurationNotifier =
            project.service<MypyIncompleteConfigurationNotifier>()
    }
}
