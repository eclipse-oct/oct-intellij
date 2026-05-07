package org.typefox.oct.sessionView

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import org.typefox.oct.*
import org.typefox.oct.actions.ToggleFollowAction
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.geom.Ellipse2D
import javax.swing.*

class SessionViewFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = "OCT"
        val sessionService = service<OCTSessionService>()

        val panel = JPanel()
        toolWindow.contentManager.addContent(
            toolWindow.contentManager.factory.createContent(panel, null, false))
        setViewForProject(panel, project, sessionService)

        sessionService.onSessionCreated.onEvent {
            if(it.project == project) {
                setViewForProject(panel, project, sessionService)
            }
        }

        sessionService.onSessionClosed.onEvent {
            if(it === project) {
                setViewForProject(panel, project, sessionService)
            }
        }

    }

    private fun setViewForProject(panel: JPanel, project: Project, sessionService: OCTSessionService) {
        invokeLater {
            val session = sessionService.currentCollaborationInstances[project]

            panel.removeAll()

            if (session != null) {
                panel.add(SessionView(project))
            } else {
                panel.add(NoSessionView(project))
            }
        }
    }
}

class SessionView(private val project: Project): JPanel() {

    class PeerColorIcon(private val color: JBColor) : Icon {

        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            val g2d = g!!.create() as java.awt.Graphics2D
            g2d.color = color
            g2d.fill(Ellipse2D.Double(x.toDouble(), y.toDouble(), iconWidth.toDouble(), iconHeight.toDouble()))
            g2d.dispose()
        }

        override fun getIconWidth(): Int {
            return 12
        }

        override fun getIconHeight(): Int {
            return 12
        }
    }

    init {

        layout = BorderLayout()

        renderPeerList()

        val session = service<OCTSessionService>().currentCollaborationInstances[project]!!
        session.onPeersChanged.onEvent {
            invokeLater {
                renderPeerList()
            }
        }
    }

    private fun renderPeerList() {
        val session = service<OCTSessionService>().currentCollaborationInstances[project]!!
        val identity = session.identity

        // Rebuild the list on each update to avoid stacking duplicate UI rows.
        removeAll()

        val ownName = identity?.name ?: "Unknown user"
        val ownSuffix = if (session.isHost) "you • host" else "you"
        val ownPeerId = identity?.id

        val rows = mutableListOf<JPanel>()

        // Current user is always shown first with a generic user icon.
        rows += JPanel(BorderLayout()).apply {
            add(JLabel(mutedSuffixText(ownName, ownSuffix)).apply {
                icon = AllIcons.General.User
                verticalAlignment = JLabel.CENTER
                iconTextGap = 8
            }, BorderLayout.WEST)
            add(Box.createHorizontalGlue(), BorderLayout.CENTER)
        }

        val remoteHost = session.host?.takeIf { it.id != ownPeerId }
        val remoteGuests = session.guests
            .asSequence()
            .filter { it.id != ownPeerId }
            .filter { guest -> remoteHost?.id != guest.id }
            .toList()

        fun createRemoteRow(peer: Peer, suffix: String?): JPanel {
            return JPanel(GridLayout(1, 2)).apply {
                val labelText = if (suffix != null) mutedSuffixText(peer.name, suffix) else peer.name
                add(JLabel(labelText).apply {
                    icon = PeerColorIcon(session.peerColors.getColor(peer.id))
                    verticalAlignment = JLabel.CENTER
                    iconTextGap = 8
                })
                val action = ToggleFollowAction(peer.id, project)
                add(JPanel().apply {
                    add(
                        ActionButton(
                            action,
                            action.templatePresentation.clone(),
                            ActionPlaces.UNKNOWN,
                            ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
                        ).apply { isEnabled = true })
                })
            }
        }

        remoteHost?.let { rows += createRemoteRow(it, "host") }
        remoteGuests.forEach { guest -> rows += createRemoteRow(guest, null) }

        add(JPanel().apply {
            layout = GridLayout(rows.size, 1)
            rows.forEach { row -> add(row) }
        }, BorderLayout.PAGE_START)

        revalidate()
        repaint()
    }

    private fun mutedSuffixText(name: String, suffix: String): String {
        return "<html>$name <span style='color:gray'>($suffix)</span></html>"
    }
}

class NoSessionView(val project: Project): JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(JButton("join OCT Session").apply {
            alignmentX = CENTER_ALIGNMENT
            addActionListener {
                executeAction("org.typefox.oct.JoinSession")
            }
        })
        add(JButton("Create OCT Session").apply {
            alignmentX = CENTER_ALIGNMENT
            addActionListener {
                executeAction("org.typefox.oct.HostSession")
            }
        })
    }

    private fun executeAction(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId)
        if (action != null) {
            val event = AnActionEvent.createFromDataContext("", null,
                SimpleDataContext.getProjectContext(project))
            action.actionPerformed(event)
        } else {
            println("Aktion mit ID $actionId nicht gefunden.")
        }
    }
}
