package executor

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import test.BaseTest
import java.time.Duration

/**
 * Hardcoded test for Change Signature operation without LLM calls.
 * This tests the low-level action tools directly to debug issues.
 *
 * BEFORE RUNNING:
 *   Terminal 1 -> ./gradlew runIdeForUiTests
 *   Terminal 2 -> ./gradlew test --tests "executor.ChangeSignatureHardcodedTest"
 */
class ChangeSignatureHardcodedTest : BaseTest() {

    private lateinit var executor: UiExecutor

    @BeforeEach
    fun setup() {
        executor = UiExecutor(robot)
    }

    @Test
    @DisplayName("Hardcoded change signature - no LLM")
    fun testChangeSignatureHardcoded() {
        println("\n=== TEST: Hardcoded Change Signature ===\n")

        // Step 1: Open the file
        println("Step 1: Opening file...")
        executor.openFile("src/main/kotlin/executor/UiExecutor.kt")
        Thread.sleep(1000)

        // Step 2: Move caret to the method
        println("Step 2: Moving caret to executeRecipe method...")
        executor.moveCaret("executeRecipe")
        Thread.sleep(500)

        // Step 3: Open context menu
        println("Step 3: Opening context menu...")
        executor.openContextMenu()
        Thread.sleep(1000)

        // Step 4: Click on Refactor submenu
        println("Step 4: Clicking Refactor...")
        executor.clickMenuItem("Refactor")
        Thread.sleep(500)

        // Step 5: Click on Change Signature
        println("Step 5: Clicking Change Signature...")
        executor.clickMenuItem("Change Signature")
        Thread.sleep(1000)

        // Step 6: Focus the Return type field
        println("Step 6: Focusing Return type field...")
        executor.focusField("Return type")
        Thread.sleep(500)

        // Step 7: Clear and type new value
        println("Step 7: Clearing field and typing 'Void'...")
        executor.typeInDialog("Void", clearFirst = true)
        Thread.sleep(500)

        // Step 8: Click Refactor button
        println("Step 8: Clicking Refactor button...")
        executor.clickDialogButton("Refactor")
        Thread.sleep(1000)

        println("\n=== TEST COMPLETE ===\n")
    }

    @Test
    @DisplayName("Test just the field clearing")
    fun testFieldClearingOnly() {
        println("\n=== TEST: Field Clearing Only ===\n")

        // Open file and dialog first
        println("Setting up: Opening file and dialog...")
        executor.openFile("src/main/kotlin/executor/UiExecutor.kt")
        Thread.sleep(1000)
        executor.moveCaret("executeRecipe")
        Thread.sleep(500)
        executor.openContextMenu()
        Thread.sleep(1000)
        executor.clickMenuItem("Refactor")
        Thread.sleep(500)
        executor.clickMenuItem("Change Signature…")
        Thread.sleep(1000)

        // Now test focusing and clearing
        println("\nTest: Focusing Return type field...")
        executor.focusField("Return type")
        Thread.sleep(500)

        // Check what's in the field before
        println("\nTest: Checking field content before clear...")
        val fieldBefore = try {
            robot.find<ComponentFixture>(
                byXpath("//div[@class='EditorComponentImpl' and contains(@accessiblename, 'Return type')]"),
                Duration.ofSeconds(2)
            )
        } catch (e: Exception) {
            println("  Could not find field: ${e.message}")
            return
        }

        val textBefore = fieldBefore.callJs<String>("component.getEditor().getDocument().getText()")
        println("  Field content before: '$textBefore'")

        // Now clear and type
        println("\nTest: Clearing and typing 'Void'...")
        executor.typeInDialog("Void", clearFirst = true)
        Thread.sleep(500)

        // Check what's in the field after
        val textAfter = fieldBefore.callJs<String>("component.getEditor().getDocument().getText()")
        println("  Field content after: '$textAfter'")

        // Verify
        if (textAfter == "Void") {
            println("\n✓ SUCCESS: Field correctly shows 'Void'")
        } else {
            println("\n✗ FAILURE: Field shows '$textAfter' instead of 'Void'")
        }

        // Close dialog
        println("\nCleanup: Pressing Escape to close dialog...")
        executor.pressKey("escape")
        Thread.sleep(500)
    }

    @Test
    @DisplayName("Test JavaScript clear directly")
    fun testJsClearDirectly() {
        println("\n=== TEST: JavaScript Clear Directly ===\n")

        // Open file and dialog first
        println("Setting up: Opening file and dialog...")
        executor.openFile("src/main/kotlin/executor/UiExecutor.kt")
        Thread.sleep(1000)
        executor.moveCaret("executeRecipe")
        Thread.sleep(500)
        executor.openContextMenu()
        Thread.sleep(1000)
        executor.clickMenuItem("Refactor")
        Thread.sleep(500)
        executor.clickMenuItem("Change Signature…")
        Thread.sleep(1000)

        // Find the field
        println("\nTest: Finding Return type field...")
        val field = try {
            robot.find<ComponentFixture>(
                byXpath("//div[@class='EditorComponentImpl' and contains(@accessiblename, 'Return type')]"),
                Duration.ofSeconds(2)
            )
        } catch (e: Exception) {
            println("  Could not find field: ${e.message}")
            return
        }

        // Check component class
        val componentClass = field.callJs<String>("component.getClass().getName()")
        println("  Component class: $componentClass")

        // Check content before
        val textBefore = field.callJs<String>("component.getEditor().getDocument().getText()")
        println("  Content before: '$textBefore'")

        // Try direct JavaScript clear
        println("\nTest: Executing JavaScript clear...")
        try {
            val result = field.callJs<String>("""
                (function() {
                    try {
                        var editor = component.getEditor();
                        if (editor == null) return 'ERROR: editor is null';
                        var doc = editor.getDocument();
                        var oldText = doc.getText();
                        doc.setText('');
                        return 'OK: cleared ' + oldText.length + ' chars';
                    } catch (e) {
                        return 'ERROR: ' + e.message;
                    }
                })();
            """)
            println("  JavaScript result: $result")
        } catch (e: Exception) {
            println("  JavaScript error: ${e.message}")
        }

        // Check content after
        val textAfter = field.callJs<String>("component.getEditor().getDocument().getText()")
        println("  Content after: '$textAfter'")

        // Close dialog
        println("\nCleanup: Pressing Escape to close dialog...")
        executor.pressKey("escape")
        Thread.sleep(500)
    }
}