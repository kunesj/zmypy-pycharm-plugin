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
import kotlin.io.path.name
import kotlin.io.path.writeText

@Service(Service.Level.PROJECT)
class SyncScanService(private val project: Project, private val cs: CoroutineScope) {

    fun scan(
        targets: Collection<VirtualFile>, configuration: ToolExecutorConfiguration
    ): Map<VirtualFile, List<MypyMessage>> =
        if (MypySettings.getInstance(project).tool == MypyTool.ZUBAN)
            scanInMirror(targets, configuration)
        else
            scanWithShadowFiles(targets, configuration)

    /**
     * Mypy can check unsaved content via --shadow-file: each target is copied to a temp file
     * holding the in-memory document text and both paths are passed to mypy.
     */
    private fun scanWithShadowFiles(
        targets: Collection<VirtualFile>, configuration: ToolExecutorConfiguration
    ): Map<VirtualFile, List<MypyMessage>> {
        val tool = MypyTool.MYPY
        val shadowedTargetMap = targets.associateWith { copyTempFrom(it) }
        val targetsByPath = targets.associateBy { it.canonicalPath ?: it.path }
        val parameters = with(project) { buildMypyParamList(configuration, shadowedTargetMap, tool) }
        val stdErr = StringBuilder()
        val executor = MypyExecutor(project, tool)
        val flow: Flow<Pair<VirtualFile, MypyMessage>> =
            executor.execute(configuration, parameters).filter { it.text.isNotBlank() }.transform { line ->
                if (line.isError) {
                    stdErr.append(line.text)
                    return@transform
                }
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
            }.onCompletion {
                // cleanup
                shadowedTargetMap.values.forEach { shadowFile -> shadowFile.deleteIfExists() }
                if (stdErr.isNotEmpty()) {
                    thisLogger().warn("mypy wrote to stderr: $stdErr")
                }
            }.catch(handleScanException(project, { executor.commandLine }, stdErr, MypyIncompleteConfigurationNotifier.getInstance(project), silent = true))
        return runBlockingFlow(flow)
    }

    /**
     * zmypy has no --shadow-file: the working directory is mirrored into a temp dir where dirty
     * files are replaced by their in-memory content, and zmypy runs with CWD = the mirror root.
     * Its CWD-relative output paths map 1:1 back to the real files.
     */
    private fun scanInMirror(
        targets: Collection<VirtualFile>, configuration: ToolExecutorConfiguration
    ): Map<VirtualFile, List<MypyMessage>> {
        val workDir = Path(configuration.workingDirectory).normalize()
        val inScope = targets.filter { it.canonicalPath?.let { p -> Path(p).normalize().startsWith(workDir) } == true }
        if (inScope.isEmpty()) return emptyMap()
        val mirror = ZubanMirror.getInstance(project)
        val excluded = if (configuration.excludeNonProjectFiles) with(project) { excludedRelativePaths(inScope) } else emptyList()
        mirror.reconcile(workDir, excluded)
        val mirrorRoot = mirror.root()
        val mirrorConfiguration = configuration.copy(workingDirectory = mirrorRoot.toString())
        val parameters = with(project) {
            buildMypyParamList(
                configuration = mirrorConfiguration,
                targets = inScope,
                tool = MypyTool.ZUBAN,
                targetPath = { it.canonicalPath?.let { p -> mirrorRoot.resolve(workDir.relativize(Path(p).normalize()))?.toString() } ?: "" }
            )
        }
        val stdErr = StringBuilder()
        val executor = MypyExecutor(project, MypyTool.ZUBAN)
        val textParser = ZubanTextOutputParser()
        val targetsByPath = inScope.associateBy { it.canonicalPath ?: it.path }
        val flow: Flow<Pair<VirtualFile, MypyMessage>> =
            executor.execute(mirrorConfiguration, parameters).filter { it.text.isNotBlank() }.transform { line ->
                if (line.isError) {
                    stdErr.append(line.text)
                    return@transform
                }
                when (val result = textParser.parse(line.text)) {
                    is ZubanParseResult.Issue -> {
                        // resolve against the mirror CWD, then map back to the real path
                        val realPath = toRealPath(resolveToolFile(result.message.file, mirrorConfiguration), mirrorRoot, workDir)
                        val virtualFile = targetsByPath[realPath] ?: VfsUtil.findFile(Path(realPath), false)
                        virtualFile?.let {
                            emit(it to result.message.copy(file = realPath))
                        } ?: thisLogger().warn("Can't find VirtualFile at $realPath")
                    }

                    // "Found N errors..." / "Success: ..." are normal output, not errors
                    ZubanParseResult.Summary -> Unit
                    ZubanParseResult.Ignored -> Unit
                    is ZubanParseResult.Junk ->
                        thisLogger().warn("Unrecognized zuban output line: ${result.raw}")
                }
            }.onCompletion {
                if (stdErr.isNotEmpty()) {
                    thisLogger().warn("zmypy wrote to stderr: $stdErr")
                }
            }.catch(handleScanException(project, { executor.commandLine }, stdErr, MypyIncompleteConfigurationNotifier.getInstance(project), silent = true))
        return runBlockingFlow(flow)
    }

    private fun runBlockingFlow(flow: Flow<Pair<VirtualFile, MypyMessage>>): Map<VirtualFile, List<MypyMessage>> =
        cs.future {
            flow.fold(mutableMapOf<VirtualFile, MutableList<MypyMessage>>()) { acc, (k, v) ->
                acc.getOrPut(k) { mutableListOf() }.add(v)
                acc
            }.mapValues { (_, v) -> v.toList() }
        }.get()

    /** Zuban prints CWD-relative paths even for absolute targets; resolve against the working directory. */
    private fun resolveToolFile(file: String, configuration: ToolExecutorConfiguration): String {
        val path = Path(file)
        if (path.isAbsolute) return file
        val workingDirectory = configuration.workingDirectory ?: return file
        return Path(workingDirectory).resolve(path).normalize().toString()
    }

    private fun toRealPath(mirrorPath: String, mirrorRoot: Path, workDir: Path): String {
        val rel = runCatching { mirrorRoot.relativize(Path(mirrorPath)) }.getOrNull() ?: return mirrorPath
        if (rel.nameCount == 0 || rel.name.startsWith("..")) return mirrorPath
        return workDir.resolve(rel).normalize().toString()
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
