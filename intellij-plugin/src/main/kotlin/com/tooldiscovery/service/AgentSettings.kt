package com.tooldiscovery.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for the Tool Discovery Agent.
 *
 * Stores connection configuration that persists across IDE sessions.
 */
@Service(Service.Level.APP)
@State(
    name = "ToolDiscoveryAgentSettings",
    storages = [Storage("tool-discovery-agent.xml")],
)
class AgentSettings : PersistentStateComponent<AgentSettings.State> {
    data class State(
        var agentUrl: String = "http://localhost:8080",
        var timeoutSeconds: Int = 30,
        var autoConnect: Boolean = false,
        var maxDiscoveryDepth: Int = 5,
    )

    private var state = State()

    var agentUrl: String
        get() = state.agentUrl
        set(value) {
            state.agentUrl = value
        }

    var timeoutSeconds: Int
        get() = state.timeoutSeconds
        set(value) {
            state.timeoutSeconds = value
        }

    var autoConnect: Boolean
        get() = state.autoConnect
        set(value) {
            state.autoConnect = value
        }

    var maxDiscoveryDepth: Int
        get() = state.maxDiscoveryDepth
        set(value) {
            state.maxDiscoveryDepth = value
        }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): AgentSettings {
            return ApplicationManager.getApplication().getService(AgentSettings::class.java)
        }
    }
}
