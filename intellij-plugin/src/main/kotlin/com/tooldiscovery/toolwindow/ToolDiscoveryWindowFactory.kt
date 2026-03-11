package com.tooldiscovery.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the Tool Discovery tool window.
 */
class ToolDiscoveryWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val contentFactory = ContentFactory.getInstance()
        val panel = ToolDiscoveryPanel(project)

        val content =
            contentFactory.createContent(
                panel,
                "",
                false,
            )

        toolWindow.contentManager.addContent(content)
    }
}
