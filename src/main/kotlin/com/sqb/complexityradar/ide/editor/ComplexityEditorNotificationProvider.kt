package com.sqb.complexityradar.ide.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService
import com.sqb.complexityradar.ide.ui.poopGlyphStrip
import com.sqb.complexityradar.ide.ui.poopScoreCount
import java.util.function.Function
import javax.swing.JComponent

class ComplexityEditorNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val service = ComplexityRadarProjectService.getInstance(project)
        val settings = service.uiSettings()
        if (!settings.showEditorBanner) {
            return null
        }
        val result = service.getResult(file) ?: return null
        if (poopScoreCount(result.score) <= 1) return null
        return Function {
            val panel = EditorNotificationPanel()
            val count = poopScoreCount(result.score)
            panel.text = "${poopGlyphStrip(result.score, showEmpty = false)} $count/5  Score ${result.score} · ${result.severity.label}"
            panel.createActionLabel("Open Radar") {
                service.openRadarForCurrentFile()
            }
            panel.createActionLabel("Copy Refactor Prompt") {
                val text = service.buildPrompt(result)
                val selection = java.awt.datatransfer.StringSelection(text)
                com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(selection)
            }
            panel
        }
    }
}
