package com.sqb.complexityradar.ide.services

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.util.concurrent.ConcurrentHashMap

class RadarUiRefreshService(
    private val project: Project,
) : Disposable {
    private val pendingRefreshFileUrls = ConcurrentHashMap.newKeySet<String>()
    private val refreshQueue = MergingUpdateQueue("ComplexityRadarUiRefresh", 300, true, null, this)

    fun refresh(files: Collection<VirtualFile>) {
        if (files.isEmpty()) {
            return
        }
        files.forEach { file -> pendingRefreshFileUrls += file.url }
        refreshQueue.queue(
            object : Update("refresh-ui") {
                override fun run() {
                    flushRefresh()
                }
            },
        )
    }

    fun refreshCurrentFile() {
        refresh(FileEditorManager.getInstance(project).selectedFiles.toList())
    }

    private fun flushRefresh() {
        val fileUrls = pendingRefreshFileUrls.toList()
        if (fileUrls.isEmpty()) {
            return
        }
        pendingRefreshFileUrls.removeAll(fileUrls.toSet())
        val files =
            fileUrls
                .mapNotNull { url -> com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(url) }
                .distinctBy { it.url }
        if (files.isEmpty()) {
            return
        }
        val editorNotifications = EditorNotifications.getInstance(project)
        files.forEach { file ->
            editorNotifications.updateNotifications(file)
        }
        ProjectView.getInstance(project).refresh()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    override fun dispose() = Unit
}

interface RefreshableComplexityView {
    fun refreshView()

    fun revealFile(file: VirtualFile?) {
        refreshView()
    }
}
