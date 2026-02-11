package main.kotlin.com.unisaarland.se.findingsnavigator.view

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.dsl.builder.panel

class FinderWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: com.intellij.openapi.project.Project, toolWindow: ToolWindow) {
        // Use the Kotlin UI DSL to build the panel
        val contentPanel = panel {
            row {
                label("Hello from my CLion Plugin!")
            }
            row {
                button("Click me") {
                    println("Button clicked in tool window!")
                }
            }
        }

        // Wrap into content and add to the tool window
        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(contentPanel, "", false)
        contentManager.addContent(content)
    }
}

// This file was an earlier sample tool window. It's now unused; keeping as a small stub or consider deleting.
