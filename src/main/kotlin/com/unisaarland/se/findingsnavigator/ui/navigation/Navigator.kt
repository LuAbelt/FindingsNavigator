package com.unisaarland.se.findingsnavigator.ui.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object Navigator {
    fun open(project: Project, relativePath: String, line: Int?, column: Int?) {
        ApplicationManager.getApplication().invokeLater {
            val base = project.basePath ?: ""
            val full = if (File(relativePath).isAbsolute) relativePath else File(base, relativePath).absolutePath
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(full))
            if (vf != null) {
                val l = (line?.coerceAtLeast(1) ?: 1) - 1
                val c = (column?.coerceAtLeast(1) ?: 1) - 1
                val descriptor = OpenFileDescriptor(project, vf, l, c)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            } else {
                // TODO: notification
                println("File not found: $full")
            }
        }
    }
}
