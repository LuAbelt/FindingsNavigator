package com.unisaarland.se.findingsnavigator.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.unisaarland.se.findingsnavigator.ui.FindingsPanel

class FindingsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FindingsPanel(project)
        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(panel.component, "Findings", false)
        contentManager.addContent(content)
    }
}
