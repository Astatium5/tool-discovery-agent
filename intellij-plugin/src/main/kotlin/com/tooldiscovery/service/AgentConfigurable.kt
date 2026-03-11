package com.tooldiscovery.service

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.Component
import javax.swing.*

/**
 * Configurable for agent connection settings.
 * Provides UI for configuring the agent service URL and other parameters.
 */
class AgentConfigurable : Configurable {
    private var urlField: JBTextField? = null
    private var timeoutField: JBTextField? = null

    private val settings
        get() = AgentSettings.getInstance()

    override fun getDisplayName(): String = "Tool Discovery Agent"

    override fun createComponent(): JComponent? {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            }

        // URL setting
        val urlPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        urlPanel.add(JBLabel("Agent URL: "))
        urlPanel.add(Box.createHorizontalStrut(10))
        urlField = JBTextField(settings.agentUrl, 30)
        urlPanel.add(urlField)
        panel.add(urlPanel)

        panel.add(Box.createVerticalStrut(10))

        // Timeout setting
        val timeoutPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        timeoutPanel.add(JBLabel("Timeout (seconds): "))
        timeoutPanel.add(Box.createHorizontalStrut(10))
        timeoutField = JBTextField(settings.timeoutSeconds.toString(), 10)
        timeoutPanel.add(timeoutField)
        panel.add(timeoutPanel)

        panel.add(Box.createVerticalStrut(20))

        // Help text
        panel.add(
            JBLabel("Configure the connection to the Tool Discovery Agent service.").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        return panel
    }

    override fun isModified(): Boolean {
        return urlField?.text != settings.agentUrl ||
            timeoutField?.text?.toIntOrNull() != settings.timeoutSeconds
    }

    override fun apply() {
        val timeout = timeoutField?.text?.toIntOrNull()
        if (timeout == null || timeout <= 0) {
            Messages.showErrorDialog(
                "Invalid timeout value. Please enter a positive integer.",
                "Validation Error",
            )
            return
        }

        settings.agentUrl = urlField?.text ?: settings.agentUrl
        settings.timeoutSeconds = timeout

        // Update the client service
        AgentClientService.getInstance().setAgentUrl(settings.agentUrl)
    }

    override fun reset() {
        urlField?.text = settings.agentUrl
        timeoutField?.text = settings.timeoutSeconds.toString()
    }
}
