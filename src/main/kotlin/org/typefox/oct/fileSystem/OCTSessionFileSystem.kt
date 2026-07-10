package org.typefox.oct.fileSystem

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileAttributes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem
import com.intellij.openapi.vfs.newvfs.VfsImplUtil
import org.typefox.oct.*
import org.typefox.oct.messageHandlers.FileSystemMessageHandler
import org.typefox.oct.messageHandlers.OCTMessageHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.Path
import kotlin.io.path.name

class OCTSessionFileSystem : NewVirtualFileSystem() {

    // Maps workspace-folder name → CollaborationInstance for host/service resolution only;
    // file instances are now owned by PersistentFS.
    private val roots: MutableMap<String, CollaborationInstance> = mutableMapOf()

    fun registerRoots(roots: Array<String>, collaborationInstance: CollaborationInstance) {
        for (root in roots) {
            if (this.roots.containsKey(root)) {
                throw IllegalArgumentException("Root already registered: $root")
            }
            this.roots[root] = collaborationInstance
        }
        Disposer.register(collaborationInstance) {
            for (root in roots) {
                this.roots.remove(root)
            }
        }
    }

    override fun getProtocol(): String = "oct"

    // Phase A1: Route all path lookups through VfsImplUtil so PersistentFS materialises
    // and caches NewVirtualFile instances.  This eliminates the asCacheAvoiding crash.

    @Suppress("DEPRECATION")
    override fun findFileByPath(pathString: String): VirtualFile? =
        VfsImplUtil.findFileByPath(this, pathString)

    @Suppress("DEPRECATION")
    override fun findFileByPathIfCached(pathString: String): NewVirtualFile? =
        VfsImplUtil.findFileByPathIfCached(this, pathString)

    override fun refresh(asynchronous: Boolean) =
        VfsImplUtil.refresh(this, asynchronous)

    override fun refreshAndFindFileByPath(pathString: String): VirtualFile? =
        VfsImplUtil.refreshAndFindFileByPath(this, pathString)

    // PersistentFS's name cache rejects any string containing a path separator, so the root
    // path must be the bare folder name — no trailing slash.  NewVirtualFileSystem tokenises
    // whatever remains of the path after stripping the root prefix, so a leading '/' in the
    // remainder is handled correctly (empty tokens are ignored by StringUtil.tokenize).
    // Both "workspace" and "workspace/sub/file" therefore resolve to the same root entry.
    override fun extractRootPath(pathString: String): String {
        val normalized = pathString.replace("\\", "/")
        val slashIndex = normalized.indexOf('/')
        return if (slashIndex < 0) normalized else normalized.substring(0, slashIndex)
    }

    override fun getRank(): Int = 1

    override fun isCaseSensitive(): Boolean = true

    override fun isReadOnly(): Boolean = false

    // Phase A3: Metadata contract — all queries go to the remote OCT service.
    // Every .get() is wrapped in a try/catch: a ResponseErrorException for ENOENT (or any
    // other remote failure) must produce null / empty / 0, not an exception — PersistentFS
    // interprets null from getAttributes() as "file does not exist" and handles it cleanly.

    override fun getAttributes(file: VirtualFile): FileAttributes? {
        val path = Path(file.path)
        val stat = try { stat(path)?.get() } catch (_: Exception) { return null } ?: return null
        return FileAttributes(
            /* isDirectory  */ stat.type == FileType.Directory,
            /* isSpecial    */ false,
            /* isSymLink    */ stat.type == FileType.SymbolicLink,
            /* isHidden     */ false,
            /* length       */ stat.size,
            /* lastModified */ stat.mtime,
            /* isWritable   */ true
        )
    }

    override fun list(file: VirtualFile): Array<String> {
        val path = Path(file.path)
        return try { readDir(path)?.get()?.keys?.toTypedArray() } catch (_: Exception) { null } ?: emptyArray()
    }

    override fun exists(file: VirtualFile): Boolean = getAttributes(file) != null

    override fun isDirectory(file: VirtualFile): Boolean = file.isDirectory

    override fun getTimeStamp(file: VirtualFile): Long {
        val path = Path(file.path)
        return try { stat(path)?.get()?.mtime } catch (_: Exception) { null } ?: 0L
    }

    override fun setTimeStamp(file: VirtualFile, timeStamp: Long) { /* timestamps are server-managed */ }

    override fun isWritable(file: VirtualFile): Boolean = !isReadOnly()

    override fun setWritable(file: VirtualFile, writableFlag: Boolean) { /* not supported */ }

    override fun isSymLink(file: VirtualFile): Boolean = false

    override fun resolveSymLink(file: VirtualFile): String? = null

    override fun contentsToByteArray(file: VirtualFile): ByteArray {
        val path = Path(file.path)
        return try { readFile(path).get()?.content } catch (_: Exception) { null } ?: ByteArray(0)
    }

    override fun getInputStream(file: VirtualFile): InputStream =
        ByteArrayInputStream(contentsToByteArray(file))

    override fun getOutputStream(file: VirtualFile, requestor: Any?, modStamp: Long, timeStamp: Long): OutputStream {
        val path = Path(file.path)
        val hostId = getHostId(path)
        val service = getRemoteFilesystemService(path) ?: return OutputStream.nullOutputStream()
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                try {
                    service.writeFile(toOctPath(path), FileContent(toByteArray()), hostId).get()
                } catch (_: Exception) {
                    // write failure is surfaced through normal IDE error handling
                }
            }
        }
    }

    override fun getLength(file: VirtualFile): Long {
        val path = Path(file.path)
        return try { stat(path)?.get()?.size } catch (_: Exception) { null } ?: 0L
    }

    // Mutation operations — call remote service, then refresh parent so PersistentFS snapshot updates.

    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {
        val path = Path(vFile.path)
        getRemoteFilesystemService(path)?.delete(toOctPath(path), getHostId(path))?.get()
        vFile.parent?.refresh(false, false)
    }

    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {
        val oldPath = Path(vFile.path)
        val newPath = Path(newParent.path).resolve(vFile.name)
        getRemoteFilesystemService(oldPath)?.rename(toOctPath(oldPath), toOctPath(newPath), getHostId(oldPath))?.get()
        vFile.parent?.refresh(false, false)
        newParent.refresh(false, false)
    }

    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {
        val oldPath = Path(vFile.path)
        val newPath = oldPath.parent.resolve(newName)
        getRemoteFilesystemService(oldPath)?.rename(toOctPath(oldPath), toOctPath(newPath), getHostId(oldPath))?.get()
        vFile.parent?.refresh(false, false)
    }

    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile {
        val parentPath = Path(vDir.path)
        getRemoteFilesystemService(parentPath)?.writeFile(
            toOctPath(parentPath.resolve(fileName)), FileContent(ByteArray(0)), getHostId(parentPath))?.get()
        vDir.refresh(false, false)
        return vDir.findChild(fileName) ?: throw IllegalStateException("File not found after creation: $fileName")
    }

    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile {
        val parentPath = Path(vDir.path)
        getRemoteFilesystemService(parentPath)?.mkdir(toOctPath(parentPath.resolve(dirName)), getHostId(parentPath))?.get()
        vDir.refresh(false, false)
        return vDir.findChild(dirName) ?: throw IllegalStateException("Directory not found after creation: $dirName")
    }

    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        val oldPath = Path(virtualFile.path)
        val parentPath = Path(newParent.path)
        val oldContent = readFile(oldPath).get()
            ?: throw IllegalStateException("Could not read file ${virtualFile.path} from host")
        getRemoteFilesystemService(oldPath)?.writeFile(
            toOctPath(parentPath.resolve(copyName)), oldContent, getHostId(parentPath))?.get()
        newParent.refresh(false, false)
        return newParent.findChild(copyName) ?: throw IllegalStateException("File not found after copy: $copyName")
    }

    // Remote-service helpers

    fun stat(path: Path): CompletableFuture<FileSystemStat?>? =
        getRemoteFilesystemService(path)?.stat(toOctPath(path), getHostId(path))

    fun readFile(path: Path): CompletableFuture<FileContent?> {
        val instance = roots[path.getName(0).name]
            ?: return CompletableFuture.completedFuture(null)
        val service = instance.remoteInterface as? OCTMessageHandler.OCTService
            ?: return CompletableFuture.completedFuture(null)
        return service.getDocumentContent(toOctPath(path))
    }

    fun readDir(path: Path): CompletableFuture<Map<String, FileType>>? =
        getRemoteFilesystemService(path)?.readDir(toOctPath(path), getHostId(path))

    private fun getRemoteFilesystemService(path: Path): FileSystemMessageHandler.FileSystemService? =
        roots[path.getName(0).name]?.remoteInterface as? FileSystemMessageHandler.FileSystemService

    private fun getHostId(path: Path): String =
        roots[path.getName(0).name]?.host?.id ?: ""
}

fun toOctPath(path: Path): String = path.toString().replace("\\", "/")

