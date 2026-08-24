package works.kunesj.plugins.zmypy.testutil

import com.intellij.openapi.project.Project
import com.jetbrains.python.packaging.PyRequirement
import com.jetbrains.python.packaging.pyRequirement
import com.jetbrains.python.packaging.requirement.PyRequirementRelation
import works.kunesj.plugins.common.test.services.AbstractPluginPackageManagementServiceStub
import works.kunesj.plugins.zmypy.services.MypySettings

class MypyPluginPackageManagementServiceStub(project: Project) : AbstractPluginPackageManagementServiceStub(project) {
    override fun getRequirement(): PyRequirement {
        val tool = MypySettings.getInstance(project).tool
        return pyRequirement(tool.pipPackage, PyRequirementRelation.GTE, tool.minVersion)
    }
}
