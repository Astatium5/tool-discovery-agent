package main

import com.intellij.remoterobot.RemoteRobot
import executor.UiExecutor
import graph.GraphAgent
import graph.telemetry.GraphTelemetryFactory
import kotlinx.coroutines.runBlocking
import llm.LlmClient
import parser.HtmlUiTreeProvider
import parser.UiTreeParser

/**
 * Main entry point for the Graph-Enhanced UI Agent.
 *
 * This is a pure-Kotlin implementation of the AppAgentX graph-based approach.
 * It maintains a knowledge graph of UI states and transitions to provide compact
 * context to the LLM instead of dumping raw HTML every step.
 *
 * Environment variables:
 * - ROBOT_URL: Remote Robot URL (default: http://localhost:8082)
 * - LLM_BASE_URL: LLM API base URL (default: https://coding-intl.dashscope.aliyuncs.com/v1)
 * - LLM_MODEL: LLM model name (default: MiniMax-M2.5)
 * - LLM_API_KEY: LLM API key (required)
 *
 * Usage:
 * ```bash
 * ./gradlew runGraphAgent --args="rename method executeRecipe to runRecipe"
 * ```
 */
fun main(args: Array<String>) =
    runBlocking {
        // Load environment variables with defaults
        val robotUrl = loadEnvVar("ROBOT_URL", "http://localhost:8082")
        val llmBaseUrl = loadEnvVar("LLM_BASE_URL", "https://coding-intl.dashscope.aliyuncs.com/v1")
        val llmModel = loadEnvVar("LLM_MODEL", "MiniMax-M2.5")
        val llmApiKey = loadEnvVar("LLM_API_KEY", "")

        // Validate required environment variables
        if (llmApiKey.isEmpty()) {
            System.err.println("ERROR: LLM_API_KEY environment variable is required")
            System.err.println()
            System.err.println("Please set it in your environment or .env file:")
            System.err.println("  export LLM_API_KEY=your_api_key_here")
            System.exit(1)
        }

        // Get task from command line argument (join all args with spaces)
        val task = args.joinToString(" ")
        if (task.isNullOrBlank()) {
            System.err.println("ERROR: No task specified")
            System.err.println()
            System.err.println("Usage: ./gradlew runGraphAgent --args=\"<task description>\"")
            System.err.println()
            System.err.println("Example:")
            System.err.println("  ./gradlew runGraphAgent --args=\"rename method executeRecipe to runRecipe\"")
            System.exit(1)
        }

        // Task is guaranteed to be non-null here
        val nonNullTask: String = task!!

        // Print configuration
        println("=== Graph-Enhanced UI Agent ===")
        println("Robot URL: $robotUrl")
        println("LLM Base URL: $llmBaseUrl")
        println("LLM Model: $llmModel")
        println("Task: $nonNullTask")
        println()

        val telemetry = GraphTelemetryFactory.create(serviceName = "graph-agent")
        Runtime.getRuntime().addShutdownHook(Thread { telemetry.close() })

        try {
            // Initialize components
            println("Initializing components...")

            // Remote Robot connection
            val robot = RemoteRobot(robotUrl)

            // UI Executor for executing actions
            val executor = UiExecutor(robot)

            // LLM Client for reasoning
            val llmClient =
                LlmClient(
                    baseUrl = llmBaseUrl,
                    model = llmModel,
                    apiKey = llmApiKey,
                )

            // UI Tree Provider for fetching HTML from Remote Robot
            val treeProvider = HtmlUiTreeProvider(robotUrl)

            // UI Tree Parser for parsing HTML into PageState (singleton object)
            val parser = UiTreeParser

            // Graph Agent with knowledge graph persistence
            val agent =
                GraphAgent(
                    executor = executor,
                    llmClient = llmClient,
                    treeProvider = treeProvider,
                    parser = parser,
                    graphPath = "data/knowledge_graph.json",
                    maxIterations = 30,
                )

            println("✓ Components initialized")
            println()

            // Execute task
            val result = agent.execute(nonNullTask)

            // Print result
            println()
            println("=== Execution Complete ===")
            println()

            if (result.success) {
                println("✓ SUCCESS")
            } else {
                println("✗ FAILED")
            }

            println()
            println("Message: ${result.message}")
            println("Iterations: ${result.iterations}")
            println("Total Tokens: ${result.tokenCount}")
            println()

            if (result.actionHistory.isNotEmpty()) {
                println("Action Log:")
                result.actionHistory.forEachIndexed { index, record ->
                    val status = if (record.success) "✓" else "✗"
                    println("  ${index + 1}. $status ${record.actionType} on ${record.pageBefore}")
                    if (record.params.isNotEmpty()) {
                        println("     Params: ${record.params}")
                    }
                    if (record.reasoning.isNotEmpty()) {
                        println("     Reasoning: ${record.reasoning.take(100)}${if (record.reasoning.length > 100) "..." else ""}")
                    }
                }
            }

            // Exit with appropriate code
            System.exit(if (result.success) 0 else 1)
        } catch (e: Exception) {
            System.err.println("ERROR: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }

/**
 * Load an environment variable with a default value.
 *
 * First checks System.getenv(), then falls back to reading from .env file
 * in the project root directory.
 */
private fun loadEnvVar(
    name: String,
    default: String,
): String {
    // Check system environment first
    val value = System.getenv(name)
    if (!value.isNullOrBlank()) {
        return value
    }

    // Try to read from .env file
    try {
        val envFile = java.io.File(".env")
        if (envFile.exists()) {
            var result: String? = null
            envFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2 && parts[0].trim() == name) {
                        result = parts[1].trim()
                    }
                }
            }
            if (result != null) {
                return result!!
            }
        }
    } catch (e: Exception) {
        // Ignore .env file reading errors
    }

    return default
}
