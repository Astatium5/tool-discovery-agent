package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.BeforeEach
import java.time.Duration

abstract class BaseTest {
    protected val robotUrl = System.getenv("ROBOT_URL") ?: "http://localhost:8082"
    protected val robot = RemoteRobot(robotUrl)
    protected val defaultTimeout: Duration = Duration.ofSeconds(10)

    @BeforeEach
    fun verifyIdeIsRunning() {
        try {
            robot.find<ComponentFixture>(
                byXpath("//div[@class='IdeFrameImpl' or @class='FlatWelcomeFrame']"),
                Duration.ofSeconds(5),
            )
            println("✅ Stage 0 harness: connected to IDE on $robotUrl")
        } catch (e: Exception) {
            throw IllegalStateException(
                "Stage 0 harness failure: could not connect to IDE at $robotUrl. Start ./gradlew runIdeForUiTests first.",
                e,
            )
        }
    }
}
