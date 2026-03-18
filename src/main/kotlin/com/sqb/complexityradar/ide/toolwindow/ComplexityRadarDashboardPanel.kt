package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService
import com.sqb.complexityradar.ide.services.ComplexityResultListener
import com.sqb.complexityradar.ide.services.RefreshableComplexityView
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.Timer
import kotlin.math.min
import kotlin.math.roundToInt

internal class ComplexityRadarDashboardPanel(
    private val project: Project,
) : JPanel(BorderLayout()), RefreshableComplexityView {

    private val service = ComplexityRadarProjectService.getInstance(project)
    private val connection: MessageBusConnection = project.messageBus.connect(project)

    // Sub-panels
    private val headerPanel = RadarHeaderPanel(
        project = project,
        onExport = ::runExport,
        onToggleGutter = ::onToggleGutter,
        gutterLabelProvider = ::gutterButtonLabel,
    )
    private val toolbarPanel = RadarToolbarPanel(
        onScanProject = ::runScanProject,
        onRefresh = { service.reanalyzeCurrentFile() },
    )
    private val navPanel = RadarNavPanel(
        onTabChange = ::onTabChanged,
        onScopeChange = ::onScopeChanged,
    )
    private val overviewPanel = RadarOverviewPanel(
        project = project,
        onFileOpen = ::openResult,
        onFileSelect = { result ->
            preferredFileUrl = result.fileUrl
            navPanel.selectTab(DashboardTab.OVERVIEW)
            navPanel.selectScope(DashboardScope.CURRENT_FILE)
            currentScope = DashboardScope.CURRENT_FILE
            currentTab = DashboardTab.OVERVIEW
            showContentCard()
            refreshView()
        },
    )
    private val issuesPanel = RadarIssuesPanel(project = project)
    private val footerPanel = RadarFooterPanel()
    private val loadingOverlay = LoadingOverlayPanel()

    // State
    private var currentTab = DashboardTab.OVERVIEW
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

    init {
        preferredSize = Dimension(980, 720)

        // North: header + toolbar + nav
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerPanel)
            add(toolbarPanel)
            add(navPanel)
        }, BorderLayout.NORTH)

        // Center: content cards over loading overlay
        add(JPanel().apply {
            layout = OverlayLayout(this)
            add(buildContentPanel().apply { alignmentX = 0.5f; alignmentY = 0.5f })
            add(loadingOverlay.apply { alignmentX = 0.5f; alignmentY = 0.5f })
        }, BorderLayout.CENTER)

        // South: footer
        add(footerPanel, BorderLayout.SOUTH)

        showContentCard()
        refreshViewNow()

        connection.subscribe(ComplexityResultListener.TOPIC, ComplexityResultListener { batch ->
            onResultsUpdated(batch.fileUrls)
            scheduleRefreshView()
        })
    }

    // ── RefreshableComplexityView ──────────────────────────────────────────────

    override fun refreshView() {
        scheduleRefreshView()
    }

    override fun revealFile(file: VirtualFile?) {
        preferredFileUrl = file?.url
        navPanel.selectTab(DashboardTab.OVERVIEW)
        navPanel.selectScope(DashboardScope.CURRENT_FILE)
        currentTab = DashboardTab.OVERVIEW
        currentScope = DashboardScope.CURRENT_FILE
        showContentCard()
        when {
            file == null -> {
                val snap = FocusedViewSnapshot(
                    currentResult = null, projectResults = emptyList(), topFiles = emptyList(),
                    averageScore = 0.0, redCount = 0, aggregateValues = emptyMap(),
                    aggregateSeverity = Severity.GREEN, projectSummary = "",
                    targetFileUrl = null, targetFilePath = null,
                )
                overviewPanel.update(snap)
            }
            else -> {
                val immediateResult = service.getResult(file)
                if (immediateResult != null) {
                    hasLoadedOnce = true
                }
            }
        }
        refreshViewNow()
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun onTabChanged(tab: DashboardTab) {
        currentTab = tab
        showContentCard()
    }

    private fun onScopeChanged(scope: DashboardScope) {
        currentScope = scope
        overviewPanel.showScope(scope)
        issuesPanel.showScope(scope)
    }

    private fun showContentCard() {
        overviewPanel.showScope(currentScope)
        issuesPanel.showScope(currentScope)
        (contentCardLayout as CardLayout).show(contentCards, currentTab.name)
    }

    // Content card container (assigned in buildContentPanel)
    private lateinit var contentCards: JPanel
    private lateinit var contentCardLayout: CardLayout

    private fun buildContentPanel(): JPanel {
        contentCardLayout = CardLayout()
        contentCards = JPanel(contentCardLayout).apply {
            isOpaque = false
            add(overviewPanel, DashboardTab.OVERVIEW.name)
            add(issuesPanel, DashboardTab.ISSUES.name)
        }
        return contentCards
    }

    // ── Refresh logic (unchanged from original) ───────────────────────────────

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
        if (!hasLoadedOnce) loadingOverlay.showIndeterminate("Opening radar...")
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
        val projectSummary = if (allResults.isEmpty()) {
            "Analyze Project to build a global view."
        } else {
            buildString {
                append("Start with the top ${min(3, sortedResults.size)} files. ")
                append(strongestFactors.joinToString(" + ").ifBlank { "Stable code paths" })
                append(" lead the pressure.")
            }
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
        overviewPanel.update(snapshot)
        issuesPanel.update(snapshot)
        hasLoadedOnce = true
        isViewRefreshing = false
        if (preferredFileUrl != null && snapshot.currentResult?.fileUrl == preferredFileUrl) {
            preferredFileUrl = null
        }
        updateLoadingUi()
        updateFooterStatus()
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    private fun runScanProject() {
        if (isAnalyzeRunning) return
        isAnalyzeRunning = true
        pendingUrls.clear()
        analysisTargetCount = 0
        analysisCompletedCount = 0
        loadingOverlay.showProgress("Queueing project scan...", 0, 0)
        updateLoadingUi()
        footerPanel.setScanning("Starting project scan...")
        AppExecutorUtil.getAppExecutorService().execute {
            val reportResult = runCatching {
                service.queueProjectAnalysis { processed, total ->
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || !isAnalyzeRunning) return@invokeLater
                        val msg = if (total > 0) "Queueing $processed / $total..." else "Queueing..."
                        footerPanel.setScanning(msg, processed, total)
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
                            footerPanel.setScanning("Analyzing ${report.queuedCount} files...", 0, report.queuedCount)
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
            lastStatusMessage = "Project analysis complete."
            footerPanel.setIdle("Analysis complete — ${analysisTargetCount} files")
        } else {
            footerPanel.setScanning(
                "Analyzing... $analysisCompletedCount / $analysisTargetCount",
                analysisCompletedCount, analysisTargetCount,
            )
        }
    }

    private fun runExport() {
        val path = service.exportReports()
        if (path == null) Messages.showErrorDialog(project, "Failed to export report.", "Complexity Radar")
        else Messages.showInfoMessage(project, "Report exported to $path", "Complexity Radar")
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
            !hasLoadedOnce && isViewRefreshing -> { loadingOverlay.showIndeterminate("Opening radar..."); true }
            else -> false
        }
        loadingOverlay.isVisible = visible
        toolbarPanel.setScanRunning(isAnalyzeRunning)
    }

    private fun updateFooterStatus() {
        val msg = lastStatusMessage
        if (msg != null) footerPanel.setIdle(msg) else footerPanel.setIdle("Ready")
    }

    private fun openResult(result: ComplexityResult) {
        val file = VirtualFileManager.getInstance().findFileByUrl(result.fileUrl) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun currentEditorFileUrl(): String? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.url

    @Suppress("unused")
    private fun throwableStackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        return writer.toString()
    }
}
