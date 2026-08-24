package works.kunesj.plugins.zmypy.activity

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import works.kunesj.plugins.zmypy.MypyTool
import works.kunesj.plugins.zmypy.services.MypySettings

private val SUPPORTED_FILE_EXTENSIONS = setOf("py", "pyi")

/**
 * In check-on-save mode (zuban) editor underlines only refresh when a document is saved,
 * because zmypy cannot analyze unsaved content. Re-run the highlighting daemon for saved
 * files that have an open editor.
 */
class DocumentSavedActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // FileDocumentManagerListener.TOPIC lives on the application message bus
        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun afterDocumentSaved(document: com.intellij.openapi.editor.Document) {
                    if (MypySettings.getInstance(project).tool != MypyTool.ZUBAN) return
                    val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return
                    if (virtualFile.extension !in SUPPORTED_FILE_EXTENSIONS) return
                    if (virtualFile is LightVirtualFile) return
                    // only bother for files with an open editor
                    if (FileEditorManager.getInstance(project).allEditors.none { it.file == virtualFile }) return
                    // cheap sync check, no process involved
                    if (!MypySettings.getInstance(project).isToolApplicable()) return
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
                    ApplicationManager.getApplication().invokeLater {
                        DaemonCodeAnalyzer.getInstance(project)
                            .restart(psiFile, "mypy-pycharm-plugin: file saved")
                    }
                }
            })
    }
}
