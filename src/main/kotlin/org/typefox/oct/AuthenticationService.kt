package org.typefox.oct

import com.google.gson.Gson
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.OneTimeString
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.remoteDev.util.addPathSuffix
import com.intellij.remoteServer.util.CloudConfigurationUtil.createCredentialAttributes
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Urls
import com.intellij.util.withQuery
import org.typefox.oct.settings.OCTSettings
import org.typefox.oct.util.EventEmitter
import java.awt.Desktop
import java.net.URI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import com.intellij.util.io.HttpRequests
import com.intellij.util.withPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.swing.Box
import javax.swing.JLabel
import kotlin.io.path.Path

const val OCT_TOKEN_SERVICE_KEY = "OCT-Auth-Token"

@Service
class AuthenticationService(private val scope: CoroutineScope) {

    private var currentAuthPopup: AuthDialog? = null

    fun authenticate(serverUrl: String, token: String, metadata: AuthMetadata) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(metadata.providers.map { provider ->
                provider.name
            })
            .setTitle("Select an Authentication Option")
            .setItemChosenCallback { selectedValue ->
                if (selectedValue === null) return@setItemChosenCallback

                val provider = metadata.providers.find { it.name == selectedValue }
                when (provider?.type) {
                    "form" -> handleFormAuth(serverUrl, provider, token)
                    "web" -> handleWebAuth(serverUrl, provider, token)
                    else -> {
                        // Fallback to opening the login page URL
                        if (JBCefApp.isSupported() && metadata.loginPageUrl != null) {
                            ApplicationManager.getApplication().invokeLater {
                                val projectManager = ProjectManager.getInstance()
                                val project = projectManager.openProjects.lastOrNull() ?: projectManager.defaultProject
                                this.currentAuthPopup = AuthDialog(metadata.loginPageUrl, project)
                                this.currentAuthPopup?.show()
                            }
                        } else if (!JBCefApp.isSupported()) {
                            Desktop.getDesktop().browse(URI(metadata.loginPageUrl!!))
                        }
                    }
                }
            }
            .createPopup()

        ApplicationManager.getApplication().invokeLater {
            popup.showInFocusCenter()
        }
    }

    private fun handleFormAuth(serverUrl: String, provider: AuthProvider, token: String) {
        if (provider.fields === null || provider.fields.isEmpty()) {
            return
        }

        val data: MutableMap<String, String> = mutableMapOf(Pair("token", token))

        val panel = JPanel()

        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        provider.fields.forEach { field ->
            val jTextField = JTextField()
            jTextField.addPropertyChangeListener {
                data[field.name] = jTextField.text
            }
            jTextField.name = field.name
            jTextField.alignmentX = 0f
            val label = JLabel(field.label.message)
            label.alignmentX = 0f
            panel.add(label)
            panel.add(jTextField)
            panel.add(Box.createVerticalStrut(8))
        }

        val dialog = DialogBuilder()
            .title("${provider.name} Login")
            .centerPanel(panel)

        dialog.setOkOperation {
            dialog.dialogWrapper.close(0)
            scope.launch {
                HttpRequests.post(
                    Urls.newFromEncoded(serverUrl).resolve(provider.endpoint).toExternalForm(),
                    "application/json"
                ).connect { request ->
                    val jsonBody = Gson().toJson(data)
                    request.write(jsonBody)
                }
            }
        }

        dialog.show()
    }

    private fun handleWebAuth(serverUrl: String, provider: AuthProvider, token: String) {
        Desktop.getDesktop().browse(URI(Urls.newFromEncoded(serverUrl)
            .resolve(provider.endpoint)
            .addParameters(mutableMapOf(Pair("token", token)))
            .toExternalForm()))
    }

    fun onAuthenticated(authToken: String, serverUrl: String) {
        if (this.currentAuthPopup != null) {
            ApplicationManager.getApplication().invokeLater {
                this.currentAuthPopup?.close(0)
                this.currentAuthPopup = null;
            }
        }
        val attributes = createCredentialAttributes(OCT_TOKEN_SERVICE_KEY, serverUrl)!!
        val credentials = Credentials(serverUrl, authToken)
        PasswordSafe.instance.set(attributes, credentials)
        service<OCTSettings>().didStoreUserToken(serverUrl)
    }

    fun getAuthToken(serverUrl: String): OneTimeString? {
        val attributes = createCredentialAttributes(OCT_TOKEN_SERVICE_KEY, serverUrl)!!

        val credentials = PasswordSafe.instance.get(attributes)
        return credentials?.password
    }
}


class AuthDialog(private val url: String, project: Project) : DialogWrapper(project) {

    init {
        title = "Login"
        buttonMap.clear()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val browser = JBCefBrowser()
        browser.loadURL(url)
        return browser.component
    }

    override fun createSouthPanel(): JComponent {
        return JPanel()
    }
}
