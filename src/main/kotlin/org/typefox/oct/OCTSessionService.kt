package org.typefox.oct

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.AnimatedIcon
import org.typefox.oct.actions.CopyRoomTokenAction
import org.typefox.oct.actions.CopyRoomUrlAction
import org.typefox.oct.messageHandlers.BaseMessageHandler
import org.typefox.oct.messageHandlers.FileSystemMessageHandler
import org.typefox.oct.messageHandlers.OCTMessageHandler
import org.typefox.oct.sessionView.OCTSessionStatusBarWidgetFactory
import org.typefox.oct.settings.OCTSettings
import org.typefox.oct.util.Disposable
import org.typefox.oct.util.EventEmitter
import java.io.File
import javax.swing.*
import kotlin.io.path.createTempDirectory
import kotlin.io.path.pathString
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.ApplicationManager
import io.grpc.netty.shaded.io.netty.util.concurrent.Future
import org.eclipse.sisu.Nullable
import java.util.concurrent.CompletableFuture

val messageHandlers: Array<Class<out BaseMessageHandler>> = arrayOf(
    FileSystemMessageHandler::class.java,
    OCTMessageHandler::class.java
)

@Service
class OCTSessionService() {

    private var currentProcesses: MutableMap<Project, OCTServiceProcess> = mutableMapOf()

    private var tempProjectsToDelete = mutableListOf<Project>()

    var currentCollaborationInstances: MutableMap<Project, CollaborationInstance> = mutableMapOf()

    var onSessionCreated: EventEmitter<CollaborationInstance> = EventEmitter()

    fun hasOpenSession(project: Project): Boolean {
        return currentCollaborationInstances.containsKey(project)
    }

    fun createRoom(workspace: Workspace, project: Project) {

        if (currentCollaborationInstances.contains(project)) {
            return
        }


        val serverUrl = service<OCTSettings>().state.defaultServerURL

        if (!currentProcesses.contains(project)) {
            currentProcesses[project] = createServiceProcess(serverUrl)
        }
        val currentProcess = currentProcesses[project]!!

        SessionCreationTask(project, "Creating room...",
            currentProcess.getOctService<OCTMessageHandler.OCTService>().createRoom(workspace)
                .exceptionally {
                    createErrorNotification(it, "Error Creating Room")
                    null
                }) { sessionData ->
                    val roomCreatedNotification =
                        Notification("Oct-Notifications", "Hosted session", NotificationType.INFORMATION)
                    roomCreatedNotification.addAction(CopyRoomTokenAction(sessionData.roomId) {
                        roomCreatedNotification.expire()
                    })
                    roomCreatedNotification.addAction(CopyRoomUrlAction(sessionData.roomId) {
                        roomCreatedNotification.expire()
                    })
                    Notifications.Bus.notify(roomCreatedNotification)

                    sessionCreated(sessionData, serverUrl, project, true)
                }.queue()
    }

    fun joinRoom(roomToken: String, project: Project?) {

        // TODO add parsing for serverUrl#id
        val serverUrl = service<OCTSettings>().state.defaultServerURL

        val currentProcess = createServiceProcess(serverUrl)

        SessionCreationTask(project, "Joining room...", currentProcess.getOctService<OCTMessageHandler.OCTService>()
            .joinRoom(roomToken)) { sessionData ->
                ApplicationManager.getApplication().invokeLater {
                    val projectDir = createTempDirectory(sessionData.workspace.name)
                    val newProject = ProjectManager.getInstance().loadAndOpenProject(projectDir.pathString)

                    if (newProject != null) {
                        currentProcesses[newProject] = currentProcess
                        sessionCreated(sessionData, serverUrl, newProject, false)
                    } else {
                        createErrorNotification(Throwable(), "Could not create project for session")
                    }
                }
            }.queue()
    }

    fun closeCurrentSession(project: Project) {
        currentProcesses[project]?.getOctService<OCTMessageHandler.OCTService>()?.closeSession()?.get()
        currentProcesses[project]?.let {
            Disposer.dispose(it)
        }
        currentProcesses.remove(project)
        currentCollaborationInstances[project]?.let {
            Disposer.dispose(it)
            if(!it.isHost) {
                tempProjectsToDelete.add(project)
            }
        }
        currentCollaborationInstances.remove(project)

        // update status bar
        project.service<StatusBarWidgetsManager>().updateWidget(OCTSessionStatusBarWidgetFactory::class.java)

    }

    fun projectClosed(project: Project) {
        // delete temporary oct project
        if (tempProjectsToDelete.contains(project)) {
            project.basePath?.let {
                File(it).deleteRecursively()
            }
        }
    }

    private fun sessionCreated(sessionData: SessionData, serverUrl: String, project: Project, isHost: Boolean) {
        if (sessionData.authToken != null) {
            service<AuthenticationService>().onAuthenticated(sessionData.authToken, serverUrl)
        }

        val currentProcess = currentProcesses[project] ?: throw IllegalStateException("No current process found for project")
        val collaborationInstance = CollaborationInstance(currentProcess.getOctService(), project, sessionData, isHost)
        Disposer.register(currentProcesses[project]!!, collaborationInstance)
        this.currentCollaborationInstances[project] = collaborationInstance

        onSessionCreated.fire(collaborationInstance)

        // update status bar
        project.service<StatusBarWidgetsManager>().updateWidget(OCTSessionStatusBarWidgetFactory::class.java)
    }

    private fun createServiceProcess(serverUrl: String): OCTServiceProcess {
        return OCTServiceProcess(serverUrl, messageHandlers.map {
            it.getConstructor(String::class.java, EventEmitter::class.java).newInstance(serverUrl, onSessionCreated)
        })
    }

    private fun createErrorNotification(e: Throwable, title: String) {
        val errorNotification = Notification(
            "Oct-Notifications",
            title,
            e.message ?: title,
            NotificationType.ERROR
        )
        Notifications.Bus.notify(errorNotification)
    }
}

class SessionCreationTask(project: Project?, title: String,
                          private val future: CompletableFuture<SessionData>,
                          private val onComplete: ((SessionData) -> Unit)
    ): Task.Backgroundable(project, title, true) {

    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true

        while (!future.isDone) {
            if (indicator.isCanceled) {
                future.cancel(true)
                return
            }
            Thread.sleep(100)
        }

        if (!future.isCompletedExceptionally) {
            val sessionData = future.get()
            onComplete(sessionData)
        }
    }

}


class ProjectListener: ProjectCloseListener {

    override fun projectClosing(project: Project) {
        service<OCTSessionService>().closeCurrentSession(project)
    }

    override fun projectClosed(project: Project) {
        service<OCTSessionService>().projectClosed(project)
    }
}
