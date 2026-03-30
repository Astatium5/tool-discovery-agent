package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Minimal smoke test for Remote Robot API.
 * Run this FIRST to verify Remote Robot works at all.
 *
 * BEFORE RUNNING:
 *   Terminal 1 -> ./gradlew runIdeForUiTests
 *   Terminal 2 -> ./gradlew test --tests RemoteRobotSmokeTest
 */
class RemoteRobotSmokeTest {

    private val robot = RemoteRobot("http://localhost:8082")

    @Test
    fun `can connect to IDE`() {
        val ide = robot.find<ComponentFixture>(
            byXpath("//div[@class='IdeFrameImpl']"),
            Duration.ofSeconds(5)
        )
        println("✅ Connected to IDE: ${ide.callJs<String>("component.getClass().simpleName")}")
    }

    @Test
    fun `can find editor component`() {
        val editors = robot.findAll<ComponentFixture>(
            byXpath("//div[@class='EditorComponentImpl']")
        )
        println("✅ Found ${editors.size} editor(s)")
        require(editors.isNotEmpty()) { "No editors found" }
    }

    @Test
    fun `can find by accessible name`() {
        val frame = robot.find<ComponentFixture>(
            byXpath("//div[@accessiblename='tool-discovery-agent - IntelliJ IDEA']"),
            Duration.ofSeconds(5)
        )
        println("✅ Found IDE frame by accessible name")
    }
}
