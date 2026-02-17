package com.unisaarland.se.findingsnavigator.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.JLabel
import javax.swing.BoxLayout
import com.unisaarland.se.findingsnavigator.service.FindingsService
import com.intellij.openapi.options.ConfigurationException

class FindingsConfigurable(private val project: Project) : Configurable {
    private var panel: JPanel? = null
    private var spinner: JSpinner? = null

    override fun createComponent(): javax.swing.JComponent? {
        if (panel == null) {
            panel = JPanel()
            panel!!.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            val lbl = JLabel("Debounce save delay (ms)")
            spinner = JSpinner(SpinnerNumberModel(1000, 0, 60000, 100))
            panel!!.add(lbl)
            panel!!.add(spinner)
            reset()
        }
        return panel
    }

    override fun isModified(): Boolean {
        val svc = project.getService(FindingsService::class.java)
        val current = svc.getSaveDelayMs()
        val ui = (spinner?.value as? Number)?.toLong() ?: current
        return ui != current
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val svc = project.getService(FindingsService::class.java)
        val ui = (spinner?.value as? Number)?.toLong() ?: svc.getSaveDelayMs()
        svc.setSaveDelayMs(ui)
    }

    override fun reset() {
        val svc = project.getService(FindingsService::class.java)
        spinner?.value = svc.getSaveDelayMs()
    }

    override fun getDisplayName(): String = "Findings Navigator"

    override fun disposeUIResources() {
        panel = null
        spinner = null
    }
}

