package works.kunesj.plugins.zmypy.services

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.run.ToolExecutor
import works.kunesj.plugins.common.run.pythonSdkToolProcessHandler
import works.kunesj.plugins.common.services.ToolExecutorConfiguration
import works.kunesj.plugins.zmypy.MypyTool

class MypyExecutor(private val project: Project, private val tool: MypyTool) :
    ToolExecutor(project, tool.moduleToRun ?: "mypy") {
    // exit code 1 should be fine https://github.com/python/mypy/issues/6003
    override fun isError(event: ProcessEvent): Boolean = event.exitCode > 2

    // zuban cannot be run as `python -m zuban`; run the SDK's own zmypy script instead
    override fun createProcessHandler(
        configuration: ToolExecutorConfiguration, parameters: List<String>
    ): OSProcessHandler = if (tool == MypyTool.ZUBAN && configuration.useProjectSdk) {
        pythonSdkToolProcessHandler(
            project,
            requireNotNull(tool.sdkScriptName),
            parameters,
            workingDir = configuration.workingDirectory
        )
    } else {
        super.createProcessHandler(configuration, parameters)
    }
}
