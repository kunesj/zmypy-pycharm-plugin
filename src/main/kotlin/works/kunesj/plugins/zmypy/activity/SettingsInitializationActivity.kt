package works.kunesj.plugins.zmypy.activity

import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.activity.AbstractSettingsInitializationActivity
import works.kunesj.plugins.common.services.AbstractPluginPackageManagementService
import works.kunesj.plugins.common.services.BasicSettingsData
import works.kunesj.plugins.common.services.Settings
import works.kunesj.plugins.zmypy.services.MypyIncompleteConfigurationNotifier
import works.kunesj.plugins.zmypy.services.MypyPluginPackageManagementService
import works.kunesj.plugins.zmypy.services.MypySettings
import works.kunesj.plugins.zmypy.services.OldMypySettings

class SettingsInitializationActivity : AbstractSettingsInitializationActivity() {

    override fun getPackageManagementService(project: Project): AbstractPluginPackageManagementService =
        MypyPluginPackageManagementService.getInstance(project)

    override fun getSettings(project: Project): Settings = MypySettings.getInstance(project)

    override suspend fun getOldSettings(project: Project): BasicSettingsData = OldMypySettings.getInstance(project)

    override fun getIncompleteConfigurationNotifier(project: Project) =
        MypyIncompleteConfigurationNotifier.getInstance(project)
}
