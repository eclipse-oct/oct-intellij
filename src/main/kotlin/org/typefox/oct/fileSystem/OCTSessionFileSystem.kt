package org.typefox.oct.fileSystem

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem
import com.jetbrains.rd.util.remove
import org.typefox.oct.*
import org.typefox.oct.messageHandlers.FileSystemMessageHandler
import org.typefox.oct.messageHandlers.OCTMessageHandler
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.pathString

class OCTSessionFileSystem() : NewVirtualFileSystem() {

    protected val roots: MutableMap<String, OCTSessionRootFile> = mutableMapOf()

    fun registerRoots(roots: Array<String>, collaborationInstance: CollaborationInstance) {
        for (root in roots) {
            if (this.roots.containsKey(root)) {
                throw IllegalArgumentException("Root already registered: $root")
            }
            this.roots[root] = OCTSessionRootFile(
                root,
                this,
                collaborationInstance.project,
                collaborationInstance
            )
        }
        Disposer.register(collaborationInstance) {
            for (root in roots) {
                this.roots.remove(root)
            }
        }
    }

    override fun getProtocol(): String = "oct"

    override fun findFileByPath(pathString: String): VirtualFile? {
        val path = Path(pathString)
        if (!roots.contains(path.getName(0).name)) return null

        var current: VirtualFile? = null
        for (segment in path.iterator()) {
            current = if (current == null) roots[segment.name]
                      else current.findChild(segment.name) ?: return null
        }
        return current
    }

    override fun findFileByPathIfCached(pathString: String): NewVirtualFile? =
        findFileByPath(pathString) as? NewVirtualFile

    override fun refresh(asynchronous: Boolean) {
        roots.forEach { it.value.refresh(true, true) }
    }

    override fun refreshAndFindFileByPath(pathString: String): VirtualFile? {
        val path = Path(pathString)
        findFileByPath(path.parent.toString())?.refresh(false, false)
        val file = findFileByPath(pathString)
        file?.refresh(true, true)
        return file
    }

    // NewVirtualFileSystem: delegate to the VirtualFile's own properties/methods

    override fun list(file: VirtualFile): Array<String> =
        file.children.map { it.name }.toTypedArray()

    override fun exists(file: VirtualFile): Boolean =
        findFileByPath(file.path) != null

    override fun isDirectory(file: VirtualFile): Boolean = file.isDirectory

    override fun getTimeStamp(file: VirtualFile): Long = file.timeStamp

    override fun setTimeStamp(file: VirtualFile, timeStamp: Long) { /* read-only timestamps */ }

    override fun isWritable(file: VirtualFile): Boolean = file.isWritable

    override fun setWritable(file: VirtualFile, writableFlag: Boolean) { /* not supported */ }

    override fun isSymLink(file: VirtualFile): Boolean = false

    override fun resolveSymLink(file: VirtualFile): String? = null

    override fun isCaseSensitive(): Boolean = true

    override fun extractRootPath(pathString: String): String {
        val normalized = pathString.replace("\\", "/")
        val slashIndex = normalized.indexOf('/')
        return if (slashIndex < 0) normalized else normalized.substring(0, slashIndex + 1)
    }

    override fun addVirtualFileListener(listener: VirtualFileListener) {}

    override fun removeVirtualFileListener(listener: VirtualFileListener) {}

    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {
        val path = Path(vFile.path)
        getRemoteFilesystemService(path)?.delete(toOctPath(path), getHostId(path))?.get()
    }

    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {
        val oldPath = Path(vFile.path)
        val newPath = Path(newParent.findChild(vFile.name)!!.path)
        getRemoteFilesystemService(oldPath)?.rename(toOctPath(oldPath), toOctPath(newPath), getHostId(oldPath))?.get()
    }

    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {
        val oldPath = Path(vFile.path)
        val newPath = oldPath.parent.resolve(newName)
        getRemoteFilesystemService(oldPath)?.rename(toOctPath(oldPath), toOctPath(newPath), getHostId(oldPath))?.get()
    }

    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile {
        val parentPath = Path(vDir.path)
        getRemoteFilesystemService(parentPath)?.writeFile(
            toOctPath(parentPath.resolve(fileName)), FileContent(ByteArray(0)), getHostId(parentPath))?.get()
        vDir.refresh(false, false)
        return vDir.findChild(fileName) ?: throw IllegalStateException("File not found after creation")
    }

    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile {
        val parentPath = Path(vDir.path)
        getRemoteFilesystemService(parentPath)?.mkdir(toOctPath(parentPath.resolve(dirName)), getHostId(parentPath))?.get()
        vDir.refresh(false, false)
        return vDir.findChild(dirName) ?: throw IllegalStateException("Directory not found after creation")
    }

    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        val oldPath = Path(virtualFile.path)
        val parentPath = Path(newParent.path)
        val oldContent = readFile(oldPath).get()
            ?: throw IllegalStateException("Could not read file ${virtualFile.path} from host")
        getRemoteFilesystemService(oldPath)?.writeFile(
            toOctPath(parentPath.resolve(copyName)), oldContent, getHostId(parentPath))?.get()
        newParent.refresh(false, false)
        return newParent.findChild(copyName) ?: throw IllegalStateException("File not found after copy")
    }

    override fun isReadOnly(): Boolean = false

    fun stat(path: Path): CompletableFuture<FileSystemStat?>? {
        val service = getRemoteFilesystemService(path)
        return service?.stat(toOctPath(path), getHostId(path))
    }

    fun readFile(path: Path): CompletableFuture<FileContent?> {
        val service = getRemoteFilesystemService(path) as OCTMessageHandler.OCTService
        return service.getDocumentContent(toOctPath(path))
    }

    fun readDir(path: Path): CompletableFuture<Map<String, FileType>>? {
        val service = getRemoteFilesystemService(path)
        return service?.readDir(toOctPath(path), getHostId(path))
    }

    private fun getRemoteFilesystemService(path: Path): FileSystemMessageHandler.FileSystemService? =
        roots[path.getName(0).name]?.collaborationInstance?.remoteInterface as FileSystemMessageHandler.FileSystemService?

    private fun getHostId(path: Path): String =
        roots[path.getName(0).name]?.collaborationInstance?.host?.id ?: ""
}

fun toOctPath(path: Path): String = path.toString().replace("\\", "/")

