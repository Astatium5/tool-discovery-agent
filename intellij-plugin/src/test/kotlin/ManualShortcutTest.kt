package test

import executor.UiExecutor
import org.junit.jupiter.api.Test

/**
 * Manual test - requires user to observe QuestDB IDE behavior.
 * Run this and watch the QuestDB IDE window.
 */
class ManualShortcutTest {

    @Test
    fun `manual test - observe QuestDB IDE during keyboard input`() {
        val robot = com.intellij.remoterobot.RemoteRobot("http://localhost:8082")
        val executor = UiExecutor(robot)

        println("\n=== MANUAL TEST ===")
        println("Watch the QuestDB IDE window")
        println("Waiting 3 seconds before starting...")
        Thread.sleep(3000)

        println("\n1. Testing Command+F (should open Find dialog if file is focused)")
        executor.pressShortcut("cmd f")
        Thread.sleep(2000)

        println("\n2. Testing Escape (should close any open dialogs)")
        executor.pressEscape()
        Thread.sleep(1000)

        println("\n3. Testing Shift+F6 (Rename) - might not work without selection")
        executor.pressShortcut("shift f6")
        Thread.sleep(2000)

        println("\n4. Clean up with Escape")
        repeat(3) {
            executor.pressEscape()
            Thread.sleep(300)
        }

        println("\n=== MANUAL TEST COMPLETE ===")
        println("Did you see the correct behavior in QuestDB IDE?")
    }
}
