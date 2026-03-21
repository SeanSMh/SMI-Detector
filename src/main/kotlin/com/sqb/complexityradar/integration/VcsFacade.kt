package com.sqb.complexityradar.integration

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

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

    /**
     * Counts the number of VCS commits for [file] within the last [days] days.
     * Uses IntelliJ's generic VCS API (works with Git, SVN, Hg, etc.).
     *
     * Silent degradation:
     * - VCS not initialized or no historyProvider → returns 0
     * - No history session for this file → returns 0
     * - Any exception → returns 0
     *
     * Note: history loading may be slow on large repos but this runs on a background thread.
     */
    fun commitCountFor(file: VirtualFile, days: Int = 90): Int = runCatching {
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
            ?: return@runCatching 0
        val historyProvider = vcs.vcsHistoryProvider
            ?: return@runCatching 0
        val filePath = VcsUtil.getFilePath(file)
        val session = historyProvider.createSessionFor(filePath)
            ?: return@runCatching 0
        val cutoff = System.currentTimeMillis() - days.toLong() * 86_400_000L
        session.revisionList.count { (it.revisionDate?.time ?: 0L) >= cutoff }
    }.getOrDefault(0)
}
