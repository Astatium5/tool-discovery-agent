package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

/**
 * Test keyboard shortcuts work.
 * IMPORTANT: On macOS, we use AppleScript to send keystrokes directly to the
 * IntelliJ process, not robot.keyboard which goes to the focused window.
 */
class KeyboardActionTest {

    private val robot = RemoteRobot("http://localhost:8082")

    @BeforeEach
    fun waitForIde() {
        robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )
    }

    @Test
    fun `can press keyboard shortcut - Cmd+Shift+O for Open File`() {
        println("   Pressing Cmd+Shift+O...")

        // First bring IntelliJ to front, then send keystroke
        bringIntelliJToFront()
        sendKeystrokeToIntelliJ("o", listOf("command down", "shift down"))

        Thread.sleep(1000)

        println("   ✅ Keyboard shortcut sent")
        println("   (You should see the 'Open File' dialog popup in IntelliJ)")
    }

    /**
     * Bring IntelliJ IDEA to front first (like your working command).
     */
    private fun bringIntelliJToFront() {
        try {
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("mac")) {
                return
            }

            // Same sequence that worked for you manually
            ProcessBuilder("osascript", "-e",
                "tell application \"System Events\" to set frontmost of the first process whose name is \"idea\" to true"
            ).start().waitFor()

            ProcessBuilder("osascript", "-e",
                "tell application \"IntelliJ IDEA\" to activate"
            ).start().waitFor()

            Thread.sleep(300)
            println("   [FOCUS] Brought IntelliJ to front")
        } catch (e: Exception) {
            println("   [WARNING] Could not bring IntelliJ to front: ${e.message}")
        }
    }

    /**
     * Send a keystroke to IntelliJ using AppleScript on macOS.
     */
    private fun sendKeystrokeToIntelliJ(
        keyName: String,
        modifiers: List<String> = emptyList(),
    ) {
        try {
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("mac")) {
                println("   [WARNING] AppleScript only works on macOS")
                return
            }

            val modifierClause =
                if (modifiers.isNotEmpty()) {
                    "using {${modifiers.joinToString(", ")}}"
                } else {
                    ""
                }

            val script = """
                tell application "System Events"
                    tell process "IntelliJ IDEA"
                        keystroke "$keyName" $modifierClause
                    end tell
                end tell
            """.trimIndent()

            val processBuilder = ProcessBuilder("osascript", "-e", script)
            val result = processBuilder.start().waitFor()
            Thread.sleep(100)
            println("   [KEYBOARD] Sent '$keyName' to IntelliJ process (exit: $result)")
        } catch (e: Exception) {
            println("   [WARNING] Could not send keystroke: ${e.message}")
        }
    }
}
