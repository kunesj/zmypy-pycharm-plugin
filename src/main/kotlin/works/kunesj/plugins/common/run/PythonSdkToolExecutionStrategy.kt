package works.kunesj.plugins.common.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.jetbrains.python.console.addDefaultEnvironments
import works.kunesj.plugins.common.CommonBundle
import works.kunesj.plugins.common.resolveModulePythonSdkNow
import java.nio.file.Path

/**
 * Runs a tool executable from the selected SDK environment (e.g. `<venv>/bin/zmypy`),
 * instead of `python -m <module>`. Use for tools whose Python package cannot be
 * invoked as a module.
 */
fun pythonSdkToolProcessHandler(
    project: Project,
    toolName: String,
    parameters: List<String> = emptyList(),
    envs: Map<String, String> = emptyMap(),
    workingDir: String?
): OSProcessHandler {
    val sdk = requireNotNull(project.resolveModulePythonSdkNow()) { CommonBundle.message("tool_executor.python_sdk_null") }
    val sdkHome = requireNotNull(sdk.homePath) { CommonBundle.message("tool_executor.python_sdk_null") }
    val toolPath = if (SystemInfo.isWindows) {
        // venv layout: <venv>\python.exe + <venv>\Scripts\<tool>.exe
        Path.of(sdkHome).resolveSibling("Scripts").resolve("$toolName.exe")
    } else {
        // venv layout: <venv>/bin/python + <venv>/bin/<tool>
        Path.of(sdkHome).resolveSibling(toolName)
    }
    val patchedEnvs = addDefaultEnvironments(sdk, envs.toMutableMap())
    val commandLine = GeneralCommandLine().apply {
        withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        withWorkingDirectory(workingDir?.let { Path.of(it) })
        withExePath(toolPath.toString())
        withParameters(parameters)
        withEnvironment(patchedEnvs)
    }
    return ToolProcessHandler(commandLine)
}
