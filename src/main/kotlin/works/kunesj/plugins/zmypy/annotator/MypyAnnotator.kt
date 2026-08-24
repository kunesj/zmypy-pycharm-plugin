package works.kunesj.plugins.zmypy.annotator

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import works.kunesj.plugins.common.annotator.ToolAnnotator
import works.kunesj.plugins.common.services.ToolExecutorConfiguration
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.ConfigHash
import works.kunesj.plugins.zmypy.services.MypySettings
import works.kunesj.plugins.zmypy.services.ScanResultCache
import works.kunesj.plugins.zmypy.services.SyncScanService
import works.kunesj.plugins.zmypy.services.parser.MypyMessage

class MypyAnnotator : ToolAnnotator<MypyMessage>() {
    override val inspectionId = MypyInspectionId

    override fun getSettings(project: Project) = MypySettings.getInstance(project)

    override fun doAnnotate(info: AnnotatorInfo): List<MypyMessage> {
        // zuban cannot see unsaved content (no --shadow-file): while the document is dirty,
        // show the last cached (saved-state) result instead of spawning a process
        if (MypySettings.getInstance(info.project).tool == MypyTool.ZUBAN) {
            if (FileDocumentManager.getInstance().isFileModified(info.file)) {
                return ScanResultCache.getInstance(info.project)
                    .get(info.file, ConfigHash.hash(info.project)) ?: emptyList()
            }
        }
        return super.doAnnotate(info)
    }

    override fun scan(info: AnnotatorInfo, configuration: ToolExecutorConfiguration): List<MypyMessage> {
        val messages =
            SyncScanService.getInstance(info.project).scan(listOf(info.file), configuration)[info.file] ?: emptyList()
        if (MypySettings.getInstance(info.project).tool == MypyTool.ZUBAN) {
            ScanResultCache.getInstance(info.project).put(info.file, ConfigHash.hash(info.project), messages)
        }
        return messages
    }

    override fun createIntention(message: MypyMessage) = MypyIgnoreIntention(message)
}
