package works.kunesj.plugins.zmypy.dialog

import works.kunesj.plugins.common.dialog.PluginErrorDescription
import works.kunesj.plugins.common.dialog.PluginErrorDialog
import works.kunesj.plugins.zmypy.ZMypyBundle

class MypyPackageInstallationErrorDialog(message: String) : PluginErrorDialog(
    ZMypyBundle.message("mypy.dialog.installation_error.title"),
    PluginErrorDescription(message, ZMypyBundle.message("mypy.dialog.installation_error.message"))
)

class MypyExecutionErrorDialog(
    commandLine: String, result: String, resultCode: Int?
) : PluginErrorDialog(
    ZMypyBundle.message("mypy.dialog.execution_error.title"), PluginErrorDescription(
        ZMypyBundle.message("mypy.dialog.execution_error.content", commandLine, result),
        resultCode?.let { ZMypyBundle.message("mypy.dialog.execution_error.status_code", it) })
)

class MypyParseErrorDialog(
    commandLine: String, targets: String, json: String, error: String
) : PluginErrorDialog(
    ZMypyBundle.message("mypy.dialog.parse_error.title"), PluginErrorDescription(
        ZMypyBundle.message("mypy.dialog.parse_error.details", commandLine, targets, json),
        error.ifEmpty { null }?.let { ZMypyBundle.message("mypy.dialog.parse_error.message", it) })
)

class MypyGeneralErrorDialog(throwable: Throwable) : PluginErrorDialog(
    ZMypyBundle.message("mypy.dialog.general_error.title"), PluginErrorDescription(
        ZMypyBundle.message(
            "mypy.dialog.general_error.details", throwable.message ?: throwable.toString(), throwable.stackTraceToString()
        ), ZMypyBundle.message("mypy.please_report_this_issue")
    )
)
