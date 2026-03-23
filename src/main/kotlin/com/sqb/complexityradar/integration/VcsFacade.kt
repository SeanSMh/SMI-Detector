package com.sqb.complexityradar.integration

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import java.util.concurrent.ConcurrentHashMap

class VcsFacade(
    private val project: Project,
) {
    // 缓存 churn 值，避免对同一文件重复执行 git log（TTL 5 分钟）
    private data class CachedChurn(val count: Int, val expiresAt: Long)
    private val churnCache = ConcurrentHashMap<String, CachedChurn>()
    private val cacheTtlMs = 5 * 60 * 1000L
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
     * Results are cached for [cacheTtlMs] ms to avoid repeated git log calls
     * when the same file is re-analyzed (edits, config reload, etc.).
     *
     * Silent degradation:
     * - VCS not initialized or no historyProvider → returns 0
     * - No history session for this file → returns 0
     * - Any exception → returns 0
     */
    fun commitCountFor(file: VirtualFile, days: Int = 90): Int {
        val now = System.currentTimeMillis()
        churnCache[file.url]?.let { cached ->
            if (now < cached.expiresAt) return cached.count
        }
        val count = runCatching {
            val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
                ?: return@runCatching 0
            val historyProvider = vcs.vcsHistoryProvider
                ?: return@runCatching 0
            val filePath = VcsUtil.getFilePath(file)
            val session = historyProvider.createSessionFor(filePath)
                ?: return@runCatching 0
            val cutoff = now - days.toLong() * 86_400_000L
            session.revisionList.count { it.revisionDate != null && it.revisionDate.time >= cutoff }
        }.getOrDefault(0)
        churnCache[file.url] = CachedChurn(count, now + cacheTtlMs)
        return count
    }
}
