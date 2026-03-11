package com.tooldiscovery.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.tooldiscovery.service.AgentClientService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class ToolDiscoveryPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val agentService = AgentClientService.getInstance()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val statusLabel = JBLabel("Disconnected")

    private val connectButton =
        JButton("Connect").apply {
            addActionListener { connectToAgent() }
        }

    private val discoverButton =
        JButton("Discover Tools").apply {
            isEnabled = false
            addActionListener { discoverTools() }
        }

    private val refreshButton =
        JButton("Refresh").apply {
            isEnabled = false
            addActionListener { loadTools() }
        }

    private val toolListModel = DefaultListModel<String>()
    private val toolList = JBList(toolListModel)

    private val toolScrollPane = JBScrollPane(toolList)

    private val outputArea =
        JTextArea(5, 40).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

    private val outputScrollPane = JScrollPane(outputArea)

    private val tools = mutableListOf<AgentClientService.ToolInfo>()

    init {
        setupUI()
        setupConnectionListener()
    }

    private fun setupUI() {
        // Toolbar
        val toolbar =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(connectButton)
                add(discoverButton)
                add(refreshButton)
            }

        // Status panel
        val statusPanel =
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Status: "))
                add(statusLabel)
            }

        // Tools list
        val toolsPanel =
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder("Discovered Tools")
                add(toolScrollPane, BorderLayout.CENTER)
            }

        // Output panel
        val outputPanel =
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder("Output")
                add(outputScrollPane, BorderLayout.CENTER)
            }

        // Layout
        add(toolbar, BorderLayout.NORTH)
        add(toolsPanel, BorderLayout.CENTER)
        add(outputPanel, BorderLayout.SOUTH)
        add(statusPanel, BorderLayout.BEFORE_FIRST_LINE)
    }

    private fun setupConnectionListener() {
        agentService.addConnectionListener(
            object : AgentClientService.ConnectionListener {
                override fun onConnected() {
                    SwingUtilities.invokeLater {
                        updateConnectedState(true)
                        appendOutput("Connected to agent at ${agentService.agentUrl}")
                    }
                }

                override fun onDisconnected(reason: String?) {
                    SwingUtilities.invokeLater {
                        updateConnectedState(false)
                        appendOutput("Disconnected: ${reason ?: "Unknown"}")
                    }
                }

                override fun onError(error: String) {
                    SwingUtilities.invokeLater {
                        appendOutput("Error: $error")
                    }
                }
            },
        )
    }

    private fun connectToAgent() {
        if (agentService.isConnected) {
            agentService.disconnect()
            updateConnectedState(false)
        } else {
            appendOutput("Connecting to ${agentService.agentUrl}...")
            agentService.connect()
        }
    }

    private fun updateConnectedState(connected: Boolean) {
        if (connected) {
            statusLabel.text = "Connected"
            connectButton.text = "Disconnect"
            discoverButton.isEnabled = true
            refreshButton.isEnabled = true
        } else {
            statusLabel.text = "Disconnected"
            connectButton.text = "Connect"
            discoverButton.isEnabled = false
            refreshButton.isEnabled = false
        }
    }

    private fun discoverTools() {
        appendOutput("Starting tool discovery...")
        discoverButton.isEnabled = false

        scope.launch {
            val result = agentService.discoverTools()

            withContext(Dispatchers.Main) {
                discoverButton.isEnabled = true

                result.fold(
                    onSuccess = { discoveredTools ->
                        tools.clear()
                        tools.addAll(discoveredTools)
                        updateToolList()
                        appendOutput("Discovered ${tools.size} tools")
                    },
                    onFailure = { error ->
                        appendOutput("Discovery failed: ${error.message}")
                    },
                )
            }
        }
    }

    private fun loadTools() {
        appendOutput("Loading tools...")

        scope.launch {
            val result = agentService.getTools()

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { loadedTools ->
                        tools.clear()
                        tools.addAll(loadedTools)
                        updateToolList()
                        appendOutput("Loaded ${tools.size} tools")
                    },
                    onFailure = { error ->
                        appendOutput("Failed to load tools: ${error.message}")
                    },
                )
            }
        }
    }

    private fun updateToolList() {
        toolListModel.clear()

        val groupedTools = tools.groupBy { it.category }

        groupedTools.forEach { (category, categoryTools) ->
            toolListModel.addElement("─── $category ───")
            categoryTools.forEach { tool ->
                toolListModel.addElement("${tool.name} [${tool.shortcut ?: "no shortcut"}]")
            }
        }
    }

    private fun appendOutput(message: String) {
        SwingUtilities.invokeLater {
            outputArea.append(
                "[${
                    java.time.LocalTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"),
                    )
                }] $message\n",
            )
            outputArea.caretPosition = outputArea.document.length
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
