package org.typefox.oct.fileSystem

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import org.typefox.oct.*
import org.typefox.oct.messageHandlers.FileSystemMessageHandler
import java.io.FileNotFoundException
import java.util.concurrent.CompletableFuture
import kotlin.io.path.Path

@Service(Service.Level.PROJECT)
class WorkspaceFileSystemService(private val project: Project) : SessionPathMapper {

    private var roots: Map<String, VirtualFile> = emptyMap()

    /**
     * Selects the roots to share for this session: the project base dir, plus any module content
     * root that isn't nested inside an already-selected root (so a project with submodules shares
     * one root, not one per module - see eclipse-oct/oct-intellij#3), with root names uniquified.
     */
    fun createWorkspace(): Workspace {
        val candidates = LinkedHashSet<VirtualFile>()
        project.basePath?.let { basePath ->
            VirtualFileManager.getInstance().findFileByNioPath(Path(basePath))?.let { candidates.add(it) }
        }
        for (module in project.modules) {
            candidates.addAll(ModuleRootManager.getInstance(module).contentRoots)
        }

        val prunedRoots = mutableListOf<VirtualFile>()
        for (candidate in candidates.sortedBy { it.path.length }) {
            if (prunedRoots.none { VfsUtilCore.isAncestor(it, candidate, false) }) {
                prunedRoots.add(candidate)
            }
        }

        val newRoots = LinkedHashMap<String, VirtualFile>()
        for (root in prunedRoots) {
            var name = root.name
            var suffix = 2
            while (newRoots.containsKey(name)) {
                name = "${root.name}-${suffix++}"
            }
            newRoots[name] = root
        }
        roots = newRoots

        return Workspace(project.name, newRoots.keys.toTypedArray())
    }

    fun stat(path: String): FileSystemStat? {
        val file = toVirtualFile(path) ?: return null
        if (isExcluded(file)) return null
        return FileSystemStat(getFileType(file), file.modificationStamp, file.timeStamp, file.length, null)
    }

    fun readFile(path: String): FileContent? {
        val file = toVirtualFile(path) ?: return null
        if (isExcluded(file)) return null
        return FileContent(file.contentsToByteArray())
    }

    fun readDir(path: String): Map<String, FileType> {
        val file = toVirtualFile(path) ?: return mapOf()
        if (!file.isDirectory) return mapOf()

        return file.children
            .filter { !isExcluded(it) }
            .associate { it.name to getFileType(it) }
    }

    fun mkdir(pathString: String): CompletableFuture<Unit> {
        return this.runAsyncInWriteContext {
            val (parentPath, name) = splitParent(pathString)
            val parentDir = toVirtualFile(parentPath)
                ?: throw FileNotFoundException("could not find parent directory for $pathString")
            parentDir.createChildDirectory(this, name)
        }
    }

    fun writeFile(pathString: String, fileData: FileContent): CompletableFuture<Unit> {
        return this.runAsyncInWriteContext {
            val file = toVirtualFile(pathString) ?: run {
                val (parentPath, name) = splitParent(pathString)
                val parentDir = toVirtualFile(parentPath)
                    ?: throw FileNotFoundException("could not find parent directory for $pathString")
                parentDir.createChildData(this, name)
            }
            file.writeBytes(fileData.content)
        }
    }

    fun delete(path: String): CompletableFuture<Unit> {
        return this.runAsyncInWriteContext {
            val file = toVirtualFile(path) ?: throw FileNotFoundException("could not find file or directory at $path")
            file.delete(file.fileSystem)
        }
    }

    fun rename(path: String, newName: String): CompletableFuture<Unit> {
        return this.runAsyncInWriteContext {
            val file = toVirtualFile(path) ?: throw FileNotFoundException("could not find file at $path")
            file.rename("externalUser", newName)
        }
    }

    private fun getFileType(file: VirtualFile): FileType {
        if (file.isRecursiveOrCircularSymlink) {
            return FileType.SymbolicLink
        } else if (file.isDirectory) {
            return FileType.Directory
        } else if (file.isFile) {
            return FileType.File
        } else {
            return FileType.Unknown
        }
    }

    override fun toVirtualFile(protocolPath: String): VirtualFile? {
        val slashIndex = protocolPath.indexOf('/')
        val rootName = if (slashIndex < 0) protocolPath else protocolPath.substring(0, slashIndex)
        val rest = if (slashIndex < 0) "" else protocolPath.substring(slashIndex + 1)

        val root = roots[rootName] ?: return null
        val file = if (rest.isEmpty()) root else (root.findFileByRelativePath(rest) ?: return null)

        // Guards against path traversal escaping the shared root (e.g. "root/../../etc/passwd").
        return if (VfsUtilCore.isAncestor(root, file, false)) file else null
    }

    override fun toProtocolPath(file: VirtualFile): String? {
        for ((name, root) in roots) {
            if (VfsUtilCore.isAncestor(root, file, false)) {
                val rel = VfsUtilCore.getRelativePath(file, root)
                return if (rel.isNullOrEmpty()) name else "$name/$rel"
            }
        }
        return null
    }

    /** String-based variant used by [OCTFileListener], which only has a raw path, not a (possibly already-deleted) VirtualFile. */
    fun toProtocolPath(absolutePath: String): String? {
        val normalized = absolutePath.replace("\\", "/")
        for ((name, root) in roots) {
            val rootPath = root.path
            if (normalized == rootPath) return name
            if (normalized.startsWith("$rootPath/")) return "$name/${normalized.substring(rootPath.length + 1)}"
        }
        return null
    }

    private fun splitParent(protocolPath: String): Pair<String, String> {
        val slashIndex = protocolPath.lastIndexOf('/')
        require(slashIndex >= 0) { "Cannot resolve parent for root-level path: $protocolPath" }
        return protocolPath.substring(0, slashIndex) to protocolPath.substring(slashIndex + 1)
    }

    private fun isExcluded(file: VirtualFile): Boolean =
        ApplicationManager.getApplication().runReadAction<Boolean> {
            ProjectFileIndex.getInstance(project).isExcluded(file)
        }

    private fun <T> runAsyncInWriteContext(action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                future.complete(action())
            }
        }
        return future
    }
}

class OCTFileListener : BulkFileListener {
    override fun after(events: MutableList<out VFileEvent>) {
        val octSessionService = service<OCTSessionService>()
        val projectChangeEvents: MutableMap<Project, MutableList<FileChange>> = mutableMapOf()

        events.forEach { event ->
            for ((octProject, session) in octSessionService.currentCollaborationInstances) {
                if (!session.isHost) continue

                val changes = computeChanges(session.workspaceFileSystem, event)
                if (changes.isNotEmpty()) {
                    projectChangeEvents.getOrPut(octProject) { mutableListOf() }.addAll(changes)
                    break
                }
            }
        }

        for ((project, changes) in projectChangeEvents) {
            val octSession = octSessionService.currentCollaborationInstances[project]
            if (octSession != null) {
                (octSession.remoteInterface as FileSystemMessageHandler.FileSystemService)
                    .change(FileChangeEvent(changes.toTypedArray()), "broadcast")
            }
        }
    }

    private fun computeChanges(fs: WorkspaceFileSystemService, event: VFileEvent): List<FileChange> {
        fun change(path: String?, type: FileChangeEventType) = path?.let { FileChange(type, it) }

        return when (event) {
            is VFileCreateEvent -> listOfNotNull(change(fs.toProtocolPath(event.path), FileChangeEventType.Create))
            is VFileDeleteEvent -> listOfNotNull(change(fs.toProtocolPath(event.path), FileChangeEventType.Delete))
            is VFileMoveEvent -> listOfNotNull(
                change(fs.toProtocolPath(event.oldPath), FileChangeEventType.Delete),
                change(fs.toProtocolPath(event.newPath), FileChangeEventType.Create)
            )
            is VFileCopyEvent -> listOfNotNull(
                change(
                    fs.toProtocolPath(event.findCreatedFile()?.path ?: "${event.newParent.path}/${event.newChildName}"),
                    FileChangeEventType.Create
                )
            )
            is VFilePropertyChangeEvent -> listOfNotNull(change(fs.toProtocolPath(event.path), FileChangeEventType.Update))
            is VFileContentChangeEvent -> listOfNotNull(change(fs.toProtocolPath(event.path), FileChangeEventType.Update))
            else -> emptyList()
        }
    }
}
