package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.virtualFile
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.text.nullize
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
    tool: MypyTool
) = with(configuration) {
    val params = tool.mandatoryArgs.toMutableList()
    configFilePath.nullize(true)?.let { params.add("--config-file"); params.add(it) }
    arguments.nullize(true)?.let { params.addAll(ParametersListUtil.parse(it)) }
    if (excludeNonProjectFiles) {
        Exclusions(project).findAll(targets).mapNotNull { getRelativePathFromContentRoot(it, project)?.toCanonicalPath() }
            .forEach { params.add("--exclude"); params.add(it) }
    }
    params.addAll(extraArgs)
    params.addAll(targets.map { requireNotNull(it.canonicalPath) })
    params
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
