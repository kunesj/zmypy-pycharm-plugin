package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.python.packaging.PyRequirement
import com.jetbrains.python.packaging.pyRequirement
import com.jetbrains.python.packaging.requirement.PyRequirementRelation
import works.kunesj.plugins.common.services.AbstractPluginPackageManagementService

@Service(Service.Level.PROJECT)
class MypyPluginPackageManagementService(override val project: Project) : AbstractPluginPackageManagementService() {

    override fun getRequirement(): PyRequirement {
        val tool = MypySettings.getInstance(project).tool
        return pyRequirement(tool.pipPackage, PyRequirementRelation.GTE, tool.minVersion)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): AbstractPluginPackageManagementService =
            project.service<MypyPluginPackageManagementService>()
    }
}
