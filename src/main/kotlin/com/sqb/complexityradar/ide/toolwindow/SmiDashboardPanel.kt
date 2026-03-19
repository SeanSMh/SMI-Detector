package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService
import com.sqb.complexityradar.ide.services.ComplexityResultListener
import com.sqb.complexityradar.ide.services.RefreshableComplexityView
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.Timer
import kotlin.math.min
import kotlin.math.roundToInt

internal class SmiDashboardPanel(
    private val project: Project,
) : JPanel(BorderLayout()), RefreshableComplexityView, Disposable {

    private val service = ComplexityRadarProjectService.getInstance(project)
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    private val headerPanel = SmiHeaderPanel(
        project = project,
        onExport = ::runExport,
        onToggleGutter = ::onToggleGutter,
        gutterLabelProvider = ::gutterButtonLabel,
    )
    private val toolbarPanel = SmiToolbarPanel(onScanClicked = ::runScanProject)
    private val navPanel     = SmiNavPanel(onTabChange = ::onTabChanged)
    private val overviewPanel = SmiOverviewPanel(onScopeChange = ::onScopeChanged)
    private val issuesPanel  = SmiIssuesPanel(project = project)
    private val footerPanel  = SmiFooterPanel()
    private val loadingOverlay = LoadingOverlayPanel()

    private var currentTab   = DashboardTab.OVERVIEW
    private var currentScope = DashboardScope.PROJECT
    private val refreshVersion = AtomicInteger(0)
    private val refreshDebounceTimer = Timer(300) { refreshViewNow() }.apply { isRepeats = false }

    private var hasLoadedOnce = false
    private var isViewRefreshing = false
    private var isAnalyzeRunning = false
    private var preferredFileUrl: String? = null
    private var lastStatusMessage: String? = null
    private val pendingUrls = linkedSetOf<String>()
    private var analysisTargetCount = 0
    private var analysisCompletedCount = 0

    private lateinit var contentCards: JPanel
    private lateinit var contentCardLayout: CardLayout

    init {
        preferredSize = Dimension(980, 720)

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerPanel)
            add(toolbarPanel)
            add(navPanel)
        }, BorderLayout.NORTH)

        contentCardLayout = CardLayout()
        contentCards = JPanel(contentCardLayout).apply {
            isOpaque = false
            add(overviewPanel, DashboardTab.OVERVIEW.name)
            add(issuesPanel,   DashboardTab.ISSUES.name)
        }

        add(JPanel().apply {
            layout = OverlayLayout(this)
            add(contentCards.apply { alignmentX = 0.5f; alignmentY = 0.5f })
            add(loadingOverlay.apply { alignmentX = 0.5f; alignmentY = 0.5f })
        }, BorderLayout.CENTER)

        add(footerPanel, BorderLayout.SOUTH)

        // Set plugin version in footer
        runCatching {
            val pluginId = com.intellij.openapi.extensions.PluginId.getId("com.sqb.complexityradar")
            val version = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)?.version ?: ""
            footerPanel.setVersion(version)
        }

        showContentCard()
        refreshViewNow()

        connection.subscribe(ComplexityResultListener.TOPIC, ComplexityResultListener { batch ->
            onResultsUpdated(batch.fileUrls)
            scheduleRefreshView()
        })

        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    scheduleRefreshView()
                }
            },
        )
    }

    // ── Disposable ─────────────────────────────────────────────────────────────

    override fun dispose() {
        refreshDebounceTimer.stop()
        connection.disconnect()
    }

    // ── RefreshableComplexityView ──────────────────────────────────────────────

    override fun refreshView() { scheduleRefreshView() }

    override fun revealFile(file: VirtualFile?) {
        preferredFileUrl = file?.url
        navPanel.selectTab(DashboardTab.OVERVIEW)
        currentTab   = DashboardTab.OVERVIEW
        currentScope = DashboardScope.CURRENT_FILE
        overviewPanel.selectScope(DashboardScope.CURRENT_FILE)
        showContentCard()
        if (file != null) service.getResult(file)?.let { hasLoadedOnce = true }
        refreshViewNow()
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun onTabChanged(tab: DashboardTab) {
        currentTab = tab
        showContentCard()
    }

    private fun onScopeChanged(scope: DashboardScope) {
        currentScope = scope
        scheduleRefreshView()
    }

    private fun showContentCard() {
        contentCardLayout.show(contentCards, currentTab.name)
    }

    // ── Refresh ────────────────────────────────────────────────────────────────

    private fun scheduleRefreshView() {
        if (project.isDisposed) return
        if (ApplicationManager.getApplication().isDispatchThread) {
            refreshDebounceTimer.restart()
        } else {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) refreshDebounceTimer.restart()
            }, project.disposed)
        }
    }

    private fun refreshViewNow() {
        val refreshId = refreshVersion.incrementAndGet()
        val targetFileUrl = preferredFileUrl ?: currentEditorFileUrl()
        isViewRefreshing = true
        if (!hasLoadedOnce) loadingOverlay.showIndeterminate("Initializing SMI Detector...")
        updateLoadingUi()
        AppExecutorUtil.getAppExecutorService().execute {
            val snapshot = runCatching { buildSnapshot(targetFileUrl) }.getOrElse {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || refreshId != refreshVersion.get()) return@invokeLater
                    isViewRefreshing = false
                    updateLoadingUi()
                }
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || refreshId != refreshVersion.get()) return@invokeLater
                applySnapshot(snapshot)
            }
        }
    }

    private fun buildSnapshot(targetFileUrl: String?): FocusedViewSnapshot {
        val allResults = service.allResults()
        val targetFile = targetFileUrl?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
        val currentResult =
            targetFileUrl?.let { target -> allResults.firstOrNull { it.fileUrl == target } }
                ?: allResults.firstOrNull { it.fileUrl == currentEditorFileUrl() }
        val sortedResults = allResults.sortedWith(
            compareByDescending<ComplexityResult> { it.priority }
                .thenByDescending { it.score }
                .thenBy { it.filePath },
        )
        val averageScore = allResults.map { it.score }.average().takeIf { !it.isNaN() } ?: 0.0
        val aggregateValues = FactorType.entries.associateWith { factor ->
            if (allResults.isEmpty()) 0.0
            else allResults.map { r -> r.contributions.firstOrNull { it.type == factor }?.normalized ?: 0.0 }.average()
        }
        val strongestFactors = aggregateValues.entries.sortedByDescending { it.value }.take(2).map { it.key.displayName }
        val projectSummary = if (allResults.isEmpty()) "" else buildString {
            append("Top factors: ")
            append(strongestFactors.joinToString(" + ").ifBlank { "balanced" })
        }
        return FocusedViewSnapshot(
            currentResult = currentResult,
            projectResults = allResults,
            topFiles = sortedResults.take(5),
            averageScore = averageScore,
            redCount = allResults.count { it.severity == Severity.RED },
            aggregateValues = aggregateValues,
            aggregateSeverity = service.configFor().severityFor(averageScore.roundToInt()),
            projectSummary = projectSummary,
            targetFileUrl = targetFileUrl,
            targetFilePath = targetFile?.path,
        )
    }

    private fun applySnapshot(snapshot: FocusedViewSnapshot) {
        navPanel.updateBadge(snapshot.redCount)
        overviewPanel.update(snapshot, currentScope)
        issuesPanel.update(snapshot.projectResults, currentScope, snapshot.currentResult)
        hasLoadedOnce = true
        isViewRefreshing = false
        if (preferredFileUrl != null && snapshot.currentResult?.fileUrl == preferredFileUrl) {
            preferredFileUrl = null
        }
        updateLoadingUi()
        updateFooterStatus(snapshot.projectResults.size)
    }

    // ── Scan ───────────────────────────────────────────────────────────────────

    private fun runScanProject() {
        if (isAnalyzeRunning) return
        isAnalyzeRunning = true
        pendingUrls.clear()
        analysisTargetCount = 0
        analysisCompletedCount = 0
        loadingOverlay.showProgress("Queueing project scan...", 0, 0)
        updateLoadingUi()
        footerPanel.setScanning("Scanning...")
        AppExecutorUtil.getAppExecutorService().execute {
            val reportResult = runCatching {
                service.queueProjectAnalysis { processed, total ->
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || !isAnalyzeRunning) return@invokeLater
                        loadingOverlay.showProgress("Queueing project scan...", processed, total)
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                reportResult.fold(
                    onSuccess = { report ->
                        if (report.queuedCount <= 0) {
                            lastStatusMessage = "No analyzable files queued."
                            pendingUrls.clear()
                            loadingOverlay.showIndeterminate("No analyzable files queued.")
                            footerPanel.setIdle("No analyzable files queued.")
                        } else {
                            pendingUrls.clear()
                            pendingUrls.addAll(report.queuedFileUrls)
                            analysisTargetCount = report.queuedCount
                            analysisCompletedCount = 0
                            lastStatusMessage = "Queued ${report.queuedCount} files. Running analysis..."
                            loadingOverlay.showProgress("Analyzing ${report.queuedCount} files...", 0, report.queuedCount)
                        }
                    },
                    onFailure = { t ->
                        lastStatusMessage = "Project scan failed."
                        loadingOverlay.showIndeterminate("Project scan failed.")
                        footerPanel.setIdle("Scan failed: ${t.message}")
                        pendingUrls.clear()
                    },
                )
                isAnalyzeRunning = false
                updateLoadingUi()
            }
        }
    }

    private fun onResultsUpdated(fileUrls: Collection<String>) {
        if (pendingUrls.isEmpty()) return
        var changed = false
        fileUrls.forEach { url ->
            if (pendingUrls.remove(url)) { analysisCompletedCount += 1; changed = true }
        }
        if (!changed) return
        if (pendingUrls.isEmpty()) {
            lastStatusMessage = "Analysis complete — $analysisTargetCount files"
            footerPanel.setIdle("Analysis Complete ($analysisTargetCount files)")
            loadingOverlay.isVisible = false
        } else {
            loadingOverlay.showProgress(
                "Analyzing... $analysisCompletedCount / $analysisTargetCount",
                analysisCompletedCount, analysisTargetCount,
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun runExport() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = "Export Report To..."
            description = "Choose folder to save complexity-radar-report"
        }
        val defaultVf = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, project, defaultVf) ?: return
        val outDir = java.nio.file.Path.of(chosen.path).resolve("complexity-radar-report")
        val path = service.exportReports(outDir)
        if (path == null) Messages.showErrorDialog(project, "Failed to export report.", "SMI Detector")
        else Messages.showInfoMessage(project, "Report exported to:\n$path", "SMI Detector")
    }

    private fun gutterButtonLabel(): String =
        if (service.uiSettings().showGutterIcons) "Hide Gutter Icons" else "Show Gutter Icons"

    private fun onToggleGutter() {
        val uiSettings = project.getService(com.sqb.complexityradar.settings.ComplexityUiSettingsService::class.java)
        uiSettings.update { it.showGutterIcons = !it.showGutterIcons }
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun updateLoadingUi() {
        val visible = when {
            isAnalyzeRunning -> true
            !hasLoadedOnce && isViewRefreshing -> {
                loadingOverlay.showIndeterminate("Initializing SMI Detector...")
                true
            }
            else -> false
        }
        loadingOverlay.isVisible = visible
        toolbarPanel.setScanRunning(isAnalyzeRunning)
    }

    private fun updateFooterStatus(fileCount: Int) {
        val msg = lastStatusMessage
        if (msg != null) footerPanel.setIdle(msg)
        else if (fileCount > 0) footerPanel.setIdle("Analysis Complete ($fileCount files)")
        else footerPanel.setIdle("Ready")
    }

    private fun currentEditorFileUrl(): String? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.url
}
