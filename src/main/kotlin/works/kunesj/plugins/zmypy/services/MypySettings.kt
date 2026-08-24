package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.blankToSingleSpace
import works.kunesj.plugins.common.services.AbstractToolSettings
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool

@Service(Service.Level.PROJECT)
@State(name = "ZMypySettings", storages = [Storage("ZMypyPlugin.xml")], category = SettingsCategory.PLUGINS)
class MypySettings(project: Project) : AbstractToolSettings<MypySettings.MypyState>(project, MypyState()) {

    class MypyState : BaseState() {
        var tool by string("mypy")
        var mypyExecutable by string()
        var useProjectSdk by property(true)
        var configFilePath by string()
        var arguments by string()
        var autoScrollToSource by property(false)
        var excludeNonProjectFiles by property(true)
        var projectDirectory by string()
        var scanBeforeCheckIn by property(false)
    }

    var tool: MypyTool
        get() = MypyTool.fromName(state.tool)
        set(value) { state.tool = value.name.lowercase() }

    override var useProjectSdk
        get() = state.useProjectSdk
        set(value) { state.useProjectSdk = value }

    override var executablePath
        get() = state.mypyExecutable?.trim() ?: ""
        set(value) { state.mypyExecutable = value.blankToSingleSpace() }

    override var configFilePath
        get() = state.configFilePath?.trim() ?: ""
        set(value) { state.configFilePath = value.blankToSingleSpace() }

    override var arguments
        get() = state.arguments?.trim() ?: ""
        set(value) { state.arguments = value.blankToSingleSpace() }

    override var isAutoScrollToSource
        get() = state.autoScrollToSource
        set(value) { state.autoScrollToSource = value }

    override var excludeNonProjectFiles
        get() = state.excludeNonProjectFiles
        set(value) { state.excludeNonProjectFiles = value }

    override var workingDirectory
        get() = state.projectDirectory
        set(value) { state.projectDirectory = value }

    override var scanBeforeCheckIn
        get() = state.scanBeforeCheckIn
        set(value) { state.scanBeforeCheckIn = value }

    override fun getPackageManagementService() = MypyPluginPackageManagementService.getInstance(project)
    override fun toolNotSetMessage() = ZMypyBundle.message("mypy.configuration.tool_not_set", tool.displayName)
    override fun isExecutableStateNull() = state.mypyExecutable == null
    override fun isConfigFileStateNull() = state.configFilePath == null
    override fun isArgumentsStateNull() = state.arguments == null
    override fun initialState() = MypyState()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): MypySettings = project.service()
    }
}