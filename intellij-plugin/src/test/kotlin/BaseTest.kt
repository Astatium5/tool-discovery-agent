package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

/**
 * BaseTest
 *
 * All UI tests extend this class.
 *
 * RemoteRobot is our connection handle to the running IDE —
 * think of it like Selenium's WebDriver.
 *
 * BEFORE RUNNING ANY TEST:
 *   Terminal 1 → ./gradlew runIdeForUiTests   (starts the IDE)
 *   Terminal 2 → ./gradlew test               (runs the tests)
 */
abstract class BaseTest {

    // Connect to the IDE sandbox running on port 8082
    // (port is set via systemProperty in runIdeForUiTests task)
    protected val robot = RemoteRobot("http://localhost:8082")

    protected val defaultTimeout: Duration = Duration.ofSeconds(10)

    @BeforeEach
    fun verifyIdeIsRunning() {
        try {
            robot.find<ComponentFixture>(
                byXpath("//div[@class='IdeFrameImpl' or @class='FlatWelcomeFrame']"),
                Duration.ofSeconds(5)
            )
            println("✅ Connected to IDE on port 8082")
        } catch (e: Exception) {
            throw IllegalStateException(
                "❌ Could not connect to IDE. Did you run './gradlew runIdeForUiTests' first?", e
            )
        }
    }
}