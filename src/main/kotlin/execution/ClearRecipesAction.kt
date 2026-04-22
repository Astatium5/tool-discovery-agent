package execution

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import recipe.RecipeRegistry

class ClearRecipesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val registry = RecipeRegistry()
        val count = registry.all().size

        val confirmed =
            Messages.showYesNoDialog(
                "Clear $count saved recipes?\nThis will reset all learned patterns.",
                "Clear Recipes",
                "Clear",
                "Cancel",
                Messages.getWarningIcon(),
            )

        if (confirmed == Messages.YES) {
            registry.clear()
            Messages.showInfoMessage("Cleared $count recipes.", "Clear Recipes")
        }
    }
}