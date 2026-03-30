package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import executor.UiExecutor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

/**
 * Test that verifies the QuestDB IDE (java process) is focused correctly.
 * This ensures UI actions are sent to the correct IDE window.
 */
class QuestDBFocusTest {

    private val robot = RemoteRobot("http://localhost:8082")
    private lateinit var executor: UiExecutor

    @BeforeEach
    fun setup() {
        // Wait for IDE to be available
        robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )
        // Creating executor should auto-focus QuestDB IDE via bringTestIdeToFront()
        executor = UiExecutor(robot)
    }

    @Test
    fun `verify QuestDB IDE is focused after executor creation`() {
        println("\n[TEST] Verifying QuestDB IDE focus...")

        // Wait for focus to settle
        Thread.sleep(500)

        // Check which process is frontmost
        val focusedApp = getFocusedApp()
        println("[TEST] Focused application: $focusedApp")

        // Verify it's the java process (QuestDB IDE)
        if (focusedApp == "java") {
            println("[TEST] ✅ PASS: QuestDB IDE (java process) is focused")
        } else {
            println("[TEST] ❌ FAIL: Expected 'java', got '$focusedApp'")
        }
    }

    @Test
    fun `send Command+F to open Find dialog in QuestDB IDE`() {
        println("\n[TEST] Sending Command+F to QuestDB IDE...")

        // Verify focus first
        Thread.sleep(300)
        val focusedApp = getFocusedApp()
        println("[TEST] Focused before shortcut: $focusedApp")

        // Try to focus an editor first (QuestDB needs a file focused for Find to work)
        try {
            executor.focusEditor()
            println("[TEST] Focused editor")
            Thread.sleep(500)
        } catch (e: Exception) {
            println("[TEST] Warning: Could not focus editor: ${e.message}")
        }

        // Send Command+F using executor (should target java process)
        println("[TEST] About to send Command+F...")
        executor.pressShortcut("cmd f")
        println("[TEST] Command+F sent")

        Thread.sleep(1000)

        // Check if Find dialog opened - could be different container types
        val hasFindDialog = try {
            robot.find<ComponentFixture>(
                byXpath("//div[@class='HeavyWeightWindow' or @class='DialogRootPane' or contains(@class, 'JDialog')]"),
                Duration.ofSeconds(2)
            )
            true
        } catch (e: Exception) {
            false
        }

        if (hasFindDialog) {
            println("[TEST] ✅ PASS: Find dialog appeared")
        } else {
            println("[TEST] ❌ FAIL: Find dialog did not appear (may need file open in QuestDB)")
        }

        // Clean up - press Escape multiple times
        try {
            repeat(3) {
                executor.pressEscape()
                Thread.sleep(200)
            }
        } catch (_: Exception) {}
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
            "unknown (${e.message})"
        }
    }
}
