package com.sqb.complexityradar.ide.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.sqb.complexityradar.core.model.AnalyzeMode
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService
import java.awt.datatransfer.StringSelection

class OpenRadarToolWindowAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ComplexityRadarProjectService.getInstance(project).openRadarForCurrentFile()
    }
}

class CopyRefactorPromptAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val text = ComplexityRadarProjectService.getInstance(project).copyPromptForCurrentFile()
        if (text == null) {
            Messages.showInfoMessage(project, "No analysis result is available for the current file.", "Complexity Radar")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}

class RunExternalCommandAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = ComplexityRadarProjectService.getInstance(project)
        val result = service.currentResult()
        if (result == null) {
            Messages.showInfoMessage(project, "No analysis result is available for the current file.", "Complexity Radar")
            return
        }
        val template = service.uiSettings().externalCommand.trim()
        if (template.isBlank()) {
            Messages.showInfoMessage(project, "Configure an external command in Settings | Tools | Complexity Radar first.", "Complexity Radar")
            return
        }
        val promptPath = service.savePrompt(result)
        val rendered =
            template
                .replace("\${file}", result.filePath)
                .replace("\${score}", result.score.toString())
                .replace("\${prompt}", promptPath?.toString().orEmpty())
        val command =
            if (SystemInfo.isWindows) {
                GeneralCommandLine("cmd", "/c", rendered)
            } else {
                GeneralCommandLine("/bin/sh", "-lc", rendered)
            }
        OSProcessHandler(command).startNotify()
        Messages.showInfoMessage(project, "Started external command:\n$rendered", "Complexity Radar")
    }
}

class ToggleAnalysisModeAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ComplexityRadarProjectService.getInstance(project).reanalyzeCurrentFile(AnalyzeMode.ACCURATE)
    }
}
