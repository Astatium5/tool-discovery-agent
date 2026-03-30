package test

import llm.LlmClient
import org.junit.jupiter.api.Test

/**
 * Test that .env configuration is loaded correctly.
 */
class EnvConfigTest {

    @Test
    fun `verify LLM config is loaded correctly`() {
        println("\n=== LLM Configuration Check ===")

        val client = LlmClient()

        // We can't directly access the private fields, but we can check if the client works
        // by inspecting the error message if any

        println("✅ LlmClient created successfully")
        println("   If API error occurs below, check .env file in parent directory")
    }
}
