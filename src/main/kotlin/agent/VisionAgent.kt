package agent

import com.intellij.openapi.diagnostic.logger
import com.intellij.remoterobot.RemoteRobot
import dev.langchain4j.model.chat.ChatModel
import execution.UiExecutor
import model.RecipeStep
import perception.vision.RemoteRobotScreenshotProvider
import profile.ApplicationProfile
import reasoner.VisionReasoner
import reasoner.VisionReasoner.ReflectionDecision
import reasoner.VisionReasoner.VisionContext
import vision.ElementInfo
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Vision-based agent implementing the actual AppAgent approach from the paper.
 *
 * Key implementation details from AppAgent (self_explorer.py):
 * - Exploration phase: Attempt to complete the task directly (not random exploration)
 * - Reflection after each action: Compare before/after screenshots
 * - Reflection decisions: BACK (wrong page), INEFFECTIVE (no change), CONTINUE/SUCCESS
 * - Documentation: Generated from reflection, stored per element UID (not numeric tag)
 * - Traces: Labeled screenshots saved for each step, JSON trace file
 *
 * Flow (from self_explorer.py):
 * 1. Take screenshot BEFORE action, draw labeled overlays
 * 2. LLM decides action to attempt task
 * 3. Execute action
 * 4. Take screenshot AFTER action, draw labeled overlays
 * 5. LLM reflects: BACK/INEFFECTIVE/CONTINUE/SUCCESS + generates documentation
 * 6. If BACK: press Escape and continue
 * 7. If INEFFECTIVE: mark element as useless, continue
 * 8. If CONTINUE/SUCCESS: save documentation, continue
 * 9. Save trace with labeled screenshots and action info
 */
class VisionAgent(
    private val llm: ChatModel,
    private val profile: ApplicationProfile,
    private val robot: RemoteRobot,
    private val documentationPath: String? = null,
    private val tracePath: String? = null,
) {
    private val log = logger<VisionAgent>()
    private val screenshotProvider = RemoteRobotScreenshotProvider(robot, profile)
    private val reasoner = VisionReasoner(llm)

    // Documentation stored by element UID (resource_id)
    private val documentation: MutableMap<String, ElementDoc> = mutableMapOf()
    private val uselessElements: MutableSet<String> = mutableSetOf()

    // Trace storage
    private val traceSteps: MutableList<TraceStep> = mutableListOf()

    init {
        loadDocumentation()
    }

    /**
     * Documentation for a UI element, keyed by resource_id (UID).
     * PC-specific documentation fields for desktop IDE interaction.
     */
    data class ElementDoc(
        val uid: String,
        val description: String = "", // What this element IS
        var click: String = "", // Single click effect
        var doubleClick: String = "", // Double click effect
        var rightClick: String = "", // Context menu effect (right-click)
        var type: String = "", // Text input effect (for input fields)
    )

    /**
     * Trace step - one exploration action with labeled screenshots.
     * Matches AppAgent's ExplorationTrace.TraceStep.
     */
    data class TraceStep(
        val stepNumber: Int,
        val screenshotBefore: BufferedImage? = null,
        val screenshotAfter: BufferedImage? = null,
        val observation: String = "",
        val thought: String = "",
        val action: String = "",
        val actionResult: String = "",
        val elementId: Int? = null,
        val learnedFunction: String? = null,
        val decision: String = "",
    )

    /**
     * Result of executing a task.
     */
    data class VisionResult(
        val success: Boolean,
        val message: String,
        val actionsTaken: Int = 0,
        val docsGenerated: Int = 0,
    )

    companion object {
        // Uses AgentConfig for all configurable values

        private fun imageToBase64(image: BufferedImage): String {
            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "png", baos)
            return Base64.getEncoder().encodeToString(baos.toByteArray())
        }
    }

    /**
     * Execute a task using the AppAgent approach.
     * Same flow for both "exploration" and "deployment" - attempt the task directly.
     *
     * @param intent Task description
     * @param generateDocs If true, generate documentation during execution
     * @return VisionResult
     */
    fun execute(
        intent: String,
        generateDocs: Boolean = false,
    ): VisionResult {
        println("\n=== VISION AGENT ===")
        println("Task: $intent")
        println("Generate docs: $generateDocs")
        log.info("\n=== VISION AGENT (AppAgent Approach) ===")
        log.info("Task: $intent")
        log.info("Generate docs: $generateDocs")
        log.info("Existing docs: ${documentation.size} elements")
        if (tracePath != null) {
            println("Trace: $tracePath")
            log.info("Trace will be saved to: $tracePath")
        }

        // Create executor early for completion detection
        val executor = UiExecutor(robot)

        // Capture initial document text for diff-based completion detection
        val initialDocumentText = executor.getDocumentText()
        println("  Initial document captured: ${initialDocumentText?.take(50)}...")

        var iteration = 0
        var lastActionSummary = "None"
        var lastAction: RecipeStep? = null // Track previous action to prevent repetition
        var consecutiveSameActions = 0 // Count of same action repeats
        var taskComplete = false
        var failed = false
        var docsGenerated = 0

        while (iteration < AgentConfig.maxIterations && !taskComplete && !failed) {
            iteration++
            println("\n--- Round $iteration ---")
            log.info("\n--- Round $iteration ---")

            // 1. OBSERVE: Capture screenshot with overlays
            val beforeResult = screenshotProvider.capture()
            val beforeScreenshot = beforeResult.screenshot
            println("  Elements: ${beforeResult.elementMap.size}")
            log.info("  Detected ${beforeResult.elementMap.size} labeled UI elements (toolbar/menu only)")

            // 2. DECIDE: LLM decides action to attempt task
            val context =
                VisionContext(
                    intent = intent,
                    screenshot = beforeScreenshot,
                    elementMap = beforeResult.elementMap,
                    lastActionSummary = lastActionSummary,
                    documentation = getNumericTagDocs(beforeResult.elementMap),
                )

            val decision = reasoner.decide(context)
            println("  Action: ${decision.action.describe()} (complete: ${decision.taskComplete})")
            println("  Thought: ${decision.thought}")
            println("  Observation: ${decision.observation}")
            log.info("  Observation: ${decision.observation}")
            log.info("  Thought: ${decision.thought}")
            log.info("  Action: ${decision.action.describe()}")
            log.info("  Summary: ${decision.summary}")

            if (decision.taskComplete || decision.action is RecipeStep.CancelDialog) {
                println("  Task complete!")
                log.info("  Task complete!")
                taskComplete = true
                break
            }

            // Check for action repetition (loop detection)
            if (decision.action == lastAction) {
                consecutiveSameActions++
                if (consecutiveSameActions >= AgentConfig.loopDetectionThreshold) {
                    println("  WARNING: Action repeated $consecutiveSameActions times - stopping")
                    failed = true
                    break
                }
            } else {
                consecutiveSameActions = 0
            }

            // 3. ACT: Execute the action using UiExecutor (with elementMap for clicking)
            executor.setElementMap(beforeResult.elementMap)
            try {
                println("  Executing: ${decision.action.describe()}")
                executor.executeStep(decision.action)
                println("  Result: SUCCESS")
                log.info("  Action executed: ${decision.action.describe()}")
                lastAction = decision.action
                lastActionSummary = "SUCCESS: ${decision.action.describe()}. ${decision.summary}"
            } catch (e: Exception) {
                println("  Result: FAILED - ${e.message}")
                log.warn("  Action failed: ${e.message}")
                lastActionSummary = "FAILED: ${decision.action.describe()} - ${e.message}"
                lastAction = decision.action
                Thread.sleep(AgentConfig.observeDelayMs)
                continue
            }

            // Check for diff-based completion after action
            if (checkDiffBasedCompletion(executor, initialDocumentText)) {
                println("  ✓ Diff-based completion detected: source code changed and no blockers")
                log.info("  Diff-based completion detected: source code changed and no blockers")
                taskComplete = true
                break
            }

            // Get element UID for documentation
            val elementId = getElementId(decision.action)
            val element = beforeResult.elementMap[elementId]
            val uid = element?.uid ?: "unknown_$elementId"

            // 4. OBSERVE: Capture screenshot AFTER action
            Thread.sleep(AgentConfig.observeDelayMs)
            val afterResult = screenshotProvider.capture()
            val afterScreenshot = afterResult.screenshot

            // 5. REFLECT: Compare before/after to decide what to do next
            var learnedFunction: String? = null
            var reflectionDecision = "CONTINUE"

            if (generateDocs && elementId != null && element != null) {
                println("  Reflecting on action...")
                val reflection =
                    reasoner.reflect(
                        screenshotBefore = beforeScreenshot,
                        screenshotAfter = afterScreenshot,
                        action = decision.action,
                        elementId = elementId,
                        lastActionSummary = decision.summary,
                        taskDescription = intent,
                    )

                reflectionDecision = reflection.decision.name
                println("  Reflection: ${reflection.decision}")
                log.info("  Reflection: ${reflection.decision}")
                log.info("  Reflection thought: ${reflection.thought}")

                when (reflection.decision) {
                    ReflectionDecision.BACK -> {
                        println("  Going back (wrong page)")
                        log.info("  Going back (wrong page)")
                        executor.executeStep(RecipeStep.CancelDialog)
                        Thread.sleep(AgentConfig.visionGoBackDelayMs)

                        if (reflection.documentation != null) {
                            learnedFunction = reflection.documentation
                            saveDocumentation(uid, decision.action, reflection.documentation)
                            docsGenerated++
                        }
                        lastActionSummary = "None"
                        uselessElements.add(uid)
                    }

                    ReflectionDecision.INEFFECTIVE -> {
                        println("  Action ineffective")
                        log.info("  Action ineffective, marking element useless")
                        uselessElements.add(uid)
                        lastActionSummary = "None"
                    }

                    ReflectionDecision.CONTINUE -> {
                        log.info("  Action didn't move task forward")
                        if (reflection.documentation != null) {
                            learnedFunction = reflection.documentation
                            saveDocumentation(uid, decision.action, reflection.documentation)
                            docsGenerated++
                        }
                        uselessElements.add(uid)
                        lastActionSummary = "None"
                    }

                    ReflectionDecision.SUCCESS -> {
                        println("  Action successful!")
                        log.info("  Action successful!")
                        if (reflection.documentation != null) {
                            learnedFunction = reflection.documentation
                            saveDocumentation(uid, decision.action, reflection.documentation)
                            docsGenerated++
                        }
                        lastActionSummary = decision.summary
                    }
                }
            } else {
                lastActionSummary = decision.summary
            }

            // 6. TRACE: Save step to trace
            if (tracePath != null) {
                traceSteps.add(
                    TraceStep(
                        stepNumber = iteration,
                        screenshotBefore = beforeScreenshot,
                        screenshotAfter = afterScreenshot,
                        observation = decision.observation,
                        thought = decision.thought,
                        action = decision.action.describe(),
                        actionResult = lastActionSummary,
                        elementId = elementId,
                        learnedFunction = learnedFunction,
                        decision = reflectionDecision,
                    ),
                )
                // Save incrementally for debugging
                if (iteration % AgentConfig.traceSaveInterval == 0 || taskComplete) {
                    saveTraceFile()
                }
            }
        }

        // Save documentation to file
        if (generateDocs) {
            saveDocumentationFile()
        }

        // Save trace to file and release memory
        if (tracePath != null && traceSteps.isNotEmpty()) {
            saveTraceFile()
            releaseTraceMemory()
        }

        val message =
            when {
                taskComplete -> "Task completed successfully"
                failed -> "Task failed"
                iteration >= AgentConfig.maxIterations -> "Max iterations reached"
                else -> "Unknown state"
            }

        println("\n=== RESULT ===")
        println("Success: $taskComplete")
        println("Message: $message")
        println("Steps: $iteration")
        println("Docs: $docsGenerated")
        log.info("\n=== RESULT ===")
        log.info("Success: $taskComplete")
        log.info("Message: $message")
        log.info("Actions taken: $iteration")
        log.info("Docs generated: $docsGenerated")
        log.info("Trace steps: ${traceSteps.size}")

        return VisionResult(
            success = taskComplete,
            message = message,
            actionsTaken = iteration,
            docsGenerated = docsGenerated,
        )
    }

    /**
     * Exploration phase - actually just execute() with generateDocs=true.
     * AppAgent doesn't have separate "random exploration" - it attempts the task.
     */
    fun explore(
        explorationTask: String,
        maxSteps: Int = AgentConfig.maxIterations,
    ): VisionResult {
        // Clear useless elements for fresh exploration
        uselessElements.clear()

        return execute(
            intent = explorationTask,
            generateDocs = true,
        )
    }

    // ── Completion Detection ─────────────────────────────────────────────────────────

    /**
     * Check for diff-based completion.
     *
     * A task is considered complete if:
     * 1. We have initial document text captured
     * 2. The current document text differs from initial
     * 3. No popups/dialogs/inline editors are currently open
     * 4. No inline refactoring is active
     *
     * This prevents the bug where agent presses Enter twice after refactoring -
     * once to confirm, then incorrectly again causing line splits.
     */
    private fun checkDiffBasedCompletion(
        executor: UiExecutor,
        initialDocumentText: String?,
    ): Boolean {
        if (initialDocumentText == null) {
            println("  [DEBUG] No initial document text - skipping diff check")
            return false
        }

        // Get current document text
        val currentText = executor.getDocumentText()
        if (currentText == null) {
            println("  [DEBUG] Could not get current document text - skipping diff check")
            return false
        }

        println("  [DEBUG] Diff check: initial=${initialDocumentText.take(50)}..., current=${currentText.take(50)}...")

        // Check if document changed
        if (currentText == initialDocumentText) {
            println("  [DEBUG] No document change detected")
            return false
        }

        println("  [DEBUG] Document changed! Checking for blockers...")

        // Check if inline refactoring is active (prevents premature completion)
        val inlineRefactoringActive = executor.hasInlineRefactoringActive()
        println("  [DEBUG] inlineRefactoringActive = $inlineRefactoringActive")
        if (inlineRefactoringActive) {
            println("  Inline refactoring is active - preventing premature completion")
            return false
        }

        // Check if editor has an active selection (inline refactoring mode)
        val hasEditorSelection = try { executor.hasEditorSelection() } catch (e: Exception) { false }
        println("  [DEBUG] hasEditorSelection = $hasEditorSelection")
        if (hasEditorSelection) {
            println("  Editor has selection - preventing premature completion")
            return false
        }

        // Complete if document changed and no blockers
        println("  [DEBUG] Completion conditions satisfied!")
        return true
    }

    // ── Documentation Helpers ─────────────────────────────────────────────────────────

    /**
     * Get element ID from action.
     */
    private fun getElementId(action: RecipeStep): Int? {
        return when (action) {
            is RecipeStep.ClickElement -> action.elementId
            is RecipeStep.DoubleClickElement -> action.elementId
            is RecipeStep.RightClickElement -> action.elementId
            else -> null
        }
    }

    /**
     * Get documentation keyed by numeric tag (for LLM prompt).
     * Maps UID -> numeric tag based on current elementMap.
     */
    private fun getNumericTagDocs(elementMap: Map<Int, ElementInfo>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for ((numericTag, element) in elementMap) {
            val uid = element.uid
            val doc = documentation[uid]
            if (doc != null) {
                val parts = mutableListOf<String>()
                if (doc.description.isNotBlank()) parts.add("Description: ${doc.description}")
                if (doc.click.isNotBlank()) parts.add("Click: ${doc.click}")
                if (doc.doubleClick.isNotBlank()) parts.add("Double-click: ${doc.doubleClick}")
                if (doc.rightClick.isNotBlank()) parts.add("Right-click: ${doc.rightClick}")
                if (doc.type.isNotBlank()) parts.add("Type input: ${doc.type}")
                if (parts.isNotEmpty()) {
                    result[numericTag] = parts.joinToString("\n")
                }
            }
        }
        return result
    }

    /**
     * Save documentation for an element action.
     */
    private fun saveDocumentation(
        uid: String,
        action: RecipeStep,
        doc: String,
    ) {
        val elementDoc = documentation.getOrPut(uid) { ElementDoc(uid) }
        when (action) {
            is RecipeStep.ClickElement -> elementDoc.click = doc
            is RecipeStep.DoubleClickElement -> elementDoc.doubleClick = doc
            is RecipeStep.RightClickElement -> elementDoc.rightClick = doc
            is RecipeStep.TypeInDialog -> elementDoc.type = doc
            else -> {}
        }
        log.info("  Saved doc for $uid: ${doc.take(50)}...")
    }

    /**
     * Load documentation from file.
     */
    private fun loadDocumentation() {
        if (documentationPath == null) return
        val file = File(documentationPath)
        if (!file.exists()) return
        try {
            val lines = file.readLines()
            for (line in lines) {
                if (line.isBlank()) continue
                // Format: uid|description|click|doubleClick|rightClick|type
                val parts = line.split("|")
                if (parts.size >= 6) {
                    documentation[parts[0]] =
                        ElementDoc(
                            uid = parts[0],
                            description = parts[1],
                            click = parts[2],
                            doubleClick = parts[3],
                            rightClick = parts[4],
                            type = parts[5],
                        )
                }
            }
            log.info("Loaded ${documentation.size} element docs from $documentationPath")
        } catch (e: Exception) {
            log.warn("Failed to load documentation: ${e.message}")
        }
    }

    /**
     * Save documentation to file.
     */
    private fun saveDocumentationFile() {
        if (documentationPath == null) return
        val file = File(documentationPath)
        file.parentFile?.mkdirs()
        try {
            // Format: uid|description|click|doubleClick|rightClick|type
            val lines =
                documentation.values.map { doc ->
                    "${doc.uid}|${doc.description}|${doc.click}|${doc.doubleClick}|${doc.rightClick}|${doc.type}"
                }
            file.writeText(lines.joinToString("\n"))
            log.info("Saved ${documentation.size} element docs to $documentationPath")
        } catch (e: Exception) {
            log.warn("Failed to save documentation: ${e.message}")
        }
    }

    /**
     * Clear documentation.
     */
    fun clearDocumentation() {
        documentation.clear()
        uselessElements.clear()
        if (documentationPath != null) {
            File(documentationPath).delete()
        }
    }

    /**
     * Get documentation count.
     */
    fun getDocCount(): Int = documentation.size

    // ── Trace Storage (AppAgent's exploration-trace.json) ───────────────────────────────────

    /**
     * Save trace to file. Matches AppAgent's exploration-trace.json structure.
     * Creates:
     * - JSON trace file with step metadata
     * - Labeled screenshot PNGs for each step
     */
    private fun saveTraceFile() {
        if (tracePath == null || traceSteps.isEmpty()) return

        val traceFile = File(tracePath)
        val traceDir = traceFile.parentFile
        traceDir.mkdirs()

        try {
            // Save screenshots as PNG files
            var screenshotCount = 0
            for (step in traceSteps) {
                if (step.screenshotBefore != null) {
                    val beforeFile = File(traceDir, "step_${step.stepNumber}_before.png")
                    ImageIO.write(step.screenshotBefore, "png", beforeFile)
                    screenshotCount++
                }
                if (step.screenshotAfter != null) {
                    val afterFile = File(traceDir, "step_${step.stepNumber}_after.png")
                    ImageIO.write(step.screenshotAfter, "png", afterFile)
                    screenshotCount++
                }
            }

            // Save JSON trace
            val traceJson = buildTraceJson()
            traceFile.writeText(traceJson)

            log.info("\n=== TRACE SAVED ===")
            log.info("  Trace JSON: ${traceFile.absolutePath}")
            log.info("  Screenshots: ${traceDir.absolutePath} ($screenshotCount PNG files)")
        } catch (e: Exception) {
            log.error("Failed to save trace: ${e.message}", e)
        }
    }

    /**
     * Release memory held by screenshots in trace steps.
     * Prevents memory leak when trace steps are cleared.
     */
    private fun releaseTraceMemory() {
        for (step in traceSteps) {
            step.screenshotBefore?.flush()
            step.screenshotAfter?.flush()
        }
        traceSteps.clear()
    }

    /**
     * Build JSON trace matching AppAgent's exploration-trace.json structure.
     */
    private fun buildTraceJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"trace\": [\n")

        for (i in traceSteps.indices) {
            val step = traceSteps[i]
            sb.append("    {\n")
            sb.append("      \"step_number\": ${step.stepNumber},\n")
            sb.append("      \"screenshot_before\": \"step_${step.stepNumber}_before.png\",\n")
            sb.append("      \"screenshot_after\": \"step_${step.stepNumber}_after.png\",\n")
            sb.append("      \"observation\": \"${escapeJson(step.observation)}\",\n")
            sb.append("      \"thought\": \"${escapeJson(step.thought)}\",\n")
            sb.append("      \"action\": \"${escapeJson(step.action)}\",\n")
            sb.append("      \"action_result\": \"${escapeJson(step.actionResult)}\",\n")
            sb.append("      \"element_id\": ${step.elementId ?: "null"},\n")
            sb.append(
                "      \"learned_function\": ${if (step.learnedFunction != null) "\"${escapeJson(step.learnedFunction)}\"" else "null"},\n",
            )
            sb.append("      \"decision\": \"${step.decision}\"\n")
            sb.append("    }")
            if (i < traceSteps.lastIndex) sb.append(",")
            sb.append("\n")
        }

        sb.append("  ],\n")
        sb.append("  \"total_steps\": ${traceSteps.size}\n")
        sb.append("}\n")

        return sb.toString()
    }

    /**
     * Escape string for JSON.
     */
    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
