package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.future
import works.kunesj.plugins.common.services.ToolExecutorConfiguration
import works.kunesj.plugins.zmypy.ZMypyBundle
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.parser.MypyMessage
import works.kunesj.plugins.zmypy.services.parser.MypyOutputParser
import works.kunesj.plugins.zmypy.services.parser.MypyParseException
import works.kunesj.plugins.zmypy.services.parser.ZubanParseResult
import works.kunesj.plugins.zmypy.services.parser.ZubanTextOutputParser
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

@Service(Service.Level.PROJECT)
class SyncScanService(private val project: Project, private val cs: CoroutineScope) {

    fun scan(
        targets: Collection<VirtualFile>, configuration: ToolExecutorConfiguration
    ): Map<VirtualFile, List<MypyMessage>> {
        val tool = MypySettings.getInstance(project).tool
        val useShadow = tool == MypyTool.MYPY
        // zuban has no --shadow-file: check the real on-disk files, no temp copies
        val shadowedTargetMap = if (useShadow) targets.associateWith { copyTempFrom(it) } else emptyMap()
        val targetsByPath = targets.associateBy { it.canonicalPath ?: it.path }
        val parameters = with(project) {
            if (useShadow) {
                buildMypyParamList(configuration, shadowedTargetMap, tool)
            } else {
                buildMypyParamList(configuration, targets, tool = tool)
            }
        }
        val stdErr = StringBuilder()
        val executor = MypyExecutor(project, tool)
        val textParser = ZubanTextOutputParser()
        val flow: Flow<Pair<VirtualFile, MypyMessage>> =
            executor.execute(configuration, parameters).filter { it.text.isNotBlank() }.transform { line ->
                if (line.isError) {
                    stdErr.append(line.text)
                    return@transform
                }
                if (useShadow) {
                    MypyOutputParser.parse(line.text).onSuccess { message ->
                        val virtualFile = targetsByPath[message.file] ?: VfsUtil.findFile(Path(message.file), false)
                        virtualFile?.let {
                            emit(it to message)
                        } ?: thisLogger().warn("Can't find VirtualFile at ${message.file}")
                    }.onFailure {
                        when (it) {
                            is MypyParseException -> {
                                thisLogger().warn(
                                    ZMypyBundle.message(
                                        "mypy.executable.parsing-result-failed", tool.displayName,
                                        executor.commandLine ?: ""
                                    ), it
                                )
                            }

                            else -> {
                                thisLogger().error(ZMypyBundle.message("mypy.please_report_this_issue"), it)
                            }
                        }
                    }
                } else {
                    when (val result = textParser.parse(line.text)) {
                        is ZubanParseResult.Issue -> {
                            val resolvedFile = resolveToolFile(result.message.file, configuration)
                            val virtualFile = targetsByPath[resolvedFile]
                                ?: VfsUtil.findFile(Path(resolvedFile), false)
                            virtualFile?.let {
                                emit(it to result.message.copy(file = resolvedFile))
                            } ?: thisLogger().warn("Can't find VirtualFile at $resolvedFile")
                        }

                        // "Found N errors..." / "Success: ..." are normal output, not errors
                        ZubanParseResult.Summary -> Unit
                        ZubanParseResult.Ignored -> Unit
                        is ZubanParseResult.Junk ->
                            thisLogger().warn("Unrecognized ${tool.displayName.lowercase()} output line: ${result.raw}")
                    }
                }
            }.onCompletion {
                // cleanup
                shadowedTargetMap.values.forEach { shadowFile -> shadowFile.deleteIfExists() }
                if (stdErr.isNotEmpty()) {
                    thisLogger().warn("mypy wrote to stderr: $stdErr")
                }
            }.catch(handleScanException(project, { executor.commandLine }, stdErr, MypyIncompleteConfigurationNotifier.getInstance(project), silent = true))
        return cs.future {
            flow.fold(mutableMapOf<VirtualFile, MutableList<MypyMessage>>()) { acc, (k, v) ->
                acc.getOrPut(k) { mutableListOf() }.add(v)
                acc
            }.mapValues { (_, v) -> v.toList() }
        }.get()
    }

    /** Zuban prints CWD-relative paths even for absolute targets; resolve against the working directory. */
    private fun resolveToolFile(file: String, configuration: ToolExecutorConfiguration): String {
        val path = Path(file)
        if (path.isAbsolute) return file
        val workingDirectory = configuration.workingDirectory ?: return file
        return Path(workingDirectory).resolve(path).normalize().toString()
    }

    private fun copyTempFrom(file: VirtualFile): Path {
        val document = requireNotNull(FileDocumentManager.getInstance().getCachedDocument(file)) {
            ZMypyBundle.message("mypy.please_report_this_issue")
        }
        val tempFile = kotlin.io.path.createTempFile(prefix = "pycharm_mypy_", suffix = "_" + file.name)
        tempFile.toFile().deleteOnExit()
        tempFile.writeText(document.charsSequence)
        return tempFile
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): SyncScanService = project.service()
    }
}
