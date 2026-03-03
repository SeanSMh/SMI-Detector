package com.sqb.complexityradar.ide.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
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
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
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

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        store.restoreFromDisk()
        registerListeners()
        analyzeOpenFiles()
        analyzeChangedFiles()
        analyzeProject()
    }

    fun analyzeProject() {
        queueProjectAnalysis()
    }

    fun queueProjectAnalysis(
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val scheduled = mutableListOf<Pair<VirtualFile, AnalyzeMode>>()
        fileIndex.iterateContent { file ->
            if (shouldAnalyze(file) && fileIndex.isInSource(file)) {
                scheduled += file to configService.getConfig(file).mode.default
            }
            true
        }
        val total = scheduled.size
        if (total == 0) {
            onProgress(0, 0)
            return 0
        }
        scheduled.forEachIndexed { index, (file, mode) ->
            scheduleAnalysis(file, mode)
            onProgress(index + 1, total)
        }
        return total
    }

    fun analyzeOpenFiles() {
        FileEditorManager.getInstance(project).openFiles.forEach { file ->
            if (shouldAnalyze(file)) {
                scheduleAnalysis(file, preferredModeFor(file, fromEditor = true))
            }
        }
    }

    fun analyzeChangedFiles() {
        vcsFacade.changedFiles().forEach { file ->
            if (shouldAnalyze(file)) {
                scheduleAnalysis(file, AnalyzeMode.FAST)
            }
        }
    }

    fun scheduleAnalysis(
        file: VirtualFile,
        mode: AnalyzeMode = preferredModeFor(file),
    ) {
        if (!shouldAnalyze(file)) {
            return
        }
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

    fun currentResult(): ComplexityResult? = currentFile()?.let(::getResult)

    fun buildPrompt(result: ComplexityResult): String = aiPromptService.buildPrompt(result)

    fun copyPromptForCurrentFile(): String? = currentResult()?.let(::buildPrompt)

    fun savePrompt(result: ComplexityResult) = aiPromptService.savePrompt(result)

    fun savePromptForCurrentFile() = currentResult()?.let(::savePrompt)

    fun exportReports() = exportService.exportAll(allResults(), changedResults())

    fun uiSettings() = uiSettingsService.state

    fun openRadarForCurrentFile() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Complexity Radar") ?: return
        val file = currentFile()
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
        analyzeOpenFiles()
        analyzeChangedFiles()
        analyzeProject()
        uiRefreshService.refreshCurrentFile()
    }

    fun fullRescanProject() {
        promotedRedFiles.clear()
        store.clearAll()
        analyzeOpenFiles()
        analyzeChangedFiles()
        analyzeProject()
        uiRefreshService.refreshCurrentFile()
    }

    override fun dispose() = Unit

    private fun registerListeners() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    var configChanged = false
                    val changedFiles = mutableSetOf<VirtualFile>()
                    events.forEach { event ->
                        val file = event.file ?: return@forEach
                        if (file.name == "radar.yaml") {
                            configChanged = true
                        } else if (shouldAnalyze(file)) {
                            changedFiles += file
                            store.invalidate(file)
                        }
                    }
                    if (configChanged) {
                        reloadConfig()
                        return
                    }
                    changedFiles.forEach { file -> scheduleAnalysis(file, preferredModeFor(file)) }
                }
            },
        )
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    event.newFile?.takeIf(::shouldAnalyze)?.let { scheduleAnalysis(it, preferredModeFor(it, fromEditor = true)) }
                }
            },
        )
        PsiManager.getInstance(project).addPsiTreeChangeListener(
            object : PsiTreeChangeAdapter() {
                override fun childrenChanged(event: PsiTreeChangeEvent) {
                    event.file?.virtualFile?.takeIf(::shouldAnalyze)?.let { file ->
                        store.invalidate(file)
                        scheduleAnalysis(file, preferredModeFor(file))
                    }
                }
            },
            this,
        )
    }

    private fun analyzeInBackground(
        file: VirtualFile,
        mode: AnalyzeMode,
    ) {
        ReadAction
            .nonBlocking<ComplexityResult?> {
                computeResult(file, mode)
            }.expireWith(this)
            .finishOnUiThread(ModalityState.any()) { result ->
                if (result == null) {
                    return@finishOnUiThread
                }
                val virtualFile = VirtualFileManager.getInstance().findFileByUrl(result.fileUrl) ?: return@finishOnUiThread
                store.put(virtualFile, result, scorer.digest(result))
                project.messageBus.syncPublisher(ComplexityResultListener.TOPIC).resultsUpdated(listOf(virtualFile))
                uiRefreshService.refresh(listOf(virtualFile))
                maybePromoteAccurate(virtualFile, result)
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun computeResult(
        file: VirtualFile,
        mode: AnalyzeMode,
    ): ComplexityResult? {
        val config = configService.getConfig(file)
        if (config.isExcluded(file)) {
            return null
        }
        val psiFile = psiFile(file) ?: return null
        val adapter = adapters.firstOrNull { it.supports(psiFile) } ?: return null
        return try {
            val summary = adapter.summarize(psiFile, mode, config)
            val hotspots = adapter.hotspots(psiFile, mode, config)
            scorer.score(
                summary = summary,
                fileUrl = file.url,
                filePath = file.path,
                mode = mode,
                config = config,
                hotspots = hotspots,
                churnNormalized = 0.0,
            )
        } catch (_: ProcessCanceledException) {
            null
        } catch (_: Throwable) {
            null
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
        !file.isDirectory &&
            file.isValid &&
            isSupportedSourceFile(file) &&
            isProjectContent(file) &&
            !configService.getConfig(file).isExcluded(file)

    private fun isProjectContent(file: VirtualFile): Boolean {
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
