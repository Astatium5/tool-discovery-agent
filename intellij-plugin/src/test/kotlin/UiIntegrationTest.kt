package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import executor.UiExecutor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.awt.event.KeyEvent
import java.time.Duration

class UiIntegrationTest {

    private val robot = RemoteRobot("http://localhost:8082")
    private lateinit var executor: UiExecutor
    private val testStartTime = mutableMapOf<String, Long>()

    @BeforeEach
    fun setup() {
        val testName = getCurrentTestName()
        testStartTime[testName] = System.currentTimeMillis()

        println("\n${"=".repeat(60)}")
        println("STARTING TEST: $testName")
        println("=".repeat(60))

        bringIntelliJToFront()
        Thread.sleep(300)

        robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )

        executor = UiExecutor(robot)
    }

    @AfterEach
    fun cleanup() {
        val testName = getCurrentTestName()
        val duration = testStartTime[testName]?.let { System.currentTimeMillis() - it }

        try {
            repeat(3) {
                robot.keyboard { key(KeyEvent.VK_ESCAPE) }
                Thread.sleep(200)
            }
        } catch (_: Exception) {}

        println("\n${"=".repeat(60)}")
        println("TEST COMPLETED: $testName${duration?.let { " (${it / 1000}s)" } ?: ""}")
        println("=".repeat(60))
    }

    companion object {
        private var totalTests = 0
        private var completedTests = 0

        @AfterAll
        @JvmStatic
        fun allTestsComplete() {
            println("\n${"=".repeat(60)}")
            println("ALL TESTS COMPLETED - $completedTests tests finished")
            println("=".repeat(60))
        }
    }

    @Test
    fun `Step 1 - Can focus editor`() {
        println("\n[TEST] Focusing editor...")
        executor.focusEditor()
        println("✓ Editor focused")
    }

    @Test
    fun `Step 2 - Can type text in editor`() {
        println("\n[TEST] Typing text in editor...")
        executor.focusEditor()
        Thread.sleep(200)

        executor.typeText("Hello from UI automation test")
        println("✓ Text typed successfully")
    }

    @Test
    fun `Step 3 - Can press Enter key`() {
        println("\n[TEST] Pressing Enter key...")
        executor.focusEditor()
        Thread.sleep(200)

        executor.pressKey("enter")
        println("✓ Enter key pressed")
    }

    @Test
    fun `Step 4 - Can press Escape key`() {
        println("\n[TEST] Pressing Escape key...")
        executor.pressEscape()
        println("✓ Escape key pressed")
    }

    @Test
    fun `Step 5 - Can press keyboard shortcut - Shift+F6 for Rename`() {
        println("\n[TEST] Pressing Shift+F6 (Rename)...")
        executor.pressShortcut("shift f6")
        println("✓ Shortcut sent")
    }

    @Test
    fun `Step 6 - Full mini workflow - Open File dialog`() {
        println("\n[TEST] Full mini workflow...")

        executor.focusEditor()
        println("  1. Focused editor")

        Thread.sleep(200)
        executor.pressShortcut("meta shift o")
        Thread.sleep(800)
        println("  2. Opened file dialog (Cmd+Shift+O)")

        executor.typeText("UiExecutor")
        Thread.sleep(500)
        println("  3. Typed 'UiExecutor' in search")

        executor.pressEscape()
        println("  4. Pressed Escape to cancel")

        println("✓ Mini workflow completed")
    }

    private fun bringIntelliJToFront() {
        try {
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("mac")) return

            ProcessBuilder("osascript", "-e",
                "tell application \"System Events\" to set frontmost of the first process whose name contains \"IntelliJ\" to true"
            ).start().waitFor()

            Thread.sleep(300)
            println("  [FOCUS] IntelliJ brought to front")
        } catch (e: Exception) {
            println("  [WARNING] Could not focus IntelliJ: ${e.message}")
        }
    }

    private fun getCurrentTestName(): String {
        val stackTrace = Thread.currentThread().stackTrace
        val testMethod = stackTrace.find {
            it.className.contains("UiIntegrationTest") &&
            (it.methodName.startsWith("test") || it.methodName.contains("Step"))
        }
        return testMethod?.methodName ?: "UnknownTest"
    }
}
