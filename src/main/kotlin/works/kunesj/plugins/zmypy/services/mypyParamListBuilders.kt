package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.virtualFile
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.text.nullize
import works.kunesj.plugins.common.resolveModulePythonSdkNow
import works.kunesj.plugins.common.run.Exclusions
import works.kunesj.plugins.common.services.ToolExecutorConfiguration
import works.kunesj.plugins.zmypy.MypyTool
import java.nio.file.Path
import kotlin.io.path.pathString

// shadow files are a mypy-only feature (zmypy has no --shadow-file)
context(project: Project)
fun buildMypyParamList(
    configuration: ToolExecutorConfiguration, shadowMap: Map<VirtualFile, Path>, tool: MypyTool
): List<String> {
    require(tool == MypyTool.MYPY) { "shadow files are only supported for mypy" }
    val shadowParameters = shadowMap.flatMap { (shadowedOriginal, shadowCastingOne) ->
        listOf("--shadow-file", shadowedOriginal.path, shadowCastingOne.pathString)
    }
    return buildMypyParamList(configuration, shadowMap.keys, shadowParameters, tool)
}

context(project: Project)
fun buildMypyParamList(
    configuration: ToolExecutorConfiguration,
    targets: Collection<VirtualFile>,
    extraArgs: Collection<String> = emptyList(),
    tool: MypyTool,
    targetPath: (VirtualFile) -> String = { requireNotNull(it.canonicalPath) }
) = with(configuration) {
    val params = tool.mandatoryArgs.toMutableList()
    configFilePath.nullize(true)?.let { params.add("--config-file"); params.add(it) }
    arguments.nullize(true)?.let { params.addAll(ParametersListUtil.parse(it)) }
    if (excludeNonProjectFiles) {
        excludedRelativePaths(targets).map { it.toCanonicalPath() }
            .forEach { params.add("--exclude"); params.add(it) }
    }
    params.addAll(extraArgs)
    params.addAll(targets.map { targetPath(it) })
    params
}

// mypy's `--exclude` doesn't work with absolute paths; relative to the content root
context(project: Project)
fun excludedRelativePaths(targets: Collection<VirtualFile>): List<Path> =
    Exclusions(project).findAll(targets).mapNotNull { getRelativePathFromContentRoot(it, project) }

private val VENV_DIR_NAMES = listOf(".venv", "venv", ".env", "env")
private val PYTHON_EXE_NAME = if (SystemInfo.isWindows) "Scripts/python.exe" else "bin/python"

/**
 * The Python interpreter zmypy should use for a mirror run. zmypy's own venv discovery
 * (walking up from the target looking for pyvenv.cfg) does not work inside the mirror —
 * the linked venv is skipped — so the environment has to be passed explicitly via
 * --python-executable, otherwise third-party imports resolve against the system Python.
 */
context(project: Project)
fun mirrorPythonExecutable(settings: MypySettings, targetFile: Path): String? {
    if (settings.useProjectSdk) {
        val homePath = project.resolveModulePythonSdkNow()?.homePath ?: return null
        return homePath.takeIf { java.nio.file.Files.isRegularFile(kotlin.io.path.Path(it)) }
    }
    var dir = targetFile.parent
    while (dir != null) {
        for (name in VENV_DIR_NAMES) {
            val venv = dir.resolve(name)
            if (java.nio.file.Files.isRegularFile(venv.resolve("pyvenv.cfg"))) {
                return venv.resolve(PYTHON_EXE_NAME).toString()
            }
        }
        dir = dir.parent
    }
    val executable = settings.executablePath
    if (executable.isNotBlank()) {
        val executableVenv = kotlin.io.path.Path(executable).parent?.parent
        if (executableVenv != null && java.nio.file.Files.isRegularFile(executableVenv.resolve("pyvenv.cfg"))) {
            return executableVenv.resolve(PYTHON_EXE_NAME).toString()
        }
    }
    return null
}

// mypy's `--exclude` doesn't work with absolute paths
private fun getRelativePathFromContentRoot(excludeUrlEntity: ExcludeUrlEntity, project: Project): Path? {
    val exclusionPath = excludeUrlEntity.url.virtualFile?.path?.let { kotlin.io.path.Path(it) } ?: return null
    val contentRootPath = WorkspaceModel.getInstance(project).currentSnapshot
        .entities(ContentRootEntity::class.java)
        .firstOrNull { contentRoot -> contentRoot.excludedUrls.contains(excludeUrlEntity) }
        ?.url?.virtualFile?.path?.let { kotlin.io.path.Path(it) } ?: return null
    return contentRootPath.relativize(exclusionPath)
}
