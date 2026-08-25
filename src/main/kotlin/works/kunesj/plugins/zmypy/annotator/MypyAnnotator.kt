package works.kunesj.plugins.zmypy.annotator

import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.annotator.ToolAnnotator
import works.kunesj.plugins.common.services.ToolExecutorConfiguration
import works.kunesj.plugins.zmypy.services.MypySettings
import works.kunesj.plugins.zmypy.services.SyncScanService
import works.kunesj.plugins.zmypy.services.parser.MypyMessage

class MypyAnnotator : ToolAnnotator<MypyMessage>() {
    override val inspectionId = MypyInspectionId

    override fun getSettings(project: Project) = MypySettings.getInstance(project)

    override fun scan(info: AnnotatorInfo, configuration: ToolExecutorConfiguration): List<MypyMessage> =
        SyncScanService.getInstance(info.project).scan(listOf(info.file), configuration)[info.file] ?: emptyList()

    override fun createIntention(message: MypyMessage) = MypyIgnoreIntention(message)
}
