package model

import kotlinx.serialization.Serializable

@Serializable
sealed class AgentAction {
    @Serializable
    data class OpenFile(val path: String) : AgentAction()

    @Serializable
    data class MoveCaret(val symbol: String) : AgentAction()

    @Serializable
    data class SelectLines(val start: Int, val end: Int) : AgentAction()

    @Serializable
    data class Click(val target: String) : AgentAction()

    @Serializable
    data class Type(val text: String, val clearFirst: Boolean = true, val target: String? = null) : AgentAction()

    @Serializable
    data class PressKey(val key: String) : AgentAction()

    @Serializable
    data class SelectDropdown(val target: String, val value: String) : AgentAction()

    @Serializable
    data class Wait(val elementType: String, val timeoutMs: Long = 5000) : AgentAction()

    @Serializable
    data object Observe : AgentAction()

    @Serializable
    data object Complete : AgentAction()

    @Serializable
    data object Fail : AgentAction()

    companion object {
        fun describe(action: AgentAction): String =
            when (action) {
                is OpenFile -> "OpenFile('${action.path}')"
                is MoveCaret -> "MoveCaret('${action.symbol}')"
                is SelectLines -> "SelectLines(${action.start}-${action.end})"
                is Click -> "Click('${action.target}')"
                is Type -> "Type('${action.text}', clearFirst=${action.clearFirst})"
                is PressKey -> "PressKey('${action.key}')"
                is SelectDropdown -> "SelectDropdown('${action.target}', '${action.value}')"
                is Wait -> "Wait('${action.elementType}')"
                is Observe -> "Observe"
                is Complete -> "Complete"
                is Fail -> "Fail"
            }
    }
}
