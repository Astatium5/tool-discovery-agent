package graph

import executor.UiExecutor

/**
 * Narrow execution seam for scripted graph-agent rename flows.
 *
 * This wrapper keeps the graph-specific action surface small while delegating
 * all UI interaction to [UiExecutor].
 */
class GraphActionExecutor(
    private val uiExecutor: UiExecutor,
) {
    data class RenameScriptResult(
        val success: Boolean,
        val message: String,
    )

    fun renameSymbol(originalName: String, renamedName: String): RenameScriptResult {
        return runCatching {
            uiExecutor.moveCaret(originalName)
            uiExecutor.openContextMenu()
            uiExecutor.clickMenuItem("Rename")
            uiExecutor.typeInDialog(renamedName)
            uiExecutor.pressKey("enter")
        }.fold(
            onSuccess = {
                RenameScriptResult(
                    success = true,
                    message = "Renamed '$originalName' to '$renamedName'",
                )
            },
            onFailure = { error ->
                RenameScriptResult(
                    success = false,
                    message = "Rename script failed: ${error.message ?: error::class.simpleName}",
                )
            },
        )
    }
}
