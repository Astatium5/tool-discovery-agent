package com.tooldiscovery.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * Action to open agent settings.
 */
class OpenSettingsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            e.project,
            "Tool Discovery Agent",
        )
    }
}
