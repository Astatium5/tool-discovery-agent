package test

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.BeforeEach
import executor.UiExecutor
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

abstract class BaseTest {
    protected val robotUrl = System.getenv("ROBOT_URL") ?: "http://localhost:8082"
    protected val robot = RemoteRobot(robotUrl)
    protected val defaultTimeout: Duration = Duration.ofSeconds(10)
    protected val canonicalRenameFixtureContents: String =
        Path.of("src/test/kotlin/fixtures/GraphAgentRenameFixture.kt").toAbsolutePath().normalize().toFile().readText()

    @BeforeEach
    fun verifyIdeIsRunning() {
        try {
            robot.find<ComponentFixture>(
                byXpath("//div[@class='IdeFrameImpl']"),
                Duration.ofSeconds(5),
            )
            println("✅ Stage 0 harness: connected to IDE on $robotUrl")
        } catch (e: Exception) {
            throw when (e.hasNetworkCause()) {
                true -> IllegalStateException(
                    "Stage 0 harness failure: could not connect to IDE at $robotUrl. Start ./gradlew runIdeForUiTests first.",
                    e,
                )
                false -> IllegalStateException(
                    "Stage 0 harness failure: IDE at $robotUrl is reachable, but the expected project window was not found. Start ./gradlew runIdeForUiTests and open the IDE project before running tests.",
                    e,
                )
            }
        }
    }

    protected fun openFreshCanonicalRenameFixture(executor: UiExecutor): String {
        return openFreshRenameFixture(executor, GraphAgentRenameFixtureScenario.canonical)
    }

    protected fun openFreshRenameFixture(
        executor: UiExecutor,
        fixture: GraphAgentRenameFixtureScenario,
    ): String {
        val tempDir = Path.of("build", "tmp", "graph-agent-fixtures").toAbsolutePath().normalize()
        Files.createDirectories(tempDir)
        val fixturePath = tempDir.resolve("${fixture.fileStem}-${UUID.randomUUID()}.kt")
        Files.writeString(fixturePath, fixture.contents)
        executor.openFile(fixturePath.toString())
        return fixturePath.toString()
    }

    private fun Throwable.hasNetworkCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ConnectException || current is SocketTimeoutException || current is UnknownHostException) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
