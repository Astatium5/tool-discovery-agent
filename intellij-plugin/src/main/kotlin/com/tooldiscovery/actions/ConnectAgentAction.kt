package com.tooldiscovery.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.tooldiscovery.service.AgentClientService

/**
 * Action to connect/disconnect from the Tool Discovery Agent.
 */
class ConnectAgentAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val agentService = AgentClientService.getInstance()

        if (agentService.isConnected) {
            agentService.disconnect()
        } else {
            agentService.connect()
        }
    }

    override fun update(e: AnActionEvent) {
        val agentService = AgentClientService.getInstance()

        if (agentService.isConnected) {
            e.presentation.text = "Disconnect from Agent"
            e.presentation.description = "Disconnect from the Tool Discovery Agent service"
        } else {
            e.presentation.text = "Connect to Agent"
            e.presentation.description = "Connect to the Tool Discovery Agent service"
        }
    }
}
