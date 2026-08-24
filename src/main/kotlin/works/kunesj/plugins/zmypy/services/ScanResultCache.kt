package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import works.kunesj.plugins.zmypy.services.parser.MypyMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches real-time scan results per file so that a dirty (unsaved) document can be
 * re-annotated without spawning a new process (check-on-save mode).
 * Entries are invalidated implicitly: the config hash in the key changes when settings change.
 */
@Service(Service.Level.PROJECT)
class ScanResultCache {
    // key: file canonical path; value: (configHash, messages)
    private val results = ConcurrentHashMap<String, Pair<String, List<MypyMessage>>>()

    fun get(file: VirtualFile, configHash: String): List<MypyMessage>? =
        results[fileKey(file)]?.takeIf { it.first == configHash }?.second

    fun put(file: VirtualFile, configHash: String, messages: List<MypyMessage>) {
        results[fileKey(file)] = configHash to messages.toList()
    }

    private fun fileKey(file: VirtualFile): String = file.canonicalPath ?: file.path

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ScanResultCache = project.service()
    }
}

/**
 * Hash of the settings that influence a scan result; used to invalidate [ScanResultCache] entries.
 */
object ConfigHash {
    fun hash(project: Project): String {
        val settings = MypySettings.getInstance(project)
        return listOf(
            settings.tool.name,
            settings.executablePath,
            settings.useProjectSdk.toString(),
            settings.configFilePath,
            settings.arguments,
            settings.workingDirectory ?: "",
            settings.excludeNonProjectFiles.toString()
        ).joinToString("\u0000").hashCode().toString()
    }
}
