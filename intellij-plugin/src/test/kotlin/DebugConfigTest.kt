package test

import llm.LlmClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Debug test to check LLM configuration values.
 */
class DebugConfigTest {

    @Test
    fun `print actual LLM config values`() {
        println("\n=== Checking .env file locations ===")

        val locations = listOf(
            File(".env"),
            File("../.env"),
            File("../../.env")
        )

        for (loc in locations) {
            println("  ${loc.absolutePath}: exists=${loc.exists()}")
            if (loc.exists()) {
                val lines = loc.readLines().take(5)
                println("    First 5 lines:")
                lines.forEach { println("      $it") }
            }
        }

        println("\n=== Testing URL construction ===")

        // Simulate what LlmClient does - check .env FIRST
        fun loadEnvVar(name: String, default: String): String {
            // Check .env file first
            val envLocations = listOf(
                File("../.env"),      // Project root where real .env is
                File("../../.env"),   // Two levels up
                File(".env")          // Current directory (may be Gradle-generated)
            )
            for (envFile in envLocations) {
                if (envFile.exists()) {
                    val lines = envFile.readLines()
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$name=") && !trimmed.startsWith("#")) {
                            val value = trimmed.substringAfter("$name=").trim()
                            if (value.isNotEmpty()) {
                                return value
                            }
                        }
                    }
                }
            }

            // Fall back to system environment (may contain Gradle placeholders)
            val sysEnv = System.getenv(name)
            if (!sysEnv.isNullOrBlank()) {
                return sysEnv
            }

            return default
        }

        val baseUrl = loadEnvVar("LLM_BASE_URL", "https://coding-intl.dashscope.aliyuncs.com/v1")
        val model = loadEnvVar("LLM_MODEL", "MiniMax-M2.5")
        val apiKey = loadEnvVar("LLM_API_KEY", "")

        println("  baseUrl = '$baseUrl'")
        println("  model = '$model'")
        println("  apiKey = '${apiKey.take(10)}...'")

        println("\n  Full URL: $baseUrl/chat/completions")

        // Try to create a valid URL
        try {
            val url = "$baseUrl/chat/completions"
            println("  ✅ URL looks valid: $url")
        } catch (e: Exception) {
            println("  ❌ URL error: ${e.message}")
        }
    }
}
