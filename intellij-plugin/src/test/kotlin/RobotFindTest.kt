package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Test to verify robot.find() works with byXpath and Duration.
 */
class RobotFindTest {

    private val robot = RemoteRobot("http://localhost:8082")

    @Test
    fun `can find component with byXpath and duration`() {
        // This is what UiExecutor.clickComponent() does
        val ide = robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(3)
        )
        println("Found IDE frame: ${ide.callJs<String>("component.getClass().simpleName")}")
    }

    @Test
    fun `can find Search Everywhere button`() {
        // This is what the graph agent tries to do
        val searchButton = robot.find<ComponentFixture>(
            byXpath("//div[@accessiblename='Search Everywhere']"),
            Duration.ofSeconds(3)
        )
        println("Found Search Everywhere button")
    }
}
