package com.sqb.complexityradar.ide.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import com.sqb.complexityradar.adapters.LanguageAdapter
import com.sqb.complexityradar.adapters.java.JavaLanguageAdapter
import com.sqb.complexityradar.adapters.kotlin.KotlinLanguageAdapter
import com.sqb.complexityradar.core.model.AnalyzeMode
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.RadarConfig
import com.sqb.complexityradar.core.model.ScoreDigest
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.core.scoring.ComplexityScorer
import com.sqb.complexityradar.ide.cache.ComplexityResultStore
import com.sqb.complexityradar.integration.AiPromptService
import com.sqb.complexityradar.integration.ExportService
import com.sqb.complexityradar.integration.VcsFacade
import com.sqb.complexityradar.settings.ComplexityUiSettingsService
import com.sqb.complexityradar.settings.RadarConfigService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class ComplexityRadarProjectService(
    private val project: Project,
) : Disposable {
    private val started = AtomicBoolean(false)
    private val scorer = ComplexityScorer()
    private val configService = project.service<RadarConfigService>()
    private val uiSettingsService = project.service<ComplexityUiSettingsService>()
    private val store = ComplexityResultStore(project.basePath)
    private val vcsFacade = VcsFacade(project)
    private val exportService = ExportService(project.basePath)
    private val aiPromptService = AiPromptService(project.basePath)
    private val uiRefreshService = RadarUiRefreshService(project)
    private val adapters: List<LanguageAdapter> = listOf(KotlinLanguageAdapter(scorer), JavaLanguageAdapter(scorer))
    private val promotedRedFiles = ConcurrentHashMap.newKeySet<String>()
    private val queue = MergingUpdateQueue("ComplexityRadarAnalysis", 800, true, null, this)
    private val publishQueue = MergingUpdateQueue("ComplexityRadarPublish", 300, true, null, this)
    private val pendingPublishedFileUrls = ConcurrentHashMap.newKeySet<String>()
    private val vcsTriggeredVfsBatch = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        store.restoreFromDisk()
        registerVcsAwareListeners()
        registerFileEditorListener()
        analyzeProject()
    }

    fun analyzeProject() {
        queueProjectAnalysis()
    }

    fun queueProjectAnalysis(
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): ProjectQueueReport {
        val collection =
            ReadAction.compute<ProjectQueueCollection, RuntimeException> {
                val collected = linkedMapOf<String, VirtualFile>()
                var visitedCount = 0
                var supportedCount = 0
                var withinProjectCount = 0
                var fallbackUsed = false
                var currentFileFallbackUsed = false

                fun collect(file: VirtualFile) {
                    visitedCount += 1
                    if (!isSupportedCandidate(file)) {
                        return
                    }
                    supportedCount += 1
                    if (!isWithinProject(file)) {
                        return
                    }
                    withinProjectCount += 1
                    collected.putIfAbsent(file.url, file)
                }

                ProjectFileIndex.getInstance(project).iterateContent { file ->
                    collect(file)
                    true
                }

                if (collected.isEmpty()) {
                    fallbackUsed = true
                    project.basePath
                        ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                        ?.let { projectDir ->
                            VfsUtilCore.iterateChildrenRecursively(projectDir, null) { file ->
                                collect(file)
                                true
                            }
                        }
                }

                if (collected.isEmpty()) {
                    currentFileFallbackUsed = true
                    currentFile()?.let(::collect)
                }

                // Filter by config within the same ReadAction to batch all PSI/VFS reads
                val scheduled = linkedMapOf<String, Pair<VirtualFile, AnalyzeMode>>()
                var excludedByConfigCount = 0
                collected.values.forEach { file ->
                    val config = configService.getConfig(file)
                    if (config.isExcluded(file)) {
                        excludedByConfigCount += 1
                    } else {
                        scheduled.putIfAbsent(file.url, file to config.mode.default)
                    }
                }

                ProjectQueueCollection(
                    scheduled = scheduled,
                    excludedByConfigCount = excludedByConfigCount,
                    visitedCount = visitedCount,
                    supportedCount = supportedCount,
                    withinProjectCount = withinProjectCount,
                    fallbackUsed = fallbackUsed,
                    currentFileFallbackUsed = currentFileFallbackUsed,
                )
            }

        val scheduled = collection.scheduled

        val total = scheduled.size
        if (total == 0) {
            onProgress(0, 0)
            return ProjectQueueReport(
                queuedCount = 0,
                visitedCount = collection.visitedCount,
                supportedCount = collection.supportedCount,
                withinProjectCount = collection.withinProjectCount,
                excludedByConfigCount = collection.excludedByConfigCount,
                fallbackUsed = collection.fallbackUsed,
                currentFileFallbackUsed = collection.currentFileFallbackUsed,
                queuedFileUrls = emptyList(),
            )
        }
        scheduled.values.forEachIndexed { index, (file, mode) ->
            enqueueAnalysis(file, mode)
            val processed = index + 1
            if (processed == 1 || processed == total || processed % 10 == 0) {
                onProgress(processed, total)
            }
        }
        return ProjectQueueReport(
            queuedCount = total,
            visitedCount = collection.visitedCount,
            supportedCount = collection.supportedCount,
            withinProjectCount = collection.withinProjectCount,
            excludedByConfigCount = collection.excludedByConfigCount,
            fallbackUsed = collection.fallbackUsed,
            currentFileFallbackUsed = collection.currentFileFallbackUsed,
            queuedFileUrls = scheduled.keys.toList(),
        )
    }

    fun scheduleAnalysis(
        file: VirtualFile,
        mode: AnalyzeMode = preferredModeFor(file),
    ) {
        if (!shouldAnalyze(file)) {
            return
        }
        enqueueAnalysis(file, mode)
    }

    private fun enqueueAnalysis(
        file: VirtualFile,
        mode: AnalyzeMode,
    ) {
        val identity = "${file.url}:${mode.name}"
        queue.queue(
            object : Update(identity) {
                override fun run() {
                    analyzeInBackground(file, mode)
                }
            },
        )
    }

    fun getResult(file: VirtualFile): ComplexityResult? = store.get(file)

    fun getDigest(file: VirtualFile): ScoreDigest? = store.getDigest(file)

    fun allResults(): List<ComplexityResult> = visibleResults()

    fun changedResults(): List<ComplexityResult> = store.resultsFor(vcsFacade.changedFiles()).filter(::shouldDisplay)

    fun configFor(file: VirtualFile? = null): RadarConfig = configService.getConfig(file)

    fun groupedByModule(results: List<ComplexityResult> = allResults()): List<AggregateBucket> =
        aggregateBuckets(results) { file -> moduleBucketName(file) }

    fun groupedByPackage(results: List<ComplexityResult> = allResults()): List<AggregateBucket> =
        aggregateBuckets(results) { file -> packageBucketName(file) }

    fun reanalyzeCurrentFile(mode: AnalyzeMode = AnalyzeMode.ACCURATE) {
        currentFile()?.let { scheduleAnalysis(it, mode) }
    }

    fun scheduleFiles(
        files: Collection<VirtualFile>,
        mode: AnalyzeMode? = null,
    ) {
        files.forEach { file ->
            val resolvedMode = mode ?: preferredModeFor(file)
            scheduleAnalysis(file, resolvedMode)
        }
    }

    fun currentResult(): ComplexityResult? = currentFile()?.let(::getResult)

    fun buildPrompt(result: ComplexityResult): String = aiPromptService.buildPrompt(result)

    fun copyPromptForCurrentFile(): String? = currentResult()?.let(::buildPrompt)

    fun savePrompt(result: ComplexityResult) = aiPromptService.savePrompt(result)

    fun exportReports(outputDir: java.nio.file.Path? = null) =
        exportService.exportAll(allResults(), changedResults(), outputDir)

    fun uiSettings() = uiSettingsService.state

    fun openRadarForCurrentFile() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Complexity Radar") ?: return
        val file = currentFile()
        file
            ?.takeIf(::shouldAnalyze)
            ?.let { scheduleAnalysis(it, preferredModeFor(it, fromEditor = true)) }
        toolWindow.show {
            toolWindow.contentManager.contents.forEach { content ->
                (content.component as? RefreshableComplexityView)?.revealFile(file)
            }
        }
    }

    fun reloadConfig() {
        configService.reload()
        promotedRedFiles.clear()
        store.clearAll()
        refreshRadarViews()
        analyzeProject()
        uiRefreshService.refreshCurrentFile()
    }

    override fun dispose() {
        uiRefreshService.dispose()
        store.dispose()
    }

    private fun refreshRadarViews() {
        if (project.isDisposed) {
            return
        }
        ToolWindowManager.getInstance(project).getToolWindow("Complexity Radar")
            ?.contentManager
            ?.contents
            ?.forEach { content ->
                (content.component as? RefreshableComplexityView)?.refreshView()
            }
    }

    private fun registerFileEditorListener() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile ?: return
                    if (shouldAnalyze(file)) {
                        scheduleAnalysis(file, preferredModeFor(file, fromEditor = true))
                    }
                }
            },
        )
    }

    private fun registerVcsAwareListeners() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(events: List<VFileEvent>) {
                    if (ProjectLevelVcsManager.getInstance(project).isBackgroundVcsOperationRunning) {
                        vcsTriggeredVfsBatch.set(true)
                    }
                }

                override fun after(events: List<VFileEvent>) {
                    val isVcsBatch =
                        vcsTriggeredVfsBatch.getAndSet(false) ||
                            ProjectLevelVcsManager.getInstance(project).isBackgroundVcsOperationRunning
                    if (!isVcsBatch) {
                        return
                    }
                    var configChanged = false
                    val changedFiles = linkedSetOf<VirtualFile>()
                    events.forEach { event ->
                        val file = event.file ?: return@forEach
                        if (file.name == "radar.yaml") {
                            configChanged = true
                            return@forEach
                        }
                        val hadCachedState = store.get(file) != null || store.getDigest(file) != null
                        val canAnalyzeNow = shouldAnalyze(file)
                        if (hadCachedState) {
                            store.invalidate(file)
                        }
                        if (canAnalyzeNow) {
                            changedFiles += file
                        }
                    }
                    if (configChanged) {
                        reloadConfig()
                        return
                    }
                    if (changedFiles.isNotEmpty()) {
                        scheduleFiles(changedFiles)
                    }
                }
            },
        )
    }

    private fun analyzeInBackground(
        file: VirtualFile,
        mode: AnalyzeMode,
    ) {
        ReadAction
            .nonBlocking<AnalysisAttempt> {
                computeResult(file, mode)
            }.expireWith(this)
            .finishOnUiThread(ModalityState.any()) { attempt ->
                when (attempt) {
                    is AnalysisAttempt.Success -> {
                        val result = attempt.result
                        val virtualFile =
                            VirtualFileManager.getInstance().findFileByUrl(result.fileUrl)
                                ?: run {
                                    store.invalidateByUrl(result.fileUrl)
                                    queueResultPublication(result.fileUrl)
                                    return@finishOnUiThread
                                }
                        store.put(virtualFile, result, scorer.digest(result))
                        queueResultPublication(result.fileUrl)
                        maybePromoteAccurate(virtualFile, result)
                    }

                    is AnalysisAttempt.FileGone,
                    is AnalysisAttempt.Unsupported,
                    -> {
                        store.invalidateByUrl(attempt.fileUrl)
                        queueResultPublication(attempt.fileUrl)
                    }

                    is AnalysisAttempt.Cancelled,
                    is AnalysisAttempt.TransientFailure,
                    -> {
                        queueResultPublication(attempt.fileUrl)
                    }
                }
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun queueResultPublication(fileUrl: String) {
        pendingPublishedFileUrls += fileUrl
        publishQueue.queue(
            object : Update("publish-results") {
                override fun run() {
                    flushResultPublication()
                }
            },
        )
    }

    private fun flushResultPublication() {
        val fileUrls = pendingPublishedFileUrls.toList()
        if (fileUrls.isEmpty()) {
            return
        }
        pendingPublishedFileUrls.removeAll(fileUrls.toSet())
        val files =
            fileUrls
                .mapNotNull { url -> VirtualFileManager.getInstance().findFileByUrl(url) }
                .distinctBy { it.url }
        project.messageBus.syncPublisher(ComplexityResultListener.TOPIC).resultsUpdated(
            ResultUpdateBatch(
                fileUrls = fileUrls,
            ),
        )
        if (files.isNotEmpty()) {
            uiRefreshService.refresh(files)
        }
    }

    private fun computeResult(
        file: VirtualFile,
        mode: AnalyzeMode,
    ): AnalysisAttempt {
        val config = configService.getConfig(file)
        if (config.isExcluded(file)) {
            return AnalysisAttempt.Unsupported(file.url)
        }
        if (!file.isValid) {
            return AnalysisAttempt.FileGone(file.url)
        }
        val psiFile =
            psiFile(file)
                ?: return if (file.isValid) {
                    AnalysisAttempt.TransientFailure(file.url)
                } else {
                    AnalysisAttempt.FileGone(file.url)
                }
        val adapter = adapters.firstOrNull { it.supports(psiFile) } ?: return AnalysisAttempt.Unsupported(file.url)
        return try {
            val (summary, hotspots) = adapter.analyze(psiFile, mode, config)
            AnalysisAttempt.Success(
                scorer.score(
                    summary = summary,
                    fileUrl = file.url,
                    filePath = file.path,
                    mode = mode,
                    config = config,
                    hotspots = hotspots,
                    churnNormalized = 0.0,
                ),
            )
        } catch (_: ProcessCanceledException) {
            AnalysisAttempt.Cancelled(file.url)
        } catch (_: Throwable) {
            AnalysisAttempt.TransientFailure(file.url)
        }
    }

    private fun maybePromoteAccurate(
        file: VirtualFile,
        result: ComplexityResult,
    ) {
        val config = configService.getConfig(file)
        if (result.mode != AnalyzeMode.FAST) {
            return
        }
        if (result.severity != Severity.RED) {
            return
        }
        if (config.mode.accurateOnTopRedFiles <= 0) {
            return
        }
        if (promotedRedFiles.size >= config.mode.accurateOnTopRedFiles) {
            return
        }
        if (promotedRedFiles.add(file.url)) {
            scheduleAnalysis(file, AnalyzeMode.ACCURATE)
        }
    }

    private fun preferredModeFor(
        file: VirtualFile,
        fromEditor: Boolean = false,
    ): AnalyzeMode {
        val config = configService.getConfig(file)
        return if (fromEditor && config.mode.accurateOnOpenFile) {
            AnalyzeMode.ACCURATE
        } else {
            config.mode.default
        }
    }

    private fun visibleResults(): List<ComplexityResult> = store.allResults().filter(::shouldDisplay)

    private fun shouldDisplay(result: ComplexityResult): Boolean {
        val file = VirtualFileManager.getInstance().findFileByUrl(result.fileUrl) ?: return false
        return shouldAnalyze(file)
    }

    private fun aggregateBuckets(
        results: List<ComplexityResult>,
        bucketName: (VirtualFile) -> String,
    ): List<AggregateBucket> {
        val byGroup =
            results
                .mapNotNull { result ->
                    val file = VirtualFileManager.getInstance().findFileByUrl(result.fileUrl) ?: return@mapNotNull null
                    if (!shouldAnalyze(file)) {
                        return@mapNotNull null
                    }
                    bucketName(file) to result
                }.groupBy({ it.first }, { it.second })
        return byGroup.entries
            .map { (group, bucketResults) ->
                val average = bucketResults.map { it.score }.average()
                AggregateBucket(
                    name = group,
                    averageScore = average,
                    maxScore = bucketResults.maxOf { it.score },
                    redCount = bucketResults.count { it.severity == Severity.RED },
                    fileCount = bucketResults.size,
                    topResults = bucketResults.sortedByDescending { it.score }.take(5),
                )
            }.sortedByDescending { it.maxScore }
    }

    private fun moduleBucketName(file: VirtualFile): String {
        val fileIndex = ProjectFileIndex.getInstance(project)
        return fileIndex.getModuleForFile(file)?.name
            ?: fileIndex.getContentRootForFile(file)?.name
            ?: "(No Module)"
    }

    private fun packageBucketName(file: VirtualFile): String {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val sourceRoot = fileIndex.getSourceRootForFile(file) ?: return "(No Package)"
        val parent = file.parent ?: return "(default package)"
        if (parent == sourceRoot) {
            return "(default package)"
        }
        val relativePath = VfsUtilCore.getRelativePath(parent, sourceRoot, '/')
        return relativePath
            ?.replace('/', '.')
            ?.takeIf { it.isNotBlank() }
            ?: "(default package)"
    }

    private fun shouldAnalyze(file: VirtualFile): Boolean =
        ReadAction.compute<Boolean, RuntimeException> { shouldAnalyzeInRead(file) }

    private fun shouldAnalyzeInRead(file: VirtualFile): Boolean =
        !file.isDirectory &&
            file.isValid &&
            isSupportedSourceFile(file) &&
            isWithinProject(file) &&
            !configService.getConfig(file).isExcluded(file)

    private fun isSupportedCandidate(file: VirtualFile): Boolean =
        !file.isDirectory &&
            file.isValid &&
            isSupportedSourceFile(file)

    private fun isWithinProject(file: VirtualFile): Boolean {
        val fileIndex = ProjectFileIndex.getInstance(project)
        return fileIndex.isInContent(file) && !fileIndex.isExcluded(file)
    }

    private fun isSupportedSourceFile(file: VirtualFile): Boolean =
        file.extension.equals("kt", ignoreCase = true) || file.extension.equals("java", ignoreCase = true)

    private fun psiFile(file: VirtualFile): PsiFile? = PsiManager.getInstance(project).findFile(file)

    private fun currentFile(): VirtualFile? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    companion object {
        fun getInstance(project: Project): ComplexityRadarProjectService = project.service()
    }
}

data class AggregateBucket(
    val name: String,
    val averageScore: Double,
    val maxScore: Int,
    val redCount: Int,
    val fileCount: Int,
    val topResults: List<ComplexityResult>,
)

data class ProjectQueueReport(
    val queuedCount: Int,
    val visitedCount: Int,
    val supportedCount: Int,
    val withinProjectCount: Int,
    val excludedByConfigCount: Int,
    val fallbackUsed: Boolean,
    val currentFileFallbackUsed: Boolean,
    val queuedFileUrls: List<String>,
)

private sealed class AnalysisAttempt(open val fileUrl: String) {
    data class Success(
        val result: ComplexityResult,
    ) : AnalysisAttempt(result.fileUrl)

    data class FileGone(
        override val fileUrl: String,
    ) : AnalysisAttempt(fileUrl)

    data class Unsupported(
        override val fileUrl: String,
    ) : AnalysisAttempt(fileUrl)

    data class Cancelled(
        override val fileUrl: String,
    ) : AnalysisAttempt(fileUrl)

    data class TransientFailure(
        override val fileUrl: String,
    ) : AnalysisAttempt(fileUrl)
}

private data class ProjectQueueCollection(
    val scheduled: LinkedHashMap<String, Pair<VirtualFile, AnalyzeMode>>,
    val excludedByConfigCount: Int,
    val visitedCount: Int,
    val supportedCount: Int,
    val withinProjectCount: Int,
    val fallbackUsed: Boolean,
    val currentFileFallbackUsed: Boolean,
)
