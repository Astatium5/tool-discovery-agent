package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import executor.UiExecutor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

/**
 * Debug test to investigate when/why focus switches to KotlinMainWorker.
 */
class FocusDebugTest {

    private val robot = RemoteRobot("http://localhost:8082")
    private lateinit var executor: UiExecutor

    @BeforeEach
    fun setup() {
        bringIntelliJToFront()
        Thread.sleep(500)

        robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )
        executor = UiExecutor(robot)
    }

    @Test
    fun `debug focus - continuous monitoring`() {
        println("\n[DEBUG] Starting continuous focus monitoring...")

        // Start a background thread that continuously monitors focus
        val monitoring = Thread {
            try {
                for (i in 1..100) {
                    val focusedApp = getFocusedApp()
                    if (focusedApp != "idea" && focusedApp != "IntelliJ IDEA") {
                        println("[FOCUS MONITOR] Line $i: Focused app = $focusedApp ⚠️")
                    }
                    Thread.sleep(100)
                }
                println("[FOCUS MONITOR] Monitoring complete")
            } catch (e: Exception) {
                println("[FOCUS MONITOR] Error: ${e.message}")
            }
        }
        monitoring.isDaemon = true
        monitoring.start()

        // Now do the actions
        Thread.sleep(500)
        println("[DEBUG] Focusing editor...")
        executor.focusEditor()

        Thread.sleep(500)
        println("[DEBUG] Typing text...")
        executor.typeText("test123")

        Thread.sleep(500)
        println("[DEBUG] Done - waiting for monitor...")

        // Wait for monitoring to complete (10 seconds)
        monitoring.join()

        println("[DEBUG] Monitoring complete - did you see any focus switches?")
    }


    private fun getFocusedApp(): String {
        return try {
            val processBuilder = ProcessBuilder("osascript", "-e",
                "tell application \"System Events\" to get name of first application process whose frontmost is true"
            )
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun logFocusState(label: String) {
        val focusedApp = getFocusedApp()
        println("[FOCUS $label] Focused app: $focusedApp")
    }

    private fun bringIntelliJToFront() {
        try {
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("mac")) {
                return
            }

            ProcessBuilder("osascript", "-e",
                "tell application \"System Events\" to set frontmost of the first process whose name is \"idea\" to true"
            ).start().waitFor()

            ProcessBuilder("osascript", "-e",
                "tell application \"IntelliJ IDEA\" to activate"
            ).start().waitFor()

            Thread.sleep(300)
        } catch (e: Exception) {
            println("  [WARNING] Could not focus IntelliJ: ${e.message}")
        }
    }
}
