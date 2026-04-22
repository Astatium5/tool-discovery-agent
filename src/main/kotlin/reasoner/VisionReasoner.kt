package reasoner

import agent.AgentConfig.maxRetries
import com.intellij.openapi.diagnostic.logger
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import model.RecipeStep
import vision.ElementInfo
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Vision-based reasoner implementing the actual AppAgent approach from the paper.
 *
 * Now outputs RecipeSteps (same action space as UI Tree agent) for unified execution.
 */
class VisionReasoner(private val llm: ChatModel) {
    private val log = logger<VisionReasoner>()

    /**
     * A decision made by the vision-based LLM.
     */
    data class VisionDecision(
        val observation: String,
        val thought: String,
        val action: RecipeStep,
        val summary: String,
        val taskComplete: Boolean = false,
    )

    /**
     * Reflection result after executing an action.
     */
    data class ReflectionResult(
        val decision: ReflectionDecision,
        val thought: String,
        val documentation: String?,
    )

    enum class ReflectionDecision {
        BACK, // Wrong page - go back and document
        INEFFECTIVE, // Nothing changed - mark useless
        CONTINUE, // Changed but didn't help task - document + continue
        SUCCESS, // Moved forward - document + continue
    }

    /**
     * Context for vision-based decision making.
     */
    data class VisionContext(
        val intent: String,
        val screenshot: BufferedImage,
        val elementMap: Map<Int, ElementInfo>,
        val lastActionSummary: String = "None",
        val documentation: Map<Int, String> = emptyMap(),
    )

    companion object {
        // Main task template - outputs RecipeSteps (unified with UI Tree agent)
        // IMPORTANT: Output must be PLAIN TEXT, not JSON (no quotes around field names)
        private const val TASK_TEMPLATE = """You are an IDE automation agent.

⚠️ CRITICAL RULES ⚠️
1. READ "Past actions" BELOW - DO NOT repeat the same action!
2. After each action, proceed to the NEXT logical step toward completing the task
3. If an action succeeded, move forward - do NOT restart or loop

⚠️ REFACTORING COMPLETION RULE ⚠️
After pressing Enter to CONFIRM a refactoring dialog (rename, extract, etc.), use FINISH.
DO NOT press Enter again - the refactoring is already applied to the code.

⚠️ NUMBERED ELEMENTS WARNING ⚠️
Numbers [1], [2], etc. on screenshot are TOOLBAR/MENU elements.
For editor operations (rename, edit), use keyboard actions, not click_element.

Available actions:
- move_caret(symbol) → Navigate cursor to symbol in editor
- press_shortcut(keys) → Keyboard shortcut (e.g., "Shift+F6" for rename, "Cmd+N" for new)
- type_in_dialog(text) → Type text in active input field
- press_key(key) → Press single key (Enter, Escape, Tab, etc.)
- click_element(id) → Click numbered toolbar/menu element
- double_click_element(id) → Double-click element
- right_click_element(id) → Right-click element
- FINISH → Task complete

---

Task: <task_description>

📌 PAST ACTIONS (READ AND CONTINUE FROM HERE):
<last_act>

⚠️ If previous action succeeded, DO NOT repeat it. Choose the NEXT action toward completion.

Output (PLAIN TEXT):
Observation: <what you see in screenshot>
Thought: <Based on past actions, what's the NEXT step? Don't repeat previous actions.>
Action: <one function call or FINISH>
Summary: <brief progress>"""

        // Reflection template - compares before/after screenshots
        // IMPORTANT: Output must be PLAIN TEXT format, not JSON
        private const val REFLECT_TEMPLATE = """I will give you screenshots before and after <action> the UI element
labeled with number '<ui_element>' on the first screenshot. The numeric tag of each element is located at the center.
The action was described as: <last_act>
This was an attempt to proceed with a larger task: <task_desc>

Your job is to analyze the difference between the two screenshots to determine if the action was correct and
effectively moved the task forward.

IMPORTANT: Output in PLAIN TEXT format (NOT JSON). Do NOT use quotes around field names.

Your output should be determined based on the following situations:

1. BACK - If the action navigated to a page where you cannot proceed with the task
Format (plain text, not JSON):
Decision: BACK
Thought: <explain why you should go back>
Documentation: <describe the function of the UI element>

2. INEFFECTIVE - If the action changed nothing on the screen
Format:
Decision: INEFFECTIVE
Thought: <explain why you made this decision>

3. CONTINUE - If the action changed something but did not move the task forward
Format:
Decision: CONTINUE
Thought: <explain why the action did not move the task forward>
Documentation: <describe the function of the UI element>

4. SUCCESS - If the action successfully moved the task forward
Format:
Decision: SUCCESS
Thought: <explain why the action moved the task forward>
Documentation: <describe the function of the UI element>"""

        // Documentation templates for specific actions (from AppAgent)
        private const val CLICK_DOC_TEMPLATE = """I will give you screenshots before and after clicking the UI element
labeled with number <ui_element>. Clicking this UI element was part of a larger task: <task_desc>.
Describe the functionality of the UI element concisely in one or two sentences. Focus on the general function.
Example: if it navigates to chat with John, say "Clicking this area navigates to the chat window".
Never include the numeric tag in your description."""

        private const val RIGHT_CLICK_DOC_TEMPLATE = """I will give you screenshots before and after right-clicking
the UI element labeled with number <ui_element>. Right-clicking was part of a larger task: <task_desc>.
Describe the functionality of the UI element concisely. Focus on the general function.
Example: "Right-clicking this area opens the context menu with additional options".
Never include the numeric tag in your description."""

        private const val DOC_REFINE_SUFFIX = """
A documentation of this UI element generated from previous demos is shown below. Your generated description should
optimize it. If your understanding conflicts with the previous doc, combine both.
Old documentation: <old_doc>"""
    }

    /**
     * Make a decision based on visual context (attempt to complete the task).
     */
    fun decide(context: VisionContext): VisionDecision {
        val prompt = buildTaskPrompt(context)

        return try {
            val base64Image = imageToBase64(context.screenshot)
            val userMessage =
                UserMessage.from(
                    ImageContent.from(base64Image, "image/png"),
                    TextContent.from(prompt),
                )
            val systemMessage =
                SystemMessage.from(
                    "You are an expert at operating IDE applications. " +
                        "Focus on UI elements that help accomplish the task. " +
                        "Output in the exact format: Observation, Thought, Action, Summary.",
                )

            val response = llm.chat(systemMessage, userMessage)
            parseDecision(response.aiMessage().text())
        } catch (e: Exception) {
            log.warn("  VisionReasoner: LLM call failed: ${e.message}")
            VisionDecision(
                observation = "LLM call failed",
                thought = "Unable to process screenshot",
                action = RecipeStep.FocusEditor,
                summary = "Failed to get LLM response",
                taskComplete = false,
            )
        }
    }

    fun reflect(
        screenshotBefore: BufferedImage,
        screenshotAfter: BufferedImage,
        action: RecipeStep,
        elementId: Int?,
        lastActionSummary: String,
        taskDescription: String,
    ): ReflectionResult {
        val prompt = buildReflectPrompt(action, elementId, lastActionSummary, taskDescription)

        var retries = 0
        while (retries < maxRetries) {
            try {
                val base64Before = imageToBase64(screenshotBefore)
                val base64After = imageToBase64(screenshotAfter)
                val userMessage =
                    UserMessage.from(
                        ImageContent.from(base64Before, "image/png"),
                        ImageContent.from(base64After, "image/png"),
                        TextContent.from(prompt),
                    )
                val systemMessage =
                    SystemMessage.from(
                        "Compare the two screenshots carefully. Determine if the action was effective " +
                            "and generate documentation for the UI element if appropriate. " +
                            "Output in PLAIN TEXT format: Decision: BACK/INEFFECTIVE/CONTINUE/SUCCESS, Thought: ..., Documentation: ...",
                    )

                val response = llm.chat(systemMessage, userMessage)
                val result = parseReflection(response.aiMessage().text())

                if (result.thought != "No thought provided" && result.thought != "Reflection call failed") {
                    return result
                }

                retries++
                log.debug("Reflection retry $retries due to invalid response")
            } catch (e: Exception) {
                log.warn("Reflection failed: ${e.message}")
                retries++
            }
        }

        // All retries failed
        return ReflectionResult(
            decision = ReflectionDecision.CONTINUE,
            thought = "Reflection failed after retries",
            documentation = null,
        )
    }

    /**
     * Generate documentation for an action by comparing before/after screenshots.
     * This is called after a successful action during exploration.
     */
    fun generateDocumentation(
        screenshotBefore: BufferedImage,
        screenshotAfter: BufferedImage,
        action: RecipeStep,
        elementId: Int,
        taskDescription: String,
        existingDoc: String? = null,
    ): String {
        val prompt = buildDocPrompt(action, elementId, taskDescription, existingDoc)

        return try {
            val base64Before = imageToBase64(screenshotBefore)
            val base64After = imageToBase64(screenshotAfter)
            val userMessage =
                UserMessage.from(
                    ImageContent.from(base64Before, "image/png"),
                    ImageContent.from(base64After, "image/png"),
                    TextContent.from(prompt),
                )
            val systemMessage =
                SystemMessage.from(
                    "Describe the UI element's general function. Focus on what it does, not specific details.",
                )

            val response = llm.chat(systemMessage, userMessage)
            response.aiMessage().text().trim()
        } catch (e: Exception) {
            log.warn("Documentation generation failed: ${e.message}")
            "Element $elementId - documentation unavailable"
        }
    }

    // ── Prompt Building ─────────────────────────────────────────────────────────

    private fun buildTaskPrompt(context: VisionContext): String {
        val docSection = buildDocSection(context.documentation)
        return TASK_TEMPLATE
            .replace("<ui_document>", docSection)
            .replace("<task_description>", context.intent)
            .replace("<last_act>", context.lastActionSummary)
    }

    private fun buildDocSection(documentation: Map<Int, String>): String {
        if (documentation.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        sb.append("You have access to the following documentations that describe UI element functionalities.\n")
        sb.append("Prioritize these documented elements for interaction:\n\n")
        for ((elementId, doc) in documentation.entries.sortedBy { it.key }) {
            sb.append("Documentation of UI element labeled '$elementId':\n")
            sb.append("$doc\n\n")
        }
        return sb.toString()
    }

    private fun buildReflectPrompt(
        action: RecipeStep,
        elementId: Int?,
        lastActionSummary: String,
        taskDescription: String,
    ): String {
        val actionVerb =
            when (action) {
                is RecipeStep.ClickElement -> "clicking"
                is RecipeStep.DoubleClickElement -> "double-clicking"
                is RecipeStep.RightClickElement -> "right-clicking"
                is RecipeStep.MoveCaret -> "moving caret to"
                is RecipeStep.TypeInDialog -> "typing in"
                else -> "interacting with"
            }
        return REFLECT_TEMPLATE
            .replace("<action>", actionVerb)
            .replace("<ui_element>", elementId?.toString() ?: "unknown")
            .replace("<last_act>", lastActionSummary)
            .replace("<task_desc>", taskDescription)
    }

    private fun buildDocPrompt(
        action: RecipeStep,
        elementId: Int,
        taskDescription: String,
        existingDoc: String?,
    ): String {
        val template =
            when (action) {
                is RecipeStep.ClickElement -> CLICK_DOC_TEMPLATE
                is RecipeStep.DoubleClickElement -> CLICK_DOC_TEMPLATE
                is RecipeStep.RightClickElement -> RIGHT_CLICK_DOC_TEMPLATE
                else -> CLICK_DOC_TEMPLATE
            }

        var prompt =
            template
                .replace("<ui_element>", elementId.toString())
                .replace("<task_desc>", taskDescription)

        if (existingDoc != null) {
            prompt += DOC_REFINE_SUFFIX.replace("<old_doc>", existingDoc)
        }

        return prompt
    }

    // ── JSON Parsing ─────────────────────────────────────────────────────────

    private fun imageToBase64(image: BufferedImage): String {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "png", outputStream)
        return Base64.getEncoder().encodeToString(outputStream.toByteArray())
    }

    /**
     * Parse the decision response in AppAgent format:
     * Observation: ...
     * Thought: ...
     * Action: tap(5) or FINISH
     * Summary: ...
     */
    private fun parseDecision(response: String): VisionDecision {
        val observation = extractFieldRobust(response, "Observation")
        val thought = extractFieldRobust(response, "Thought")
        val actionStr = extractFieldRobust(response, "Action")
        val summary = extractFieldRobust(response, "Summary")

        // Check for garbage response (all fields failed to extract)
        if (observation == null && thought == null && actionStr == null) {
            log.warn("LLM returned malformed response")
            return VisionDecision(
                observation = "Malformed LLM response",
                thought = "Response parsing failed, requesting new observation",
                action = RecipeStep.FocusEditor,
                summary = "LLM response was malformed",
                taskComplete = false,
            )
        }

        val action = parseAction(actionStr ?: "focus_editor")
        val taskComplete = (actionStr ?: "").trim().uppercase() == "FINISH"

        return VisionDecision(
            observation = observation ?: "Unable to observe",
            thought = thought ?: "Unable to think",
            action = action,
            summary = summary ?: "No summary",
            taskComplete = taskComplete,
        )
    }

    /**
     * Parse reflection response in AppAgent format:
     * Decision: BACK/INEFFECTIVE/CONTINUE/SUCCESS
     * Thought: ...
     * Documentation: ...
     */
    private fun parseReflection(response: String): ReflectionResult {
        val decisionStr = extractFieldRobust(response, "Decision") ?: "CONTINUE"
        val thought = extractFieldRobust(response, "Thought") ?: "No thought provided"
        val documentation = extractFieldRobust(response, "Documentation")

        val decision =
            when (decisionStr.uppercase().trim()) {
                "BACK" -> ReflectionDecision.BACK
                "INEFFECTIVE" -> ReflectionDecision.INEFFECTIVE
                "CONTINUE" -> ReflectionDecision.CONTINUE
                "SUCCESS" -> ReflectionDecision.SUCCESS
                else -> ReflectionDecision.CONTINUE
            }

        return ReflectionResult(
            decision = decision,
            thought = thought,
            documentation = documentation,
        )
    }

    /**
     * Extract field - handles various JSON formats and plain text.
     * LLMs return inconsistent formats, so we try multiple patterns.
     */
    private fun extractFieldRobust(
        response: String,
        fieldName: String,
    ): String? {
        // Clean up response - remove leading garbage like {"
        var cleanedResponse = response.trim()
        if (cleanedResponse.startsWith("{") && !cleanedResponse.startsWith("{\"")) {
            // Handle malformed JSON like {"
            cleanedResponse = cleanedResponse.drop(1).trim()
        }
        if (cleanedResponse.startsWith("\"") && cleanedResponse.contains("\":")) {
            // Remove leading quote if it's part of malformed JSON
            cleanedResponse = cleanedResponse.drop(1).trim()
        }

        // Check for obviously broken responses
        if (cleanedResponse.length < 20) {
            return null
        }

        // Pattern 1: Standard JSON - "FieldName": "value" (case-insensitive)
        val standardJsonPattern = Regex("\"${fieldName}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*+)\"", RegexOption.IGNORE_CASE)
        standardJsonPattern.find(cleanedResponse)?.let {
            return it.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").trim()
        }

        // Pattern 2: JSON with newlines - "FieldName":
        //   "value on multiple lines"
        val multilineJsonPattern = Regex("\"${fieldName}\"\\s*:\\s*\"([^\"]*+)\"", RegexOption.IGNORE_CASE)
        multilineJsonPattern.find(cleanedResponse)?.let {
            return it.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").trim()
        }

        // Pattern 3: Plain text - FieldName: value (capture until next field line)
        // Split by lines and find the field
        val lines = cleanedResponse.lines()
        var foundFieldStart = false
        var fieldValue = StringBuilder()
        for (line in lines) {
            val fieldStartMatch = Regex("^${fieldName}\\s*:\\s*", RegexOption.IGNORE_CASE).find(line)
            if (fieldStartMatch != null && !foundFieldStart) {
                foundFieldStart = true
                fieldValue.append(line.substring(fieldStartMatch.range.last + 1))
                continue
            }
            // Check if this line starts a new field
            val newFieldMatch = Regex("^([A-Za-z]+)\\s*:\\s*", RegexOption.IGNORE_CASE).find(line)
            if (newFieldMatch != null && foundFieldStart) {
                // We found a new field, stop capturing
                break
            }
            // Continue capturing if we're in the middle of a field
            if (foundFieldStart) {
                fieldValue.append("\n").append(line)
            }
        }
        if (foundFieldStart && fieldValue.isNotEmpty()) {
            return fieldValue.toString().trim()
        }

        // Pattern 5: Markdown-style bold headers like **Observation:**
        val markdownPattern = Regex("\\*\\*${fieldName}\\*\\*\\s*:\\s*(.+?)(?=\\R\\*\\*[A-Za-z]+\\*\\*|$)", RegexOption.IGNORE_CASE)
        markdownPattern.find(cleanedResponse)?.let {
            return it.groupValues[1].trim()
        }

        return null
    }

    /**
     * Parse action string like "move_caret(cost)" or "press_shortcut(Shift+F6)"
     * Returns RecipeStep (unified action space).
     */
    private fun parseAction(actionStr: String): RecipeStep {
        val trimmed = actionStr.trim()
        if (trimmed == "FINISH") return RecipeStep.CancelDialog

        // Parse function calls like move_caret(cost), press_shortcut(Shift+F6)
        val funcRegex = Regex("(\\w+)\\(([^)]*)\\)")
        val match = funcRegex.find(trimmed)

        if (match != null) {
            val funcName = match.groupValues[1].lowercase()
            val params = match.groupValues[2].replace("\"", "")

            return when (funcName) {
                // Editor operations
                "move_caret" -> RecipeStep.MoveCaret(params)
                "press_shortcut" -> RecipeStep.PressShortcut(params)
                "type_in_dialog", "type" -> RecipeStep.TypeInDialog(params)
                "press_key" -> RecipeStep.PressKey(params)
                "cancel_dialog", "cancel" -> RecipeStep.CancelDialog
                "open_file" -> RecipeStep.OpenFile(params)

                // Vision-based coordinate clicking
                "click_element", "click" -> RecipeStep.ClickElement(params.toIntOrNull() ?: 0)
                "double_click_element", "double_tap", "double_click" -> RecipeStep.DoubleClickElement(params.toIntOrNull() ?: 0)
                "right_click_element", "right_click" -> RecipeStep.RightClickElement(params.toIntOrNull() ?: 0)

                // Default
                else -> RecipeStep.FocusEditor
            }
        }

        return RecipeStep.FocusEditor
    }
}
