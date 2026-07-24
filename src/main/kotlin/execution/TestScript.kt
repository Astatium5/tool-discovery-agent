package execution

fun runTestScript(executor: InProcessGuiExecutor) {
    println("=== Test Script Start ===")
    println("Task 01: Rename completePendingCheckpoint → finalizePendingCheckpoint")

    println("Step 1: Open file")
    executor.openFile("CheckpointCoordinator.java")

    println("Step 2: Move caret to symbol")
    executor.moveCaretToSymbol("completePendingCheckpoint")

    println("Step 3: Open context menu")
    executor.openContextMenu()

    println("Step 4: Expand Refactor submenu")
    executor.clickMenuItemByLabel("Refactor")

    println("Step 5: Click Rename")
    executor.clickMenuItemByLabel("Rename")

    println("Step 6: Type new name")
    executor.typeInDialog("finalizePendingCheckpoint", clearFirst = true)

    println("Step 7: Press Enter to confirm")
    executor.pressKey("Enter")

    println("=== Test Script End ===")
}
