package execution

fun runTestScript(executor: InProcessGuiExecutor) {
    println("=== Test Script Start ===")

    println("Step 1: Open file")
    executor.openFile("CheckpointCoordinator.java")

    println("Step 2: Move caret to symbol")
    executor.moveCaretToSymbol("completePendingCheckpoint")

    println("Step 3: Open context menu")
    executor.openContextMenu()

    println("Step 4: Expand Refactor submenu")
    executor.clickMenuItemByLabel("Refactor")

    println("Step 5: Diagnose Refactor submenu contents")
    diagnoseRefactorSubmenu(executor)

    println("Step 6: Click Rename")
    executor.clickMenuItemByLabel("Rename")

    println("Step 7: Diagnose Refactor submenu contents")
    diagnoseRefactorSubmenu(executor)

    println("Step 8: Type new name")
    executor.typeInDialog("finalizePendingCheckpoint", clearFirst = true)

    println("Step 9: Press Enter to confirm")
    executor.pressKey("Enter")

    println("=== Test Script End ===")
}

private fun diagnoseRefactorSubmenu(executor: InProcessGuiExecutor) {
    executor.runOnEdtAndWait {
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val group = actionManager.getAction("EditorPopupMenu") as? com.intellij.openapi.actionSystem.DefaultActionGroup
            ?: return@runOnEdtAndWait

        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()!!
        ).selectedTextEditor
        val dataContext = com.intellij.ide.DataManager.getInstance().getDataContext(editor?.contentComponent)

        val children = group.getChildren(com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(group, null, "search", dataContext))
        for (child in children) {
            if (child !is com.intellij.openapi.actionSystem.DefaultActionGroup) continue
            val text = child.templatePresentation.text ?: continue
            if (!text.contains("Refactor", ignoreCase = true)) continue

            println("Refactor group children:")
            val subChildren = child.getChildren(com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(child, null, "search", dataContext))
            for (sub in subChildren) {
                val subText = sub.templatePresentation.text ?: "(no text)"
                val isEnabled = sub.templatePresentation.isEnabled
                println("  - ${sub.javaClass.simpleName}: '$subText' enabled=$isEnabled")
            }
        }
    }
}
