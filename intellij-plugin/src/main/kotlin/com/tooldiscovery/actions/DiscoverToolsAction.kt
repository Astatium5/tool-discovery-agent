package com.tooldiscovery.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.tooldiscovery.service.AgentClientService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Action to discover IDE tools.
 */
class DiscoverToolsAction : AnAction() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val agentService = AgentClientService.getInstance()

        if (!agentService.isConnected) {
            Messages.showWarningDialog(
                project,
                "Not connected to Tool Discovery Agent. Please connect first.",
                "Not Connected",
            )
            return
        }

        scope.launch {
            val result = agentService.discoverTools()

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { tools ->
                        Messages.showInfoMessage(
                            project,
                            "Discovered ${tools.size} tools",
                            "Discovery Complete",
                        )
                    },
                    onFailure = { error ->
                        Messages.showErrorDialog(
                            project,
                            "Discovery failed: ${error.message}",
                            "Error",
                        )
                    },
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = AgentClientService.getInstance().isConnected
    }
}
