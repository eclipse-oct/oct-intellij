package org.typefox.oct.fileSystem

import com.intellij.openapi.vfs.VirtualFile

/**
 * Guest-side path mapper. Every protocol path is mounted under a session-unique root segment
 * (`oct://<rootSegment>/<folderName>/...`) so that two concurrent sessions never collide, even
 * if their shared folder names do.
 */
class GuestPathMapper(private val fileSystem: OCTSessionFileSystem, val rootSegment: String) : SessionPathMapper {

    override fun toVirtualFile(protocolPath: String): VirtualFile? =
        fileSystem.findFileByPath("$rootSegment/$protocolPath")

    override fun toProtocolPath(file: VirtualFile): String? {
        val prefix = "$rootSegment/"
        return if (file.path.startsWith(prefix)) file.path.substring(prefix.length) else null
    }

    override fun refreshAndFind(protocolPath: String): VirtualFile? =
        fileSystem.refreshAndFindFileByPath("$rootSegment/$protocolPath")
}
