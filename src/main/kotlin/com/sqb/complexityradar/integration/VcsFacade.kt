package com.sqb.complexityradar.integration

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

class VcsFacade(
    private val project: Project,
) {
    fun changedFiles(): List<VirtualFile> =
        runCatching {
            ChangeListManager
                .getInstance(project)
                .allChanges
                .mapNotNull { change -> change.afterRevision?.file?.virtualFile ?: change.beforeRevision?.file?.virtualFile }
                .distinctBy(VirtualFile::getUrl)
        }.getOrDefault(emptyList())
}
