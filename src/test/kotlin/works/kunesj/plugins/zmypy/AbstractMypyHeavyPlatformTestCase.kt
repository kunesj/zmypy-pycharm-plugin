package works.kunesj.plugins.zmypy

import com.intellij.openapi.project.Project
import com.intellij.testFramework.replaceService
import io.mockk.every
import io.mockk.mockkObject
import works.kunesj.plugins.common.services.AbstractPluginPackageManagementService
import works.kunesj.plugins.common.test.AbstractPluginHeavyPlatformTestCase
import works.kunesj.plugins.zmypy.action.MypyScanJobRegistryService
import works.kunesj.plugins.zmypy.services.MypyPluginPackageManagementService
import works.kunesj.plugins.zmypy.testutil.MypyPluginPackageManagementServiceStub

abstract class AbstractMypyHeavyPlatformTestCase : AbstractPluginHeavyPlatformTestCase() {

    override fun setupPackageManagementServiceMock(stubProvider: (Project) -> AbstractPluginPackageManagementService) {
        mockkObject(MypyPluginPackageManagementService.Companion)
        every { MypyPluginPackageManagementService.getInstance(any(Project::class)) } answers {
            stubProvider(firstArg())
        }
    }

    override fun createPackageManagementServiceStub(project: Project) = MypyPluginPackageManagementServiceStub(project)

    override fun onSetUp() {
        project.replaceService(MypyScanJobRegistryService::class.java, MypyScanJobRegistryService(), testRootDisposable)
    }
}
