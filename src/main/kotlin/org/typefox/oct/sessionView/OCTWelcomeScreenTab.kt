package org.typefox.oct.sessionView

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.wm.impl.welcomeScreen.TabbedWelcomeScreen
import javax.swing.JComponent
import javax.swing.JPanel

class OCTWelcomeScreenTab : TabbedWelcomeScreen.DefaultWelcomeScreenTab("OCT") {
    override fun buildComponent(): JComponent {
        val panel = JPanel()
        setViewForProject(panel)

        return panel
    }

    private fun setViewForProject(panel: JPanel) {
        invokeLater {
            panel.removeAll()

            panel.add(NoSessionView(null))
        }
    }
}
