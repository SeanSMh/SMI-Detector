package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService
import com.sqb.complexityradar.ide.services.ComplexityResultListener
import com.sqb.complexityradar.ide.services.RefreshableComplexityView
import com.sqb.complexityradar.ide.ui.poopAccentColor
import com.sqb.complexityradar.ide.ui.poopMutedColor
import com.sqb.complexityradar.ide.ui.poopScoreCount
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.PrintWriter
import java.io.StringWriter
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.OverlayLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.Timer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class ComplexityRadarToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = FocusedComplexityRadarToolWindowPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class FocusedComplexityRadarToolWindowPanel(
    private val project: Project,
) : JPanel(BorderLayout()), RefreshableComplexityView {
    private val service = ComplexityRadarProjectService.getInstance(project)
    private val connection: MessageBusConnection = project.messageBus.connect(project)

    private val tabs = JBTabbedPane()
    private val loadingOverlay = LoadingOverlayPanel()
    private val refreshVersion = AtomicInteger(0)
    private val refreshDebounceTimer =
        Timer(300) {
            refreshViewNow()
        }.apply {
            isRepeats = false
        }

    private val currentRefreshButton = subtleActionButton("Refresh") { service.reanalyzeCurrentFile() }
    private val analyzeProjectButton = primaryActionButton("Analyze Project") { runAnalyzeProject() }
    private val exportButton = subtleActionButton("Export") { runExport() }
    private val toggleGutterButton = subtleActionButton(gutterButtonLabel()) { onToggleGutter() }

    private val currentTitleLabel = JLabel("Current File").apply { font = font.deriveFont(Font.BOLD, font.size2D + 4f) }
    private val currentMetaLabel = JLabel("Open a file and run analysis to inspect it.").apply { foreground = secondaryTextColor() }
    private val currentSummaryLabel =
        JLabel("Run Refresh to build a diagnosis.").apply {
            foreground = poopAccentColor(Severity.ORANGE)
            font = font.deriveFont(Font.BOLD, font.size2D + 0.5f)
        }
    private val currentRadarPanel = RadarChartPanel()
    private val currentMainFactorLabel =
        JLabel("–").apply {
            foreground = secondaryTextColor()
            font = font.deriveFont(font.size2D - 0.5f)
        }
    private val currentHotspotsPanel = detailListPanel()

    private val projectSummaryStripLabel =
        JLabel("–").apply {
            foreground = secondaryTextColor()
        }
    private val projectFilesValue = summaryValueLabel("0")
    private val projectAvgPoopValue = summaryValueLabel("0/5")
    private val projectRedValue = summaryValueLabel("0")
    private val projectStatusLabel =
        JLabel("Idle").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        }
    private val projectStatusProgress =
        JProgressBar().apply {
            isBorderPainted = false
            isStringPainted = true
            foreground = JBColor(Color(0xB58B57), Color(0xD0A873))
            background = JBColor(Color(0xECE4D8), Color(0x302921))
            preferredSize = Dimension(JBUI.scale(220), JBUI.scale(8))
        }
    private val projectRadarPanel = RadarChartPanel()
    private val projectPressureLabel =
        JLabel("Run Analyze Project to build a smell map.").apply {
            foreground = secondaryTextColor()
            font = font.deriveFont(font.size2D - 0.5f)
        }
    private val projectSummaryPanel = detailListPanel()
    private val topFilesModel = DefaultListModel<ComplexityResult>()
    private val topFilesList = JBList(topFilesModel)

    private var hasLoadedOnce = false
    private var isViewRefreshing = false
    private var isAnalyzeProjectRunning = false
    private var preferredFileUrl: String? = null
    private var projectStatusMessage: String? = null
    private var projectStatusDetails: String? = null
    private var lastProjectSnapshot: FocusedViewSnapshot? = null
    private val pendingProjectResultUrls = linkedSetOf<String>()
    private var projectAnalysisTargetCount = 0
    private var projectAnalysisCompletedCount = 0

    init {
        add(buildNorthPanel(), BorderLayout.NORTH)
        add(buildContentLayer(), BorderLayout.CENTER)

        preferredSize = Dimension(980, 720)
        configureCurrentPane()
        configureTopFilesList()
        refreshViewNow()

        connection.subscribe(
            ComplexityResultListener.TOPIC,
            ComplexityResultListener { batch ->
                onResultsUpdated(batch.fileUrls)
                scheduleRefreshView()
            },
        )
    }

    override fun refreshView() {
        scheduleRefreshView()
    }

    private fun scheduleRefreshView() {
        if (project.isDisposed) {
            return
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            refreshDebounceTimer.restart()
        } else {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    refreshDebounceTimer.restart()
                }
            }, project.disposed)
        }
    }

    private fun refreshViewNow() {
        val refreshId = refreshVersion.incrementAndGet()
        val targetFileUrl = preferredFileUrl ?: currentEditorFileUrl()
        isViewRefreshing = true
        if (!hasLoadedOnce) {
            loadingOverlay.showIndeterminate("Opening radar...")
        }
        updateLoadingUi()
        AppExecutorUtil.getAppExecutorService().execute {
            val snapshot =
                runCatching { buildSnapshot(targetFileUrl) }.getOrElse {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || refreshId != refreshVersion.get()) {
                            return@invokeLater
                        }
                        isViewRefreshing = false
                        updateLoadingUi()
                    }
                    return@execute
                }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || refreshId != refreshVersion.get()) {
                    return@invokeLater
                }
                applySnapshot(snapshot)
            }
        }
    }

    override fun revealFile(file: com.intellij.openapi.vfs.VirtualFile?) {
        preferredFileUrl = file?.url
        tabs.selectedIndex = 0
        when {
            file == null -> applyCurrentFile(null)
            else -> {
                val immediateResult = service.getResult(file)
                if (immediateResult != null) {
                    applyCurrentFile(immediateResult)
                    hasLoadedOnce = true
                } else {
                    applyCurrentPending(file.path)
                }
            }
        }
        refreshViewNow()
    }

    private fun buildSnapshot(targetFileUrl: String?): FocusedViewSnapshot {
        val allResults = service.allResults()
        val targetFile =
            targetFileUrl?.let { url ->
                com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(url)
            }
        val currentResult =
            targetFileUrl?.let { target ->
                allResults.firstOrNull { it.fileUrl == target }
            } ?: allResults.firstOrNull { it.fileUrl == currentEditorFileUrl() }
        val sortedResults =
            allResults.sortedWith(
                compareByDescending<ComplexityResult> { it.priority }
                    .thenByDescending { it.score }
                    .thenBy { it.filePath },
            )
        val averageScore = allResults.map { it.score }.average().takeIf { !it.isNaN() } ?: 0.0
        val aggregateValues =
            FactorType.entries.associateWith { factor ->
                if (allResults.isEmpty()) {
                    0.0
                } else {
                    allResults.map { result ->
                        result.contributions.firstOrNull { it.type == factor }?.normalized ?: 0.0
                    }.average()
                }
            }
        val strongestFactors =
            aggregateValues.entries
                .sortedByDescending { it.value }
                .take(2)
                .map { it.key.displayName }
        val projectSummary =
            if (allResults.isEmpty()) {
                "Analyze Project to build a global view."
            } else {
                buildString {
                    append("Start with the top ")
                    append(min(3, sortedResults.size))
                    append(" files. ")
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
        when {
            snapshot.currentResult != null -> applyCurrentFile(snapshot.currentResult)
            snapshot.targetFilePath != null -> applyCurrentPending(snapshot.targetFilePath)
            else -> applyCurrentFile(null)
        }
        applyProject(snapshot)
        hasLoadedOnce = true
        isViewRefreshing = false
        if (preferredFileUrl != null && snapshot.currentResult?.fileUrl == preferredFileUrl) {
            preferredFileUrl = null
        }
        updateLoadingUi()
    }

    private fun applyCurrentFile(result: ComplexityResult?) {
        if (result == null) {
            currentTitleLabel.text = "Current File"
            currentMetaLabel.text = "Open a file and run analysis to inspect it."
            currentSummaryLabel.text = "Run Refresh to build a diagnosis."
            currentMainFactorLabel.text = "–"
            currentRadarPanel.setResult(null)
            renderCurrentDetailPanels(null)
            return
        }
        currentTitleLabel.text = fileName(result.filePath)
        currentMetaLabel.text = relativePath(result.filePath)
        currentSummaryLabel.text = "Score ${result.score} (${result.severity.label})  •  ${leadingFactors(result)}"
        currentMainFactorLabel.text = leadingFactors(result)
        currentRadarPanel.setResult(result)
        renderCurrentDetailPanels(result)
    }

    private fun applyCurrentPending(filePath: String) {
        currentTitleLabel.text = fileName(filePath)
        currentMetaLabel.text = relativePath(filePath)
        currentSummaryLabel.text = "This file is queued for analysis."
        currentMainFactorLabel.text = "–"
        currentRadarPanel.setResult(null)
        renderCurrentPendingPanels()
    }

    private fun applyProject(snapshot: FocusedViewSnapshot) {
        lastProjectSnapshot = snapshot
        projectFilesValue.text = snapshot.projectResults.size.toString()
        val avgRounded = snapshot.averageScore.roundToInt().coerceIn(0, 100)
        projectAvgPoopValue.text = "${poopScoreCount(avgRounded)}/5"
        projectRedValue.text = snapshot.redCount.toString()
        val criticalText = if (snapshot.redCount > 0) "  \u2022  \u26a0 ${snapshot.redCount} Critical" else ""
        projectSummaryStripLabel.text = "${snapshot.projectResults.size} files  \u2022  Avg Score $avgRounded$criticalText"
        projectRadarPanel.setAggregate(snapshot.aggregateValues, snapshot.aggregateSeverity)
        val strongestFactors =
            snapshot.aggregateValues.entries
                .sortedByDescending { it.value }
                .take(2)
                .map { it.key.displayName }
        projectPressureLabel.text =
            when {
                snapshot.projectResults.isEmpty() -> "Run Analyze Project to build a smell map."
                strongestFactors.isEmpty() -> "Pressure is currently balanced."
                strongestFactors.size == 1 -> "${strongestFactors.first()} is driving most of the pressure."
                else -> "${strongestFactors[0]} and ${strongestFactors[1]} dominate the current risk."
            }
        topFilesModel.removeAllElements()
        snapshot.topFiles.forEach(topFilesModel::addElement)
    }

    private fun buildNorthPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12, 12, 0, 12)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(toggleGutterButton)
                },
                BorderLayout.WEST,
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(analyzeProjectButton)
                    add(exportButton)
                },
                BorderLayout.EAST,
            )
        }

    private fun buildContentLayer(): JComponent =
        JPanel().apply {
            layout = OverlayLayout(this)
            add(buildMainContent().apply {
                alignmentX = 0.5f
                alignmentY = 0.5f
            })
            add(loadingOverlay.apply {
                alignmentX = 0.5f
                alignmentY = 0.5f
            })
        }

    private fun buildMainContent(): JComponent =
        tabs.apply {
            border = JBUI.Borders.empty(8, 12, 12, 12)
            addTab("Current File", buildCurrentFileTab())
            addTab("Project", buildProjectTab())
        }

    private fun buildCurrentFileTab(): JComponent =
        JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(12))).apply {
            border = cardBorder()
            add(buildCurrentHeader(), BorderLayout.NORTH)
            add(buildCurrentAnalysisRow(), BorderLayout.CENTER)
        }

    private fun buildProjectTab(): JComponent =
        JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(12))).apply {
            border = cardBorder()
            add(
                JPanel(BorderLayout(0, JBUI.scale(10))).apply {
                    border = JBUI.Borders.empty(14, 16, 8, 16)
                    add(buildProjectSummaryStrip(), BorderLayout.NORTH)
                    add(buildProjectStatusStrip(), BorderLayout.SOUTH)
                },
                BorderLayout.NORTH,
            )
            add(
                JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    buildProjectOverviewCard(),
                    buildTopFilesCard(),
                ).apply {
                    border = JBUI.Borders.empty(0, 16, 16, 16)
                    resizeWeight = 0.52
                    dividerSize = JBUI.scale(8)
                },
                BorderLayout.CENTER,
            )
        }

    private fun buildProjectSummaryStrip(): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(projectSummaryStripLabel)
        }

    private fun buildProjectOverviewCard(): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(10, 12, 4, 12)
                    isOpaque = false
                    add(sectionHeaderPill("Project Pressure"), BorderLayout.WEST)
                    add(subtleCaptionLabel("Global smell map"), BorderLayout.SOUTH)
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(0, 12, 12, 12)
                    add(
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            isOpaque = false
                            add(projectPressureLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                            add(verticalGap(6))
                            add(projectRadarPanel)
                        },
                        BorderLayout.CENTER,
                    )
                },
                BorderLayout.CENTER,
            )
        }

    private fun buildProjectStatusStrip(): JComponent =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xE5D7C3), Color(0x43372A))),
                    JBUI.Borders.empty(6, 10),
                )
            background = JBColor(Color(0xFAF5EE), Color(0x231D18))
            isOpaque = true
            add(projectStatusLabel, BorderLayout.WEST)
            add(projectStatusProgress, BorderLayout.CENTER)
        }

    private fun buildTopFilesCard(): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(10, 12, 6, 12)
                    isOpaque = false
                    add(sectionHeaderPill("Fix First"), BorderLayout.WEST)
                },
                BorderLayout.NORTH,
            )
            topFilesList.emptyText.text = "Analyze Project to rank the worst files."
            add(JBScrollPane(topFilesList), BorderLayout.CENTER)
        }

    private fun configureCurrentPane() {
        renderCurrentDetailPanels(null)
    }

    private fun configureTopFilesList() {
        topFilesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        topFilesList.visibleRowCount = -1
        topFilesList.fixedCellHeight = JBUI.scale(68)
        topFilesList.cellRenderer = TopFileRenderer()
        topFilesList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount != 2) {
                        return
                    }
                    topFilesList.selectedValue?.let { openResult(it) }
                }
            },
        )
        topFilesList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) {
                return@addListSelectionListener
            }
            topFilesList.selectedValue?.let { result ->
                preferredFileUrl = result.fileUrl
                tabs.selectedIndex = 0
                refreshView()
            }
        }
    }

    private fun runAnalyzeProject() {
        if (isAnalyzeProjectRunning) {
            return
        }
        tabs.selectedIndex = 1
        isAnalyzeProjectRunning = true
        pendingProjectResultUrls.clear()
        projectAnalysisTargetCount = 0
        projectAnalysisCompletedCount = 0
        setProjectStatus("Starting project scan...")
        updateProjectProgress(active = true, message = "Queueing files...", processed = 0, total = 0)
        loadingOverlay.showProgress("Queueing project scan...", 0, 0)
        updateLoadingUi()
        AppExecutorUtil.getAppExecutorService().execute {
            val reportResult =
                runCatching {
                    service.queueProjectAnalysis { processed, total ->
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || !isAnalyzeProjectRunning) {
                                return@invokeLater
                            }
                            val status =
                                if (total > 0) {
                                    "Queueing project scan... $processed / $total"
                                } else {
                                    "Queueing project scan..."
                                }
                            setProjectStatus(status)
                            updateProjectProgress(active = true, message = "Queueing files...", processed = processed, total = total)
                            loadingOverlay.showProgress("Queueing project scan...", processed, total)
                        }
                    }
                }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                reportResult.fold(
                    onSuccess = { report ->
                        if (report.queuedCount <= 0) {
                            val message = "No analyzable files were queued."
                            val details =
                                buildString {
                                    appendLine("visited=${report.visitedCount}")
                                    appendLine("supported=${report.supportedCount}")
                                    appendLine("withinProject=${report.withinProjectCount}")
                                    appendLine("excludedByConfig=${report.excludedByConfigCount}")
                                    appendLine("fallbackUsed=${report.fallbackUsed}")
                                    append("currentFileFallbackUsed=${report.currentFileFallbackUsed}")
                                }
                            setProjectStatus(message, details)
                            updateProjectProgress(active = false, message = message, processed = 0, total = 0)
                            pendingProjectResultUrls.clear()
                            projectAnalysisTargetCount = 0
                            projectAnalysisCompletedCount = 0
                            loadingOverlay.showIndeterminate("No analyzable files were queued.")
                        } else {
                            pendingProjectResultUrls.clear()
                            pendingProjectResultUrls.addAll(report.queuedFileUrls)
                            projectAnalysisTargetCount = report.queuedCount
                            projectAnalysisCompletedCount = 0
                            setProjectStatus(
                                "Queued ${report.queuedCount} files. Running analysis...",
                                buildString {
                                    appendLine("visited=${report.visitedCount}")
                                    appendLine("supported=${report.supportedCount}")
                                    appendLine("withinProject=${report.withinProjectCount}")
                                    appendLine("excludedByConfig=${report.excludedByConfigCount}")
                                    appendLine("fallbackUsed=${report.fallbackUsed}")
                                    append("currentFileFallbackUsed=${report.currentFileFallbackUsed}")
                                },
                            )
                            updateProjectProgress(
                                active = true,
                                message = "Analyzing files...",
                                processed = 0,
                                total = report.queuedCount,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        setProjectStatus("Project scan failed.", throwableStackTrace(throwable))
                        updateProjectProgress(active = false, message = "Project scan failed.", processed = 0, total = 0)
                        pendingProjectResultUrls.clear()
                        projectAnalysisTargetCount = 0
                        projectAnalysisCompletedCount = 0
                        loadingOverlay.showIndeterminate("Project scan failed.")
                    },
                )
                isAnalyzeProjectRunning = false
                updateLoadingUi()
            }
        }
    }

    private fun setProjectStatus(
        message: String,
        details: String? = null,
    ) {
        projectStatusMessage = message
        projectStatusDetails = details
        renderProjectSummaryPanel(lastProjectSnapshot)
    }

    private fun updateProjectProgress(
        active: Boolean,
        message: String,
        processed: Int,
        total: Int,
    ) {
        projectStatusLabel.text = message
        if (active) {
            if (total <= 0) {
                projectStatusProgress.isIndeterminate = true
                projectStatusProgress.isStringPainted = false
            } else {
                projectStatusProgress.isIndeterminate = false
                projectStatusProgress.isStringPainted = true
                projectStatusProgress.minimum = 0
                projectStatusProgress.maximum = total
                projectStatusProgress.value = processed.coerceIn(0, total)
                projectStatusProgress.string = "$processed / $total"
            }
        } else {
            projectStatusProgress.isIndeterminate = false
            projectStatusProgress.isStringPainted = total > 0
            projectStatusProgress.minimum = 0
            projectStatusProgress.maximum = if (total > 0) total else 1
            projectStatusProgress.value = if (total > 0) processed.coerceIn(0, total) else 0
            projectStatusProgress.string = if (total > 0) "$processed / $total" else ""
        }
    }

    private fun onResultsUpdated(fileUrls: Collection<String>) {
        if (pendingProjectResultUrls.isEmpty()) {
            return
        }
        var changed = false
        fileUrls.forEach { fileUrl ->
            if (pendingProjectResultUrls.remove(fileUrl)) {
                projectAnalysisCompletedCount += 1
                changed = true
            }
        }
        if (!changed) {
            return
        }
        if (pendingProjectResultUrls.isEmpty()) {
            setProjectStatus("Project analysis complete.", projectStatusDetails)
            updateProjectProgress(
                active = false,
                message = "Analysis complete",
                processed = projectAnalysisTargetCount,
                total = projectAnalysisTargetCount,
            )
        } else {
            setProjectStatus(
                "Analyzing queued files... $projectAnalysisCompletedCount / $projectAnalysisTargetCount",
                projectStatusDetails,
            )
            updateProjectProgress(
                active = true,
                message = "Analyzing files...",
                processed = projectAnalysisCompletedCount,
                total = projectAnalysisTargetCount,
            )
        }
    }

    private fun throwableStackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            throwable.printStackTrace(printWriter)
        }
        return writer.toString()
    }

    private fun runExport() {
        val path = service.exportReports()
        if (path == null) {
            Messages.showErrorDialog(project, "Failed to export report.", "Complexity Radar")
        } else {
            Messages.showInfoMessage(project, "Report exported to $path", "Complexity Radar")
        }
    }

    private fun gutterButtonLabel(): String {
        val on = service.uiSettings().showGutterIcons
        return if (on) "Hide Gutter Icons" else "Show Gutter Icons"
    }

    private fun onToggleGutter() {
        val uiSettings = project.getService(com.sqb.complexityradar.settings.ComplexityUiSettingsService::class.java)
        uiSettings.update { it.showGutterIcons = !it.showGutterIcons }
        toggleGutterButton.text = gutterButtonLabel()
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun updateLoadingUi() {
        val visible =
            when {
                isAnalyzeProjectRunning -> true
                !hasLoadedOnce && isViewRefreshing -> {
                    loadingOverlay.showIndeterminate("Opening radar...")
                    true
                }
                else -> false
            }
        loadingOverlay.isVisible = visible
        analyzeProjectButton.isEnabled = !isAnalyzeProjectRunning
        exportButton.isEnabled = !isAnalyzeProjectRunning
    }

    private fun renderProjectSummaryPanel(snapshot: FocusedViewSnapshot?) {
        val items =
            when {
                snapshot == null || snapshot.projectResults.isEmpty() -> buildProjectEmptyItems()
                else -> buildProjectSummaryItems(snapshot)
            }
        setDetailItems(projectSummaryPanel, items)
    }

    private fun primaryActionButton(
        text: String,
        action: () -> Unit,
    ): JButton =
        JButton(text).apply {
            isOpaque = true
            background = JBColor(Color(0xE8D1B3), Color(0x3A2D20))
            foreground = JBColor(Color(0x352414), Color(0xF3E5D6))
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xC8A47C), Color(0x6B543D))),
                    JBUI.Borders.empty(6, 12),
                )
            font = font.deriveFont(Font.BOLD)
            addActionListener { action() }
        }

    private fun subtleActionButton(
        text: String,
        action: () -> Unit,
    ): JButton =
        JButton(text).apply {
            isOpaque = true
            background = JBColor(Color(0xFAF6F0), Color(0x201B16))
            foreground = JBColor(Color(0x5E4C3A), Color(0xCDB79D))
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xE2D4C4), Color(0x4B3B2C))),
                    JBUI.Borders.empty(4, 10),
                )
            font = font.deriveFont(font.size2D - 0.5f)
            addActionListener { action() }
        }

    private fun buildCurrentSection(
        title: String,
        content: JPanel,
    ): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(10, 12, 0, 12)
                    isOpaque = false
                    add(sectionHeaderPill(title), BorderLayout.WEST)
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(8, 12, 12, 12)
                    isOpaque = false
                    add(content, BorderLayout.NORTH)
                },
                BorderLayout.CENTER,
            )
        }

    private fun buildCurrentHeader(): JComponent =
        JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            border = JBUI.Borders.empty(14, 16, 10, 16)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(currentTitleLabel)
                    add(currentMetaLabel)
                },
                BorderLayout.WEST,
            )
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(currentSummaryLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(currentRefreshButton)
                },
                BorderLayout.EAST,
            )
        }

    private fun buildCurrentAnalysisRow(): JComponent =
        JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JPanel(BorderLayout()).apply {
                border = cardBorder()
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        border = JBUI.Borders.empty(10, 10, 10, 10)
                        isOpaque = false
                        add(currentRadarPanel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                        add(verticalGap(6))
                        add(currentMainFactorLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
                    },
                    BorderLayout.CENTER,
                )
            },
            buildCurrentHotspotsCard(),
        ).apply {
            border = JBUI.Borders.empty(0, 16, 12, 16)
            resizeWeight = 0.52
            dividerSize = JBUI.scale(8)
        }

    private fun buildCurrentHotspotsCard(): JComponent =
        buildCurrentSection("Hotspots", currentHotspotsPanel)

    private fun summaryCard(
        title: String,
        valueLabel: JLabel,
        caption: String,
    ): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JLabel(title).apply {
                    foreground = secondaryTextColor()
                    border = JBUI.Borders.empty(8, 10, 0, 10)
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(0, 10, 0, 10)
                    add(valueLabel, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            add(
                JLabel(caption).apply {
                    foreground = secondaryTextColor()
                    border = JBUI.Borders.empty(0, 10, 8, 10)
                },
                BorderLayout.SOUTH,
            )
        }.apply {
            preferredSize = Dimension(JBUI.scale(170), JBUI.scale(92))
        }

    private fun primarySummaryCard(
        title: String,
        valueLabel: JLabel,
        caption: String,
    ): JComponent =
        JPanel(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xD8C0A1), Color(0x5A4630))),
                    JBUI.Borders.empty(0),
                )
            background = JBColor(Color(0xFBF3E8), Color(0x251F19))
            isOpaque = true
            add(
                JLabel(title).apply {
                    foreground = poopAccentColor(Severity.ORANGE)
                    font = font.deriveFont(Font.BOLD, font.size2D + 0.5f)
                    border = JBUI.Borders.empty(10, 12, 0, 12)
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(0, 12, 0, 12)
                    add(valueLabel.apply { font = font.deriveFont(Font.BOLD, font.size2D + 10f) }, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            add(
                JLabel(caption).apply {
                    foreground = secondaryTextColor()
                    font = font.deriveFont(font.size2D - 0.5f)
                    border = JBUI.Borders.empty(0, 12, 10, 12)
                },
                BorderLayout.SOUTH,
            )
            preferredSize = Dimension(JBUI.scale(240), JBUI.scale(104))
        }

    private fun summaryValueLabel(text: String): JLabel =
        JLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 7f)
        }

    private fun detailListPanel(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

    private fun sectionHeaderPill(text: String): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xF5E8D5), Color(0x2A221A))
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xDEC2A0), Color(0x57442E))),
                    JBUI.Borders.empty(4, 10),
                )
            add(
                JLabel(text).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = poopAccentColor(Severity.ORANGE)
                },
                BorderLayout.CENTER,
            )
        }

    private fun subtleCaptionLabel(text: String): JComponent =
        JLabel(text).apply {
            foreground = secondaryTextColor()
            font = font.deriveFont(font.size2D - 1f)
        }

    private fun factorTag(text: String): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xF5E8D5), Color(0x2A221A))
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xDEC2A0), Color(0x57442E))),
                    JBUI.Borders.empty(3, 8),
                )
            add(
                JLabel(text).apply {
                    foreground = poopAccentColor(Severity.ORANGE)
                    font = font.deriveFont(Font.BOLD, font.size2D - 0.5f)
                },
                BorderLayout.CENTER,
            )
        }

    private fun openResult(result: ComplexityResult) {
        val file = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(result.fileUrl) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun currentEditorFileUrl(): String? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.url

    private fun verticalGap(height: Int): JComponent =
        JPanel().apply {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(height))
            minimumSize = preferredSize
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(height))
        }

    private fun renderCurrentDetailPanels(result: ComplexityResult?) {
        if (result == null) {
            setDetailItems(currentHotspotsPanel, listOf(detailBodyLabel("Hotspot methods will appear here after analysis.")))
            return
        }

        val items = mutableListOf<JComponent>()
        if (result.hotspots.isEmpty()) {
            items += detailBodyLabel("No hotspot methods detected yet.")
        } else {
            result.hotspots.take(3).forEach { hotspot ->
                items += detailItemLabel(
                    hotspot.methodName,
                    "Line ${hotspot.line}  \u2022  Score ${hotspot.score}",
                )
            }
            items += detailBodyLabel("\u2192  ${result.hotspots.first().recommendation}")
        }
        setDetailItems(currentHotspotsPanel, items)
    }

    private fun renderCurrentPendingPanels() {
        setDetailItems(currentHotspotsPanel, listOf(detailBodyLabel("Waiting for hotspot extraction...")))
    }


    private fun buildProjectEmptyItems(): List<JComponent> {
        val items = mutableListOf<JComponent>()
        val status = projectStatusMessage?.takeIf { it.isNotBlank() }
        if (status != null) {
            items += detailItemLabel("Latest Scan", status)
        } else {
            items += detailBodyLabel("Analyze Project to build a global smell map.")
        }
        projectStatusDetails?.takeIf { it.isNotBlank() }?.let { details ->
            items += detailBodyLabel(details.replace("\n", "  "))
        }
        return items
    }

    private fun buildProjectSummaryItems(snapshot: FocusedViewSnapshot): List<JComponent> {
        val avgRounded = snapshot.averageScore.roundToInt().coerceIn(0, 100)
        val strongestFactors =
            snapshot.aggregateValues.entries
                .sortedByDescending { it.value }
                .take(2)
                .map { it.key.displayName }
        val pressureLine =
            when {
                strongestFactors.isEmpty() -> "Pressure is currently balanced."
                strongestFactors.size == 1 -> "Most pressure comes from ${strongestFactors.first()}."
                else -> "Most pressure comes from ${strongestFactors[0]} and ${strongestFactors[1]}."
            }
        val items = mutableListOf<JComponent>()
        items += detailItemLabel("What To Fix First", snapshot.projectSummary)
        items += detailItemLabel("Pressure Snapshot", pressureLine)
        items += detailItemLabel(
            "Project Risk",
            "Avg ${poopScoreCount(avgRounded)}/5 • Red ${snapshot.redCount} • Top ${min(3, snapshot.topFiles.size)}",
        )
        projectStatusMessage?.takeIf { it.isNotBlank() }?.let { status ->
            items += detailItemLabel("Latest Scan", status)
        }
        return items
    }

    private fun setDetailItems(
        container: JPanel,
        items: List<JComponent>,
    ) {
        container.removeAll()
        items.forEachIndexed { index, component ->
            if (index > 0) {
                container.add(verticalGap(8))
            }
            container.add(component)
        }
        container.revalidate()
        container.repaint()
    }

    private fun detailItemLabel(
        title: String,
        body: String,
    ): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xF8F1E7), Color(0x231D18))
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xE4D4BE), Color(0x4A3B2B))),
                    JBUI.Borders.empty(8, 10),
                )
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(
                        JLabel(title).apply {
                            font = font.deriveFont(Font.BOLD)
                            foreground = JBColor(Color(0x2F3742), Color(0xE7EBF0))
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                    add(verticalGap(4))
                    add(
                        JLabel("<html>${html(body)}</html>").apply {
                            foreground = secondaryTextColor()
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                },
                BorderLayout.CENTER,
            )
        }

    private fun detailBodyLabel(text: String): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(Color(0xF8F4ED), Color(0x1F1A16))
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xE8DDD0), Color(0x41362C))),
                    JBUI.Borders.empty(8, 10),
                )
            add(
                JLabel("<html>${html(text)}</html>").apply {
                    foreground = secondaryTextColor()
                    alignmentX = Component.LEFT_ALIGNMENT
                },
                BorderLayout.CENTER,
            )
        }

    private fun leadingFactors(result: ComplexityResult): String {
        val names = result.contributions.take(2).map { it.type.displayName }
        return when {
            names.isEmpty() -> "A stable profile right now"
            names.size == 1 -> "Mostly driven by ${names.first()}"
            else -> "Mostly driven by ${names[0]} and ${names[1]}"
        }
    }

    private fun relativePath(filePath: String): String {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return filePath.replace('\\', '/')
        val normalized = filePath.replace('\\', '/')
        val prefix = "$basePath/"
        return if (normalized.startsWith(prefix)) normalized.removePrefix(prefix) else normalized
    }

    private fun fileName(filePath: String): String = filePath.substringAfterLast('/', filePath)

    private fun shortenMiddle(
        value: String,
        maxLength: Int,
    ): String {
        if (value.length <= maxLength) {
            return value
        }
        val head = (maxLength / 2) - 2
        val tail = maxLength - head - 3
        return value.take(head) + "..." + value.takeLast(tail)
    }

    private fun html(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun secondaryTextColor(): Color = JBColor(Color(0x6F7885), Color(0x9097A0))

    private fun cardBorder() =
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(0xD9DEE7), Color(0x373D4A))),
            JBUI.Borders.empty(0),
        )

    private inner class TopFileRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val result = value as? ComplexityResult ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val root =
                JPanel(BorderLayout()).apply {
                    border =
                        BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, severityColor(result.severity)),
                            JBUI.Borders.empty(6, 10),
                        )
                    background = if (isSelected) list.selectionBackground else list.background
                    toolTipText = buildPoopTooltip(result.score)
                }
            val rankLabel =
                JLabel("#${index + 1}", SwingConstants.CENTER).apply {
                    foreground = if (isSelected) list.selectionForeground else poopAccentColor(Severity.ORANGE)
                    font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                    border = JBUI.Borders.emptyRight(6)
                }
            val textPanel =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }
            textPanel.add(
                JLabel(fileName(result.filePath)).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                },
            )
            textPanel.add(
                JLabel(shortenMiddle(relativePath(result.filePath), 30)).apply {
                    foreground = if (isSelected) list.selectionForeground else secondaryTextColor()
                },
            )
            val strip =
                PoopRatingStrip(iconSize = 11, gap = 2).apply {
                    setScore(result.score, result.severity, isSelected)
                }
            root.add(rankLabel, BorderLayout.WEST)
            root.add(textPanel, BorderLayout.CENTER)
            root.add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(strip)
                    add(
                        JLabel("${poopScoreCount(result.score)}/5").apply {
                            foreground = if (isSelected) list.selectionForeground else secondaryTextColor()
                            font = font.deriveFont(Font.BOLD, font.size2D - 0.5f)
                            border =
                                BorderFactory.createCompoundBorder(
                                    BorderFactory.createLineBorder(
                                        if (isSelected) list.selectionForeground else JBColor(Color(0xD8C8B6), Color(0x4F4133)),
                                    ),
                                    JBUI.Borders.empty(2, 6),
                                )
                        },
                    )
                },
                BorderLayout.EAST,
            )
            return root
        }
    }
}

private data class FocusedViewSnapshot(
    val currentResult: ComplexityResult?,
    val projectResults: List<ComplexityResult>,
    val topFiles: List<ComplexityResult>,
    val averageScore: Double,
    val redCount: Int,
    val aggregateValues: Map<FactorType, Double>,
    val aggregateSeverity: Severity,
    val projectSummary: String,
    val targetFileUrl: String?,
    val targetFilePath: String?,
)

