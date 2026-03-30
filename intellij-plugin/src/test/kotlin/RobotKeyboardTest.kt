package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.awt.event.KeyEvent
import java.time.Duration

/**
 * Test if robot.keyboard works after bringing IntelliJ to front.
 */
class RobotKeyboardTest {

    private val robot = RemoteRobot("http://localhost:8082")

    @BeforeEach
    fun waitForIde() {
        robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )
    }

    @Test
    fun `robot keyboard after bringing IntelliJ to front`() {
        println("   [TEST] Using robot.keyboard after focus...")

        // First bring IntelliJ to front
        ProcessBuilder("osascript", "-e",
            "tell application \"System Events\" to set frontmost of the first process whose name is \"idea\" to true"
        ).start().waitFor()

        ProcessBuilder("osascript", "-e",
            "tell application \"IntelliJ IDEA\" to activate"
        ).start().waitFor()

        Thread.sleep(500)
        println("   [FOCUS] IntelliJ brought to front")

        // Now try robot.keyboard
        robot.keyboard {
            pressing(KeyEvent.VK_META) {
                pressing(KeyEvent.VK_SHIFT) {
                    key(KeyEvent.VK_O)
                }
            }
        }

        Thread.sleep(1000)

        println("   ✅ robot.keyboard completed")
        println("   (Did Open File dialog appear?)")
    }
}
