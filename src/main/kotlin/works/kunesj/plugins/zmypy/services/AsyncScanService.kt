package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.*
import works.kunesj.plugins.common.services.ToolExecutorConfiguration
import works.kunesj.plugins.common.services.showClickableBalloonError
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.dialog.DialogManager
import works.kunesj.plugins.zmypy.services.parser.MypyMessage
import works.kunesj.plugins.zmypy.services.parser.MypyOutputParser
import works.kunesj.plugins.zmypy.services.parser.MypyParseException
import works.kunesj.plugins.zmypy.services.parser.ZubanParseResult
import works.kunesj.plugins.zmypy.services.parser.ZubanTextOutputParser
import works.kunesj.plugins.zmypy.toolWindow.MypyToolWindowPanel
import kotlin.io.path.Path

@Service(Service.Level.PROJECT)
class AsyncScanService(private val project: Project) {

    suspend fun scan(targets: Collection<VirtualFile>, configuration: ToolExecutorConfiguration): List<MypyMessage> {
        val tool = MypySettings.getInstance(project).tool
        val nonJsonStdout = StringBuilder()
        val parameters = with(project) { buildMypyParamList(configuration, targets, tool = tool) }
        val stdErr = StringBuilder()
        val executor = MypyExecutor(project, tool)
        val textParser = if (tool == MypyTool.ZUBAN) ZubanTextOutputParser() else null
        return executor.execute(configuration, parameters).filter { it.text.isNotBlank() }
            .transform { line ->
                if (line.isError) {
                    stdErr.append(line.text)
                    return@transform
                }
                if (tool == MypyTool.MYPY) {
                    MypyOutputParser.parse(line.text).onSuccess { emit(it) }.onFailure {
                        when (it) {
                            is MypyParseException -> {
                                // mypy sometimes ignores -O json for certain errors; collect the raw lines as-is
                                nonJsonStdout.appendLine(it.sourceJson)
                            }

                            else -> {
                                thisLogger().error(ZMypyBundle.message("mypy.please_report_this_issue"), it)
                            }
                        }
                    }
                } else {
                    when (val result = textParser!!.parse(line.text)) {
                        is ZubanParseResult.Issue -> {
                            // zuban prints CWD-relative paths; absolutize for the tool window tree
                            emit(result.message.copy(file = resolveToolFile(result.message.file, configuration)))
                        }

                        // "Found N errors..." / "Success: ..." are normal output — never balloon on them
                        ZubanParseResult.Summary -> Unit
                        ZubanParseResult.Ignored -> Unit
                        is ZubanParseResult.Junk ->
                            // anything else unexpected on stdout is suspicious, like non-json output in mypy mode
                            nonJsonStdout.appendLine(result.raw)
                    }
                }
            }.onCompletion {
                val output = buildString {
                    if (stdErr.isNotEmpty()) append(stdErr)
                    if (nonJsonStdout.isNotEmpty()) {
                        if (stdErr.isNotEmpty()) appendLine()
                        append(nonJsonStdout)
                    }
                }
                if (output.isNotEmpty()) {
                    showClickableBalloonError(project, MypyToolWindowPanel.ID,
                        ZMypyBundle.message("mypy.toolwindow.balloon.external_error", tool.displayName)) {
                        DialogManager.showToolExecutionErrorDialog(executor.commandLine ?: "", output, executor.exitCode)
                    }
                }
            }.catch(handleScanException(project, { executor.commandLine }, stdErr, MypyIncompleteConfigurationNotifier.getInstance(project))).toList(ArrayList())
    }

    private fun resolveToolFile(file: String, configuration: ToolExecutorConfiguration): String {
        val path = Path(file)
        if (path.isAbsolute) return file
        val workingDirectory = configuration.workingDirectory ?: return file
        return Path(workingDirectory).resolve(path).normalize().toString()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): AsyncScanService = project.service()
    }
}
