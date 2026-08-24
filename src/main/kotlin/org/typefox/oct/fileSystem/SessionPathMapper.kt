package org.typefox.oct.fileSystem

import com.intellij.openapi.vfs.VirtualFile

/**
 * Translates between protocol paths ("<folderName>/<relativePath>") and local [VirtualFile]s.
 * The host implementation ([WorkspaceFileSystemService]) resolves against the real local disk;
 * the guest implementation ([GuestPathMapper]) resolves against the synthetic `oct://` VFS.
 */
interface SessionPathMapper {
    fun toVirtualFile(protocolPath: String): VirtualFile?
    fun toProtocolPath(file: VirtualFile): String?

    fun refreshAndFind(protocolPath: String): VirtualFile? =
        toVirtualFile(protocolPath)?.also { it.refresh(false, false) }
}
