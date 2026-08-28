package org.typefox.oct.sessionView

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.wm.impl.welcomeScreen.TabbedWelcomeScreen
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class OCTWelcomeScreenTab : TabbedWelcomeScreen.DefaultWelcomeScreenTab("OCT") {
    override fun buildComponent(): JComponent {
        val panel = JPanel()
        setViewForProject(panel)

        return panel
    }

    private fun setViewForProject(panel: JPanel) {
        panel.layout = GridBagLayout()
        invokeLater {
            panel.removeAll()

            panel.add(NoSessionView(null), GridBagConstraints())
        }
    }
}