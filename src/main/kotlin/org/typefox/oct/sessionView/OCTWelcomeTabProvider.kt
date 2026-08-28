package org.typefox.oct.sessionView

import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.WelcomeScreen
import com.intellij.openapi.wm.WelcomeScreenTab
import com.intellij.openapi.wm.WelcomeTabFactory

class OCTWelcomeTabProvider : WelcomeTabFactory {
    override fun createWelcomeTabs(ws: WelcomeScreen, parentDisposable: Disposable): List<WelcomeScreenTab?> {
        return listOf(OCTWelcomeScreenTab())
    }
}