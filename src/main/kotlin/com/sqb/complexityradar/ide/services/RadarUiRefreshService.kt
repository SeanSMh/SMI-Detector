package com.sqb.complexityradar.ide.services

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotifications

class RadarUiRefreshService(
    private val project: Project,
) {
    fun refresh(files: Collection<VirtualFile>) {
        if (files.isEmpty()) {
            return
        }
        val editorNotifications = EditorNotifications.getInstance(project)
        files.forEach { file ->
            editorNotifications.updateNotifications(file)
        }
        ProjectView.getInstance(project).refresh()
        DaemonCodeAnalyzer.getInstance(project).restart()
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Complexity Radar")
        toolWindow?.contentManager?.contents?.forEach { content ->
            (content.component as? RefreshableComplexityView)?.refreshView()
        }
    }

    fun refreshCurrentFile() {
        refresh(FileEditorManager.getInstance(project).selectedFiles.toList())
    }
}

interface RefreshableComplexityView {
    fun refreshView()

    fun revealFile(file: VirtualFile?) {
        refreshView()
    }
}
