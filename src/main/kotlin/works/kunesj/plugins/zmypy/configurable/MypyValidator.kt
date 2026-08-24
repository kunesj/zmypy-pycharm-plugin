package works.kunesj.plugins.zmypy.configurable

import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.validator.AbstractToolValidator
import works.kunesj.plugins.common.validator.ToolValidatorMessages
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.MypyPluginPackageManagementService

class MypyValidator(project: Project, private val tool: MypyTool) :
    AbstractToolValidator(project, MESSAGES(tool)) {
    override val versionFlag = "-V"
    override val packageName = tool.pipPackage
    override fun getPackageManagementService() = MypyPluginPackageManagementService.getInstance(project)

    companion object {
        private fun MESSAGES(tool: MypyTool) = ToolValidatorMessages(
            pathNotExists = ZMypyBundle.message("mypy.configuration.path_to_executable.not_exists"),
            pathIsDirectory = ZMypyBundle.message("mypy.configuration.path_to_executable.is_directory"),
            pathNotExecutable = ZMypyBundle.message("mypy.configuration.path_to_executable.not_executable"),
            unknownVersion = ZMypyBundle.message("mypy.configuration.path_to_executable.unknown_version", tool.displayName),
            invalidVersion = ZMypyBundle.message("mypy.configuration.mypy_invalid_version", tool.displayName, tool.minVersion),
            notInstalled = ZMypyBundle.message("mypy.configuration.mypy_not_installed", tool.displayName)
        )
    }
}
