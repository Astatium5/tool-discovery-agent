package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

/**
 * Test that a simple click action works.
 */
class ClickActionTest {

    private val robot = RemoteRobot("http://localhost:8082")

    @BeforeEach
    fun waitForIde() {
        robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )
    }

    @Test
    fun `can click on editor`() {
        // Find all editors (there are 3)
        val editors = robot.findAll<ComponentFixture>(
            byXpath("//div[@class='EditorComponentImpl']")
        )
        println("✅ Found ${editors.size} editor(s)")

        // Click the first one
        editors.first().click()
        println("✅ Clicked on first editor")

        Thread.sleep(500)

        println("   (click executed - you should see focus change in IntelliJ)")
    }
}
