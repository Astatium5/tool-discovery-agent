package test

import com.intellij.remoterobot.RemoteRobot
import executor.UiExecutor
import graph.GraphAgent
import llm.LlmClient
import org.junit.jupiter.api.Test
import parser.HtmlUiTreeProvider
import parser.UiTreeParser

class GraphAgentRunTest {

    @Test
    fun `run graph agent - rename method test`() {
        val robot = RemoteRobot("http://localhost:8082")
        val executor = UiExecutor(robot)
        val llmClient = LlmClient()
        val treeProvider = HtmlUiTreeProvider("http://localhost:8082")
        // UiTreeParser is an object, not a class

        val agent = GraphAgent(
            executor = executor,
            llmClient = llmClient,
            treeProvider = treeProvider,
            parser = UiTreeParser,
            graphPath = "data/knowledge_graph_test.json",
            maxIterations = 30
        )

        val result = agent.execute("rename the method executeRecipe to runRecipe")

        println("\n=== RESULT ===")
        println("Success: ${result.success}")
        println("Iterations: ${result.iterations}")
        println("Total tokens: ${result.tokenCount}")

        if (!result.success) {
            println("Failed reason: ${result.message}")
        }
    }
}
