// inspired by idea/243.19420.21 git4idea.test.TestDialogManager
package works.kunesj.plugins.zmypy.testutil

import works.kunesj.plugins.common.services.PluginPackageManagementException
import works.kunesj.plugins.common.test.dialog.AbstractTestDialogManager
import works.kunesj.plugins.common.test.dialog.TestDialogWrapper
import works.kunesj.plugins.zmypy.dialog.*

class TestDialogManager : AbstractTestDialogManager() {
    override fun createPyPackageInstallationErrorDialog(exception: PluginPackageManagementException.InstallationFailedException) =
        TestDialogWrapper(MypyPackageInstallationErrorDialog::class.java, exception)

    override fun createToolExecutionErrorDialog(commandLine: String, result: String, resultCode: Int?) =
        TestDialogWrapper(MypyExecutionErrorDialog::class.java, commandLine, result, resultCode)

    override fun createToolOutputParseErrorDialog(
        commandLine: String, targets: String, json: String, error: String
    ) = TestDialogWrapper(MypyParseErrorDialog::class.java, commandLine, targets, json, error)

    override fun createGeneralErrorDialog(failure: Throwable) =
        TestDialogWrapper(MypyGeneralErrorDialog::class.java, failure)
}
