package org.typefox.oct

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFileManager
import org.typefox.oct.editor.EditorManager
import org.typefox.oct.fileSystem.GuestPathMapper
import org.typefox.oct.fileSystem.OCTSessionFileSystem
import org.typefox.oct.fileSystem.SessionPathMapper
import org.typefox.oct.fileSystem.WorkspaceFileSystemService
import org.typefox.oct.messageHandlers.BaseMessageHandler
import org.typefox.oct.messageHandlers.OCTMessageHandler
import org.typefox.oct.util.EventEmitter


class CollaborationInstance(val remoteInterface: BaseMessageHandler.BaseRemoteInterface,
                            val project: Project,
                            private val sessionData: SessionData,
                            val isHost: Boolean) : Disposable {

    val workspaceFileSystem: WorkspaceFileSystemService = project.getService(WorkspaceFileSystemService::class.java)

    private val pathMapper: SessionPathMapper = if (isHost) {
        workspaceFileSystem
    } else {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("oct") as OCTSessionFileSystem
        val rootSegment = fileSystem.registerRoots(sessionData.workspace.name, sessionData.workspace.folders, this)
        GuestPathMapper(fileSystem, rootSegment)
    }

    private val editorManager: EditorManager = EditorManager(
        remoteInterface as OCTMessageHandler.OCTService,
        project,
        pathMapper
    )

    val guests: ArrayList<Peer> = ArrayList()
    var host: Peer? = null

    var identity: Peer? = null

    val peerColors = PeerColors()

    val onPeersChanged = EventEmitter<Unit?>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(editorManager, this)
        println("initialized collaboration instance")
    }

    fun updateTextSelection(url: String, selections: Array<ClientTextSelection>) {
        editorManager.updateTextSelection(url, selections)
    }

    fun updateDocument(url: String, updates: Array<TextDocumentInsert>) {
        editorManager.updateDocument(url, updates)
    }

    fun initPeers(initData: InitData) {
        guests.addAll(initData.guests)
        host = initData.host
        if(!isHost) {
            mountSharedFolders()
        }
        onPeersChanged.fire(null)
    }


    fun peerJoined(peer: Peer) {
        this.guests.add(peer)
        this.onPeersChanged.fire(Unit)
    }

    fun peerLeft(peer: Peer) {
        this.guests.remove(guests.find {
            it.id == peer.id
        } ?: return)
        this.onPeersChanged.fire(Unit)
    }

    /** Adds the shared folders (already registered with the oct VFS in [pathMapper]'s init) as content roots of a synthetic module. */
    private fun mountSharedFolders() {
        val rootSegment = (pathMapper as GuestPathMapper).rootSegment
        try {
            val module: Module = WriteAction.computeAndWait<Module, Throwable> {
                ModuleManager.getInstance(project)
                    .newNonPersistentModule(sessionData.workspace.name, EmptyModuleType.EMPTY_MODULE)
            }
            ModuleRootModificationUtil.updateModel(module) {
                for (entry in sessionData.workspace.folders) {
                    val root = VirtualFileManager.getInstance().findFileByUrl("oct://$rootSegment/${entry}")
                        ?: throw IllegalStateException("Could not find shared root for entry $entry")
                    it.addContentEntry(root)
                }
            }
        } catch (e: Throwable) {
            createErrorNotification(e)
            this.dispose()
        }
    }

    private fun createErrorNotification(e: Throwable) {
        Notifications.Bus.notify(
            Notification(
                "Oct-Notifications",
                "Failed to initialize shared folders",
                e.message ?: e.toString(),
                NotificationType.ERROR
            )
        )
    }

    fun followPeer(peerId: String) {
        editorManager.followPeer(peerId)
    }

    fun stopFollowingPeer() {
        editorManager.stopFollowing()
    }

    fun isFollowingPeer(peerId: String): Boolean {
        return editorManager.followingPeerId == peerId
    }

    fun editorOpened(documentPath: String, peerId: String) {
        if (isHost) {
            editorManager.guestOpenedEditor(documentPath)
        }
    }

    fun handleVirtualFilesystemChange(event: FileChangeEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            for (change in event.changes) {
                val slashIndex = change.path.lastIndexOf('/')
                val parentPath = if (slashIndex < 0) "" else change.path.substring(0, slashIndex)
                if (parentPath.isNotEmpty()) {
                    pathMapper.toVirtualFile(parentPath)?.refresh(false, false)
                }
                pathMapper.refreshAndFind(change.path)
            }

            ProjectView.getInstance(project).refresh()
        }
    }

    override fun dispose() {
        System.out.println("disposed")
    }
}
