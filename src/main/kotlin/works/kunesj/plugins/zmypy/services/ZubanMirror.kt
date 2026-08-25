package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VfsUtil
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * A temporary-directory mirror of the scan working directory used for real-time (unsaved)
 * annotation in zuban mode: zmypy has no --shadow-file, so the tree is mirrored instead —
 * clean files as links (symlink, hard link, or copy fallback), dirty files as real copies of
 * the in-memory document content. zmypy is then run with CWD = the mirror root and reports
 * CWD-relative paths, which map 1:1 back to the real files.
 */
@Service(Service.Level.PROJECT)
class ZubanMirror(private val project: Project) {

    private val lock = Any()
    private var root: Path? = null
    // relative path -> entry, so only changed mirror files are touched on each reconcile
    private val entries = HashMap<Path, Entry>()

    private enum class Kind { SYMLINK, HARDLINK, COPY_DISK, COPY_DOCUMENT }

    private class Entry(
        val kind: Kind,
        var documentHash: Int = 0,
        var diskMtime: Long = 0,
        var diskSize: Long = 0
    )

    /** The mirror root; created lazily, removed when the project closes or the IDE exits. */
    fun root(): Path = synchronized(lock) {
        root ?: Files.createTempDirectory("zmypy_mirror_").also {
            root = it
            it.toFile().deleteOnExit()
            Disposer.register(project) { runCatching { FileUtilRt.delete(it.toFile()) } }
        }
    }

    /**
     * Makes the mirror consistent with [realRoot]: links clean files, copies the in-memory
     * content of dirty (unsaved) files, and deletes entries whose real file is gone.
     * [excluded] are directory paths relative to [realRoot] that are skipped
     * (the same set passed to zmypy via --exclude).
     */
    fun reconcile(realRoot: Path, excluded: Collection<Path>) {
        synchronized(lock) {
            val mirrorRoot = root()
            val excludedDirs = excluded.map { it.normalize() }
                .filter { it.nameCount > 0 && !it.name.startsWith("..") }
            val seen = HashMap<Path, Entry>()
            try {
                Files.walkFileTree(realRoot, emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // only the exclusion check happens here; upsert() creates the
                        // mirror directories on demand, so empty dirs are never created
                        val rel = realRoot.relativize(dir)
                        if (isExcluded(rel, excludedDirs)) return FileVisitResult.SKIP_SUBTREE
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // without FOLLOW_LINKS a symlink reports its own attributes;
                        // isRegularFile follows it, so symlinks to files are mirrored too
                        if (isMirrorable(file) && (attrs.isRegularFile() || Files.isRegularFile(file))) {
                            upsert(realRoot.relativize(file), file, attrs, seen)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            } catch (e: IOException) {
                thisLogger().warn("Failed to reconcile the zmypy mirror of $realRoot", e)
            }
            for (stale in entries.keys - seen.keys) {
                runCatching { mirrorRoot.resolve(stale).deleteIfExists() }
            }
            entries.clear()
            entries.putAll(seen)
        }
    }

    private fun isExcluded(rel: Path, excludedDirs: List<Path>): Boolean =
        excludedDirs.any { rel == it || rel.startsWith(it) }

    // zmypy only reads Python sources (and PEP 561 markers) from the file system
    private fun isMirrorable(file: Path): Boolean {
        val n = file.name
        return n.endsWith(".py") || n.endsWith(".pyi") || n == "py.typed"
    }

    private fun upsert(rel: Path, realFile: Path, attrs: BasicFileAttributes, seen: HashMap<Path, Entry>) {
        val mirrorRoot = root()
        val target = mirrorRoot.resolve(rel)
        val virtualFile = VfsUtil.findFile(realFile, false)
        val documentManager = FileDocumentManager.getInstance()
        val dirty = virtualFile != null && documentManager.isFileModified(virtualFile)
        val document = if (dirty) documentManager.getCachedDocument(virtualFile!!) else null
        val existing = entries[rel]
        when {
            dirty && document != null -> {
                val content = document.charsSequence
                val hash = content.toString().hashCode()
                var written = existing?.kind == Kind.COPY_DOCUMENT && existing.documentHash == hash
                if (!written) {
                    written = runCatching {
                        target.deleteIfExists()
                        target.parent?.createDirectories()
                        target.writeText(content)
                        target.exists()
                    }.onFailure { thisLogger().warn("Failed to mirror unsaved content of $realFile", it) }.getOrDefault(false)
                }
                // an entry without the hash never counts as written, so a failed write retries
                seen[rel] = if (written) Entry(Kind.COPY_DOCUMENT, documentHash = hash) else Entry(Kind.COPY_DOCUMENT)
            }
            else -> upsertFromDisk(rel, realFile, attrs, existing, seen, target)
        }
    }

    private fun upsertFromDisk(
        rel: Path,
        realFile: Path,
        attrs: BasicFileAttributes,
        existing: Entry?,
        seen: HashMap<Path, Entry>,
        target: Path
    ) {
        val diskMtime = attrs.lastModifiedTime().toMillis()
        val diskSize = attrs.size()
        val diskFresh = existing != null && existing.diskMtime == diskMtime && existing.diskSize == diskSize
        if (existing?.kind == Kind.SYMLINK && target.exists()) {
            // symlinks always see the current on-disk content
            seen[rel] = existing
            return
        }
        if (diskFresh && existing != null && target.exists()) {
            // hard links share the inode; disk copies are refreshed when the file changes —
            // a mismatch means the file was replaced (e.g. by git) and must be relinked
            seen[rel] = existing
            return
        }
        val kind = runCatching {
            target.deleteIfExists()
            target.parent?.createDirectories()
            createLink(realFile, target) ?: Kind.COPY_DISK.also {
                Files.copy(realFile, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { thisLogger().warn("Failed to mirror $realFile", it) }.getOrNull()
        // an entry without disk markers never counts as fresh, so a failed upsert retries
        seen[rel] = if (kind != null && target.exists()) Entry(kind, diskMtime = diskMtime, diskSize = diskSize)
        else Entry(kind ?: Kind.COPY_DISK)
    }

    /** @return the kind of link created, or null when a plain copy was made instead. */
    private fun createLink(realFile: Path, target: Path): Kind? {
        try {
            Files.createSymbolicLink(target, realFile)
            return Kind.SYMLINK
        } catch (e: Exception) {
            // symlinks can be unavailable (Windows without developer mode) — hard links work
            // without privileges as long as both paths are on the same volume
            val parent = target.parent ?: return null
            return try {
                require(Files.getFileStore(parent) == Files.getFileStore(realFile))
                Files.createLink(target, realFile)
                Kind.HARDLINK
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ZubanMirror = project.service()
    }
}
