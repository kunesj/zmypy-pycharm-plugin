package works.kunesj.plugins.zmypy.configurable

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.layout.ValidationInfoBuilder
import works.kunesj.plugins.common.configurable.ConfigurableConfiguration
import works.kunesj.plugins.common.configurable.GeneralConfigurable
import works.kunesj.plugins.common.trimToNull
import works.kunesj.plugins.common.validator.FileValidator
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.action.InstallMypyAction
import works.kunesj.plugins.zmypy.services.MypyPluginPackageManagementService
import works.kunesj.plugins.zmypy.services.MypySettings

class MypyConfigurable(private val project: Project) : GeneralConfigurable(
    project, ConfigurableConfiguration(
        ZMypyBundle.message("mypy.configuration.name"),
        ZMypyBundle.message("mypy.configuration.name"),
        ID,
        InstallMypyAction.ID,
        ZMypyBundle.message("mypy.intention.install_mypy.text", "Mypy"),
        ZMypyBundle.message("mypy.configuration.mypy_picker_title", "Mypy"),
        ZMypyBundle.message("mypy.configuration.path_to_executable.label", "Mypy"),
        FileFilter(MypyTool.values().flatMap { it.executableFileNames }.distinct()),
        ZMypyBundle.message("mypy.configuration.path_to_executable.empty_warning", "Mypy"),
        ZMypyBundle.message("mypy.configuration.path_to_executable.version_validation_title", "Mypy"),
        ZMypyBundle.message("mypy.configuration.use_project_sdk"),
        ZMypyBundle.message("mypy.configuration.config_file.comment"),
        ZMypyBundle.message("mypy.configuration.config_file.help"),
        toolNames = listOf("mypy", "zuban"),
        toolSelectorLabel = ZMypyBundle.message("mypy.configuration.type_checker.label"),
        pickerTitleForTool = { ZMypyBundle.message("mypy.configuration.mypy_picker_title", it) },
        directOptionTitleForTool = { ZMypyBundle.message("mypy.configuration.path_to_executable.label", it) },
        emptyWarningForTool = { ZMypyBundle.message("mypy.configuration.path_to_executable.empty_warning", it) },
        installButtonTextForTool = { ZMypyBundle.message("mypy.intention.install_mypy.text", it) },
        versionCheckTitleForTool = { ZMypyBundle.message("mypy.configuration.path_to_executable.version_validation_title", it) }
    )
) {
    override val settings get() = MypySettings.getInstance(project)
    override val packageManager get() = MypyPluginPackageManagementService.getInstance(project)

    override fun selectedTool() = settings.tool.name.lowercase()

    override fun selectTool(tool: String) {
        settings.tool = MypyTool.fromName(tool)
    }

    override fun validateExecutable(path: String?) = with(MypyValidator(project, settings.tool)) {
        path?.trimToNull()?.let { path ->
            validateExecutablePath(path) ?: validateVersion(path)
        }
    }

    override suspend fun validateLocalSdk() = MypyValidator(project, settings.tool).validateProjectSdk()

    override fun validateConfigFilePath(
        builder: ValidationInfoBuilder, field: TextFieldWithBrowseButton
    ) = FileValidator.validateConfigFilePath(field.text.trimToNull())?.let { builder.error(it) }

    companion object {
        const val ID = "Settings.ZMypy"
    }
}
