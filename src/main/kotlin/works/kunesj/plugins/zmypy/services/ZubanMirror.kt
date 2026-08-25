package works.kunesj.plugins.zmypy.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
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
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * A temporary-directory mirror of the scan working directory used for real-time (unsaved)
 * annotation in zuban mode: zmypy has no --shadow-file, so the tree is mirrored instead and
 * zmypy is run with CWD = the mirror root, whose CWD-relative output maps 1:1 back to the
 * real files.
 *
 * Clean directories are mirrored as live directory symlinks (one link reflects the current
 * content of the whole subtree, including files added, removed or changed on disk), so a
 * reconcile only touches: the files open in editors (the only ones that can be unsaved),
 * the directories on their paths, and the top level. Unsaved files become real copies of
 * their in-memory document content, and their ancestor directories are materialized (real
 * mirror directories with per-file links) until they become clean again.
 *
 * When directory symlinks are unavailable (e.g. Windows without developer mode) the mirror
 * falls back to linking/copying every file individually.
 */
@Service(Service.Level.PROJECT)
class ZubanMirror(private val project: Project) {

    private val lock = Any()
    private var root: Path? = null
    // relative path -> entry; a DIR_LINK entry has no entries below it (the link is live)
    private val entries = HashMap<Path, Entry>()
    // directories that are real (materialized) in the mirror; their children are in [entries]
    private val materializedDirs = HashSet<Path>()
    private var directoryLinksWork = true

    private enum class Kind { DIR_LINK, FILE_LINK, COPY_DOCUMENT }

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

    /** Makes the mirror consistent with [realRoot] before a real-time scan. */
    fun reconcile(realRoot: Path) {
        synchronized(lock) {
            if (!directoryLinksWork) {
                legacyReconcile(realRoot)
                return
            }
            val startedAt = System.nanoTime()
            val mirrorRoot = root()
            val candidates = dirtyCandidates(realRoot)
            for (candidate in candidates) {
                materializeAncestors(mirrorRoot, candidate)
                upsertCandidate(realRoot, mirrorRoot, candidate)
            }
            linkChildren(realRoot, mirrorRoot, null, candidates)
            for (dir in materializedDirs.toList()) {
                linkChildren(realRoot, mirrorRoot, dir, candidates)
            }
            pruneStale(realRoot, mirrorRoot)
            compact(realRoot, mirrorRoot, candidates)
            thisLogger().info(
                "ZMypy mirror: reconciled $realRoot in ${System.nanoTime() - startedAt} ns " +
                    "(unsaved=${candidates.size}, dirLinks=${entries.count { it.value.kind == Kind.DIR_LINK }}, " +
                    "materializedDirs=${materializedDirs.size}, fileEntries=${entries.count { it.value.kind != Kind.DIR_LINK }})"
            )
        }
    }

    // ------------------------------------------------------------------
    // directory-link mode
    // ------------------------------------------------------------------

    /** Files open in an editor, modified, and located under [realRoot] — the only ones that can be unsaved. */
    private fun dirtyCandidates(realRoot: Path): Set<Path> {
        val result = HashSet<Path>()
        val documentManager = FileDocumentManager.getInstance()
        for (editor in FileEditorManager.getInstance(project).allEditors) {
            val file = editor.file
            val canonicalPath = file.canonicalPath ?: continue
            if (!documentManager.isFileModified(file)) continue
            val rel = runCatching { realRoot.relativize(Path(canonicalPath).normalize()) }.getOrNull() ?: continue
            if (rel.nameCount == 0 || rel.startsWith("..")) continue
            result.add(rel)
        }
        return result
    }

    /** Replaces DIR_LINKs on the path to [fileRel] with real mirror directories. */
    private fun materializeAncestors(mirrorRoot: Path, fileRel: Path) {
        var dir: Path? = fileRel.parent
        while (dir != null && dir.nameCount > 0) {
            if (entries.remove(dir)?.kind == Kind.DIR_LINK) {
                runCatching { mirrorRoot.resolve(dir).deleteIfExists() }
            }
            materializedDirs.add(dir)
            dir = dir.parent
        }
    }

    /** Mirrors [rel]: as a copy of the in-memory content while unsaved, from disk otherwise. */
    private fun upsertCandidate(realRoot: Path, mirrorRoot: Path, rel: Path) {
        val real = realRoot.resolve(rel)
        val virtualFile = VfsUtil.findFile(real, false)
        val documentManager = FileDocumentManager.getInstance()
        val document = virtualFile?.takeIf { documentManager.isFileModified(it) }
            ?.let { documentManager.getCachedDocument(it) }
        if (document != null) {
            val content = document.charsSequence
            val hash = content.toString().hashCode()
            val existing = entries[rel]
            var written = existing?.kind == Kind.COPY_DOCUMENT && existing.documentHash == hash
            if (!written) {
                val target = mirrorRoot.resolve(rel)
                written = runCatching {
                    target.deleteIfExists()
                    target.parent?.createDirectories()
                    target.writeText(content)
                    target.exists()
                }.onFailure { thisLogger().warn("Failed to mirror unsaved content of $real", it) }.getOrDefault(false)
            }
            // an entry without the hash never counts as written, so a failed write retries
            entries[rel] = if (written) Entry(Kind.COPY_DOCUMENT, documentHash = hash) else Entry(Kind.COPY_DOCUMENT)
        } else {
            upsertFromDisk(real, mirrorRoot, rel)
        }
    }

    /**
     * Links the children of a mirror directory that is real in the mirror ([relDir] == null
     * means the mirror root): directories as live dir links (unless materialized or holding
     * an unsaved file), plain files as file links.
     */
    private fun linkChildren(realRoot: Path, mirrorRoot: Path, relDir: Path?, candidates: Set<Path>) {
        val realDir = relDir?.let { realRoot.resolve(it) } ?: realRoot
        try {
            Files.list(realDir).use { stream ->
                for (child in stream.toList()) {
                    val rel = relDir?.resolve(child.fileName) ?: realRoot.relativize(child)
                    if (entries.containsKey(rel)) continue
                    if (Files.isDirectory(child)) {
                        if (materializedDirs.contains(rel)) continue
                        if (candidates.any { it.startsWith(rel) }) continue
                        createDirLink(realRoot, mirrorRoot, child, rel)
                    } else if (Files.isRegularFile(child)) {
                        upsertFromDisk(child, mirrorRoot, rel)
                    }
                }
            }
        } catch (e: IOException) {
            // the directory vanished during the scan; it is pruned/recreated next time
        }
    }

    /** Replaces a stale materialized directory (no unsaved files, no live document copies) with a dir link. */
    private fun compact(realRoot: Path, mirrorRoot: Path, candidates: Set<Path>) {
        for (dir in materializedDirs.toList()) {
            if (candidates.any { it.startsWith(dir) }) continue
            val below = entries.keys.filter { it.startsWith(dir) }
            if (below.isEmpty()) {
                materializedDirs.remove(dir)
                continue
            }
            var keepMaterialized = false
            for (rel in below) {
                if (entries[rel]!!.kind == Kind.COPY_DOCUMENT) {
                    if (isStillDirty(realRoot.resolve(rel))) {
                        // still unsaved without an open editor — keep the copy in place
                        keepMaterialized = true
                        break
                    }
                    upsertFromDisk(realRoot.resolve(rel), mirrorRoot, rel)
                }
            }
            if (keepMaterialized) continue
            for (rel in below) {
                entries.remove(rel)
                runCatching { mirrorRoot.resolve(rel).deleteIfExists() }
            }
            // recursive-tolerant: the dir should be empty, but never leave a dir where the link goes
            runCatching { FileUtilRt.delete(mirrorRoot.resolve(dir).toFile()) }
            if (createDirLink(realRoot, mirrorRoot, realRoot.resolve(dir), dir)) {
                materializedDirs.remove(dir)
            }
        }
    }

    private fun isStillDirty(realFile: Path): Boolean {
        val virtualFile = VfsUtil.findFile(realFile, false) ?: return false
        return FileDocumentManager.getInstance().isFileModified(virtualFile)
    }

    private fun pruneStale(realRoot: Path, mirrorRoot: Path) {
        for (rel in entries.keys.toList()) {
            val real = realRoot.resolve(rel)
            val isAlive = if (entries[rel]!!.kind == Kind.DIR_LINK) Files.isDirectory(real)
            else Files.isRegularFile(real)
            if (!isAlive) {
                entries.remove(rel)
                runCatching { mirrorRoot.resolve(rel).deleteIfExists() }
            }
        }
        materializedDirs.removeAll { !Files.isDirectory(realRoot.resolve(it)) }
    }

    /** @return false (and the subtree is walked per-file) when a dir link cannot be created. */
    private fun createDirLink(realRoot: Path, mirrorRoot: Path, realDir: Path, rel: Path): Boolean {
        val target = mirrorRoot.resolve(rel)
        return try {
            target.parent?.createDirectories()
            Files.createSymbolicLink(target, realDir)
            entries[rel] = Entry(Kind.DIR_LINK)
            true
        } catch (e: Exception) {
            // a top-level failure is systemic (e.g. Windows without developer mode) — the
            // whole mirror switches to the per-file fallback from the next scan on
            if (rel.nameCount == 1) directoryLinksWork = false
            thisLogger().warn("Failed to create a directory link for $realDir", e)
            legacyWalk(realRoot, mirrorRoot, realDir, dirtyCandidates(realRoot))
            false
        }
    }

    // ------------------------------------------------------------------
    // per-file fallback (also used for a single subtree that cannot be dir-linked)
    // ------------------------------------------------------------------

    /** Full per-file reconcile: links every mirrorable file, copies unsaved ones. */
    private fun legacyReconcile(realRoot: Path) {
        val startedAt = System.nanoTime()
        val mirrorRoot = root()
        val candidates = dirtyCandidates(realRoot)
        legacyWalk(realRoot, mirrorRoot, realRoot, candidates)
        for (rel in entries.keys.toList().filter { it.nameCount > 0 }) {
            if (!Files.isRegularFile(realRoot.resolve(rel)) && entries[rel]!!.kind != Kind.COPY_DOCUMENT) {
                entries.remove(rel)
                runCatching { mirrorRoot.resolve(rel).deleteIfExists() }
            }
        }
        thisLogger().info(
            "ZMypy mirror (per-file): reconciled $realRoot in ${System.nanoTime() - startedAt} ns " +
                "(entries=${entries.size}, unsaved=${candidates.size})"
        )
    }

    /** Walks [startReal] and mirrors every file individually into [entries]. */
    private fun legacyWalk(
        realRoot: Path,
        mirrorRoot: Path,
        startReal: Path,
        candidates: Set<Path>
    ) {
        val startRel = realRoot.relativize(startReal)
        val seen = HashSet<Path>()
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = realRoot.relativize(dir)
                if (rel.nameCount > 0) {
                    materializedDirs.add(rel)
                    val mirrorDir = mirrorRoot.resolve(rel)
                    // never create through a stale dir link — that would write into the real tree
                    if (Files.isSymbolicLink(mirrorDir)) {
                        runCatching { mirrorDir.deleteIfExists() }
                    }
                    runCatching { mirrorDir.createDirectories() }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isMirrorable(file) && (attrs.isRegularFile() || Files.isRegularFile(file))) {
                    val rel = realRoot.relativize(file)
                    seen.add(rel)
                    if (candidates.contains(rel)) {
                        upsertCandidate(realRoot, mirrorRoot, rel)
                    } else {
                        upsertFromDisk(file, mirrorRoot, rel)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
        try {
            Files.walkFileTree(startReal, emptySet(), Int.MAX_VALUE, visitor)
        } catch (e: IOException) {
            thisLogger().warn("Failed to reconcile the zmypy mirror of $startReal", e)
        }
        val scope = if (startRel.nameCount == 0) entries.keys
        else entries.keys.filter { it.startsWith(startRel) && it.nameCount > startRel.nameCount }
        for (stale in scope - seen) {
            entries.remove(stale)
            runCatching { mirrorRoot.resolve(stale).deleteIfExists() }
        }
    }

    // ------------------------------------------------------------------
    // shared file-level mirroring
    // ------------------------------------------------------------------

    // zmypy only reads Python sources (and PEP 561 markers) from the file system
    private fun isMirrorable(file: Path): Boolean {
        val n = file.fileName.toString()
        return n.endsWith(".py") || n.endsWith(".pyi") || n == "py.typed"
    }

    /** Mirrors a clean [realFile] as a link (symlink, hard link, or copy fallback). */
    private fun upsertFromDisk(realFile: Path, mirrorRoot: Path, rel: Path) {
        val target = mirrorRoot.resolve(rel)
        val attrs = runCatching { Files.readAttributes(realFile, BasicFileAttributes::class.java) }.getOrNull()
            ?: return
        val diskMtime = attrs.lastModifiedTime().toMillis()
        val diskSize = attrs.size()
        val existing = entries[rel]
        if (existing?.kind == Kind.FILE_LINK && target.exists() &&
            existing.diskMtime == diskMtime && existing.diskSize == diskSize
        ) {
            return
        }
        val entry = runCatching {
            target.deleteIfExists()
            target.parent?.createDirectories()
            if (!createFileLink(realFile, target)) {
                Files.copy(realFile, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Entry(Kind.FILE_LINK, diskMtime = diskMtime, diskSize = diskSize)
        }.onFailure { thisLogger().warn("Failed to mirror $realFile", it) }.getOrNull()
        // an entry without disk markers never counts as fresh, so a failed upsert retries
        entries[rel] = entry?.takeIf { target.exists() } ?: Entry(Kind.FILE_LINK)
    }

    /** @return true when a link was created, false when a copy must be made instead. */
    private fun createFileLink(realFile: Path, target: Path): Boolean {
        try {
            Files.createSymbolicLink(target, realFile)
            return true
        } catch (e: Exception) {
            // symlinks can be unavailable (Windows without developer mode) — hard links work
            // without privileges as long as both paths are on the same volume
            val parent = target.parent ?: return false
            return try {
                require(Files.getFileStore(parent) == Files.getFileStore(realFile))
                Files.createLink(target, realFile)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ZubanMirror = project.service()
    }
}
