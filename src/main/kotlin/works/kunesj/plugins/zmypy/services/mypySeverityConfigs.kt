package works.kunesj.plugins.zmypy.services

import com.intellij.icons.AllIcons
import works.kunesj.plugins.common.services.SeverityConfig
import works.kunesj.plugins.zmypy.ZMypyBundle

val mypySeverityConfigs = mapOf(
    "ERROR" to SeverityConfig(
        "ERROR",
        ZMypyBundle.message("action.MyPyDisplayErrorsAction.text"),
        ZMypyBundle.message("action.MyPyDisplayErrorsAction.description"),
        AllIcons.General.Error
    ),

    "WARNING" to SeverityConfig(
        "WARNING",
        ZMypyBundle.message("action.MypyDisplayWarningsAction.text"),
        ZMypyBundle.message("action.MypyDisplayWarningsAction.description"),
        AllIcons.General.Warning
    ),

    "NOTE" to SeverityConfig(
        "NOTE",
        ZMypyBundle.message("action.MypyDisplayNoteAction.text"),
        ZMypyBundle.message("action.MypyDisplayNoteAction.description"),
        AllIcons.General.Information
    )
)
