package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.FlowCollector
import works.kunesj.plugins.common.run.ToolExecutionTerminatedException
import works.kunesj.plugins.common.services.IncompleteConfigurationNotifier
import works.kunesj.plugins.common.services.showClickableBalloonError
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.dialog.DialogManager
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowPanel

inline fun <reified T> handleScanException(
    project: Project, noinline commandLine: () -> String?, stdErr: StringBuilder,
    notifier: IncompleteConfigurationNotifier, silent: Boolean = false
): suspend FlowCollector<T>.(Throwable) -> Unit = {
    if (it is ToolExecutionTerminatedException) {
        if (!silent) showClickableBalloonError(project, MypyToolWindowPanel.ID,
            ZMypyBundle.message("mypy.toolwindow.balloon.external_error", MypySettings.getInstance(project).tool.displayName)) {
            DialogManager.showToolExecutionErrorDialog(
                commandLine() ?: "", stdErr.toString(), it.exitCode
            )
        }
    } else {
        // Unexpected exception - tool likely gone
        notifier.showWarningBubble(false)
    }
}