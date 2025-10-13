package com.unisaarland.se.findingsnavigator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class HelloAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        Messages.showMessageDialog(
            "Hello from my Kotlin plugin!",
            "Greeting",
            Messages.getInformationIcon()
        )
    }
}
