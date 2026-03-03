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
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
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
import javax.swing.JEditorPane
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

    private val currentRefreshButton = actionButton("Refresh") { service.reanalyzeCurrentFile() }
    private val analyzeProjectButton = actionButton("Analyze Project") { runAnalyzeProject() }
    private val exportButton = actionButton("Export") { runExport() }

    private val currentTitleLabel = JLabel("Current File").apply { font = font.deriveFont(Font.BOLD, font.size2D + 4f) }
    private val currentMetaLabel = JLabel("Open a file and run analysis to inspect it.").apply { foreground = secondaryTextColor() }
    private val currentPoopStrip = PoopRatingStrip(iconSize = 20, gap = 5)
    private val currentRadarPanel = RadarChartPanel()
    private val currentDetailsPane = JEditorPane("text/html", currentEmptyState())

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
    private val projectNotesPane = JEditorPane("text/html", projectEmptyState())
    private val topFilesModel = DefaultListModel<ComplexityResult>()
    private val topFilesList = JBList(topFilesModel)

    private var hasLoadedOnce = false
    private var isViewRefreshing = false
    private var isAnalyzeProjectRunning = false
    private var preferredFileUrl: String? = null
    private var projectStatusMessage: String? = null
    private var projectStatusDetails: String? = null
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
                    append("Most pressure comes from ")
                    append(strongestFactors.joinToString(" and ").ifBlank { "stable code paths" })
                    append(". Start with the top ")
                    append(min(3, sortedResults.size))
                    append(" files.")
                }
            }
        return FocusedViewSnapshot(
            currentResult = currentResult,
            projectResults = allResults,
            topFiles = sortedResults.take(5),
            averageScore = averageScore,
            redCount = allResults.count { it.severity == Severity.RED },
            aggregateValues = aggregateValues,
            aggregateSeverity = severityForScore(averageScore.roundToInt()),
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
            currentPoopStrip.setScore(0, Severity.GREEN)
            currentMetaLabel.text = "Reanalyze this file to inspect it."
            currentRadarPanel.setResult(null)
            currentDetailsPane.text = currentEmptyState()
            return
        }
        currentTitleLabel.text = fileName(result.filePath)
        currentPoopStrip.setScore(result.score, result.severity)
        currentMetaLabel.text =
            "${relativePath(result.filePath)}  |  Poop ${poopCountForScore(result.score)}/5  |  Raw ${result.score}  |  ${result.severity.label}"
        currentRadarPanel.setResult(result)
        currentDetailsPane.text = renderCurrentFileHtml(result)
    }

    private fun applyCurrentPending(filePath: String) {
        currentTitleLabel.text = fileName(filePath)
        currentPoopStrip.setScore(0, Severity.GREEN)
        currentMetaLabel.text = "Analyzing ${relativePath(filePath)}..."
        currentRadarPanel.setResult(null)
        currentDetailsPane.text =
            "<html><body style='padding:8px;color:#8f96a3;'>Analyzing this file. The breakdown will appear here shortly.</body></html>"
    }

    private fun applyProject(snapshot: FocusedViewSnapshot) {
        projectFilesValue.text = snapshot.projectResults.size.toString()
        val avgRounded = snapshot.averageScore.roundToInt().coerceIn(0, 100)
        projectAvgPoopValue.text = "${poopCountForScore(avgRounded)}/5"
        projectRedValue.text = snapshot.redCount.toString()
        projectRadarPanel.setAggregate(snapshot.aggregateValues, snapshot.aggregateSeverity)
        projectNotesPane.text = renderProjectSummaryHtml(snapshot)
        topFilesModel.removeAllElements()
        snapshot.topFiles.forEach(topFilesModel::addElement)
    }

    private fun buildNorthPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12, 12, 0, 12)
            add(
                JLabel("Complexity Radar").apply {
                    font = font.deriveFont(Font.BOLD, font.size2D + 2f)
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
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(14, 16, 8, 16)
                    add(
                        JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            isOpaque = false
                            add(currentTitleLabel)
                            add(currentPoopStrip.apply { alignmentX = Component.LEFT_ALIGNMENT })
                            add(currentMetaLabel)
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
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                    border = JBUI.Borders.empty(0, 16, 16, 16)
                    add(
                        JPanel(BorderLayout()).apply {
                            border = cardBorder()
                            add(
                                JLabel("Why This File Smells").apply {
                                    font = font.deriveFont(Font.BOLD)
                                    border = JBUI.Borders.empty(8, 10, 0, 10)
                                },
                                BorderLayout.NORTH,
                            )
                            add(currentRadarPanel, BorderLayout.CENTER)
                        },
                        BorderLayout.WEST,
                    )
                    add(
                        JPanel(BorderLayout()).apply {
                            border = cardBorder()
                            add(
                                JLabel("Breakdown").apply {
                                    font = font.deriveFont(Font.BOLD)
                                    border = JBUI.Borders.empty(8, 10, 0, 10)
                                },
                                BorderLayout.NORTH,
                            )
                            configureHtmlPane(currentDetailsPane)
                            add(JBScrollPane(currentDetailsPane), BorderLayout.CENTER)
                        },
                        BorderLayout.CENTER,
                    )
                },
                BorderLayout.CENTER,
            )
        }

    private fun buildProjectTab(): JComponent =
        JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(12))).apply {
            border = cardBorder()
            add(buildProjectSummaryStrip(), BorderLayout.NORTH)
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
        JPanel(GridLayout(1, 3, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(14, 16, 8, 16)
            add(summaryCard("Files", projectFilesValue, "analyzed now"))
            add(summaryCard("Avg Poop", projectAvgPoopValue, "project smell"))
            add(summaryCard("Red Files", projectRedValue, "critical now"))
        }

    private fun buildProjectOverviewCard(): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JLabel("Project Snapshot").apply {
                    font = font.deriveFont(Font.BOLD)
                    border = JBUI.Borders.empty(10, 12, 0, 12)
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
                            add(buildProjectStatusBar())
                            add(projectRadarPanel)
                        },
                        BorderLayout.NORTH,
                    )
                    configureHtmlPane(projectNotesPane)
                    add(JBScrollPane(projectNotesPane), BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

    private fun buildProjectStatusBar(): JComponent =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(0xE5D7C3), Color(0x43372A))),
                    JBUI.Borders.empty(8, 10),
                )
            background = JBColor(Color(0xFAF5EE), Color(0x231D18))
            isOpaque = true
            add(projectStatusLabel, BorderLayout.NORTH)
            add(projectStatusProgress, BorderLayout.SOUTH)
        }

    private fun buildTopFilesCard(): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JLabel("Worst Files").apply {
                    font = font.deriveFont(Font.BOLD)
                    border = JBUI.Borders.empty(10, 12, 6, 12)
                },
                BorderLayout.NORTH,
            )
            topFilesList.emptyText.text = "Analyze Project to rank the worst files."
            add(JBScrollPane(topFilesList), BorderLayout.CENTER)
        }

    private fun configureCurrentPane() {
        configureHtmlPane(currentDetailsPane)
        configureHtmlPane(projectNotesPane)
    }

    private fun configureTopFilesList() {
        topFilesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        topFilesList.visibleRowCount = -1
        topFilesList.fixedCellHeight = JBUI.scale(82)
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
        projectNotesPane.text = projectStatusHtml(message, details)
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

    private fun renderCurrentFileHtml(result: ComplexityResult): String {
        val factors =
            result.contributions.take(3).joinToString("<br/>") {
                "<b>${html(it.type.displayName)}</b>: ${html(it.explanation)}"
            }
        val hotspots =
            if (result.hotspots.isEmpty()) {
                "No hotspot methods detected yet."
            } else {
                result.hotspots.take(3).joinToString("<br/>") {
                    "<b>${html(it.methodName)}</b> @ line ${it.line} (${it.score})"
                }
            }
        val recommendation = result.hotspots.firstOrNull()?.recommendation ?: "Split responsibilities and flatten deep nesting first."
        return """
            <html><body style='padding:8px;'>
            <b>Why It Smells</b><br/>$factors
            <br/><br/>
            <b>Hotspots</b><br/>$hotspots
            <br/><br/>
            <b>Refactor Moves</b><br/>${html(recommendation)}
            </body></html>
        """.trimIndent()
    }

    private fun renderProjectSummaryHtml(snapshot: FocusedViewSnapshot): String {
        val statusBlock =
            projectStatusMessage?.takeIf { it.isNotBlank() }?.let {
                buildString {
                    append("<b>Scan Status</b><br/>")
                    append(html(it))
                    projectStatusDetails?.takeIf { details -> details.isNotBlank() }?.let { details ->
                        append("<br/><br/><pre style='white-space:pre-wrap;font-family:monospace;'>")
                        append(html(details))
                        append("</pre>")
                    }
                    append("<br/><br/>")
                }
            }.orEmpty()
        if (snapshot.projectResults.isEmpty()) {
            return if (statusBlock.isNotEmpty()) {
                "<html><body style='padding:8px;'>$statusBlock</body></html>"
            } else {
                projectEmptyState()
            }
        }
        val topFiles =
            snapshot.topFiles.joinToString("<br/>") { result ->
                "<b>${html(fileName(result.filePath))}</b>  ${poopCountForScore(result.score)}/5  (${result.score})"
            }
        return """
            <html><body style='padding:8px;'>
            $statusBlock
            <b>What To Fix First</b><br/>${html(snapshot.projectSummary)}
            <br/><br/>
            <b>Top Targets</b><br/>$topFiles
            </body></html>
        """.trimIndent()
    }

    private fun currentEmptyState(): String =
        "<html><body style='padding:8px;color:#8f96a3;'>Reanalyze this file to inspect it.</body></html>"

    private fun projectEmptyState(): String =
        "<html><body style='padding:8px;color:#8f96a3;'>Analyze Project to build a global view.</body></html>"

    private fun projectStatusHtml(
        message: String,
        details: String? = null,
    ): String =
        buildString {
            append("<html><body style='padding:8px;'><b>Scan Status</b><br/>")
            append(html(message))
            details?.takeIf { it.isNotBlank() }?.let {
                append("<br/><br/><pre style='white-space:pre-wrap;font-family:monospace;'>")
                append(html(it))
                append("</pre>")
            }
            append("</body></html>")
        }

    private fun configureHtmlPane(pane: JEditorPane) {
        pane.isEditable = false
        pane.isOpaque = false
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.border = JBUI.Borders.empty(0, 0, 0, 0)
    }

    private fun actionButton(
        text: String,
        action: () -> Unit,
    ): JButton =
        JButton(text).apply {
            addActionListener { action() }
        }

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
        }

    private fun summaryValueLabel(text: String): JLabel =
        JLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 7f)
        }

    private fun openResult(result: ComplexityResult) {
        val file = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(result.fileUrl) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun currentEditorFileUrl(): String? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.url

    private fun relativePath(filePath: String): String {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return filePath.replace('\\', '/')
        val normalized = filePath.replace('\\', '/')
        val prefix = "$basePath/"
        return if (normalized.startsWith(prefix)) normalized.removePrefix(prefix) else normalized
    }

    private fun fileName(filePath: String): String = filePath.substringAfterLast('/', filePath)

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
                            JBUI.Borders.empty(8, 10),
                        )
                    background = if (isSelected) list.selectionBackground else list.background
                    toolTipText = buildPoopTooltip(result.score)
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
                JLabel(relativePath(result.filePath)).apply {
                    foreground = if (isSelected) list.selectionForeground else secondaryTextColor()
                },
            )
            val strip =
                PoopRatingStrip(iconSize = 13, gap = 3).apply {
                    setScore(result.score, result.severity, isSelected)
                }
            root.add(textPanel, BorderLayout.CENTER)
            root.add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(strip, BorderLayout.NORTH)
                    add(
                        JLabel("${result.score}", SwingConstants.CENTER).apply {
                            foreground = if (isSelected) list.selectionForeground else secondaryTextColor()
                        },
                        BorderLayout.SOUTH,
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

private class LoadingOverlayPanel : JPanel(BorderLayout()) {
    private val label =
        JLabel("Loading...", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            foreground = JBColor(Color(0xF4F6F8), Color(0xE6E9ED))
            border = JBUI.Borders.empty(12, 18, 6, 18)
            horizontalAlignment = SwingConstants.CENTER
        }
    private val progressBar =
        JProgressBar().apply {
            isBorderPainted = false
            isStringPainted = true
            foreground = JBColor(Color(0xB58B57), Color(0xD0A873))
            background = JBColor(Color(0x58606B), Color(0x3A4048))
            preferredSize = Dimension(JBUI.scale(220), JBUI.scale(10))
        }

    init {
        isOpaque = false
        isVisible = false
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        add(label)
                        add(progressBar)
                    },
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.CENTER,
        )
        showIndeterminate("Loading...")
    }

    fun showIndeterminate(message: String) {
        label.text = message
        progressBar.isIndeterminate = true
        progressBar.isStringPainted = false
    }

    fun showProgress(
        message: String,
        processed: Int,
        total: Int,
    ) {
        label.text = message
        if (total <= 0) {
            progressBar.isIndeterminate = true
            progressBar.isStringPainted = false
            return
        }
        progressBar.isIndeterminate = false
        progressBar.isStringPainted = true
        progressBar.minimum = 0
        progressBar.maximum = total
        progressBar.value = processed.coerceIn(0, total)
        progressBar.string = "$processed / $total"
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor(Color(20, 24, 31, 165), Color(12, 14, 18, 185))
        g2.fillRect(0, 0, width, height)

        val boxWidth = minOf(width - JBUI.scale(40), JBUI.scale(260)).coerceAtLeast(JBUI.scale(180))
        val boxHeight = JBUI.scale(84)
        val x = ((width - boxWidth) / 2).coerceAtLeast(JBUI.scale(12))
        val y = ((height - boxHeight) / 2).coerceAtLeast(JBUI.scale(12))
        g2.color = JBColor(Color(0x2F353E), Color(0x23272D))
        g2.fillRoundRect(x, y, boxWidth, boxHeight, JBUI.scale(18), JBUI.scale(18))
        g2.color = JBColor(Color(0x4A5462), Color(0x3A414B))
        g2.drawRoundRect(x, y, boxWidth, boxHeight, JBUI.scale(18), JBUI.scale(18))
        g2.dispose()
    }
}

private class RadarChartPanel : JPanel() {
    private var result: ComplexityResult? = null
    private var aggregateValues: Map<FactorType, Double>? = null
    private var aggregateSeverity: Severity = Severity.GREEN

    init {
        preferredSize = Dimension(JBUI.scale(240), JBUI.scale(220))
        minimumSize = preferredSize
        isOpaque = false
    }

    fun setResult(value: ComplexityResult?) {
        result = value
        aggregateValues = null
        repaint()
    }

    fun setAggregate(
        values: Map<FactorType, Double>,
        severity: Severity,
    ) {
        result = null
        aggregateValues = values
        aggregateSeverity = severity
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val centerX = width / 2
        val centerY = height / 2 + JBUI.scale(8)
        val radius = min(width, height) / 2 - JBUI.scale(34)
        if (radius <= 0) {
            g2.dispose()
            return
        }

        val factors = FactorType.entries
        val levels = listOf(0.25, 0.5, 0.75, 1.0)

        g2.color = JBColor(Color(0xDFE5ED), Color(0x3B4250))
        levels.forEach { level ->
            val polygon = buildPolygon(centerX, centerY, radius * level, factors.size) { level }
            g2.drawPolygon(polygon)
        }

        factors.forEachIndexed { index, factor ->
            val angle = angleFor(index, factors.size)
            val endX = centerX + (cos(angle) * radius).toInt()
            val endY = centerY + (sin(angle) * radius).toInt()
            g2.drawLine(centerX, centerY, endX, endY)

            val labelX = centerX + (cos(angle) * (radius + JBUI.scale(18))).toInt()
            val labelY = centerY + (sin(angle) * (radius + JBUI.scale(18))).toInt()
            g2.color = JBColor(Color(0x55606D), Color(0xA5ADB8))
            val label = labelFor(factor)
            val labelWidth = g2.fontMetrics.stringWidth(label)
            g2.drawString(label, labelX - labelWidth / 2, labelY)
            g2.color = JBColor(Color(0xDFE5ED), Color(0x3B4250))
        }

        val polygonValues =
            result?.contributions?.associate { it.type to it.normalized }
                ?: aggregateValues
        val polygonSeverity =
            result?.severity
                ?: aggregateSeverity
        polygonValues?.let { values ->
            val polygon =
                buildPolygon(centerX, centerY, radius.toDouble(), factors.size) { factorIndex ->
                    values[factors[factorIndex]] ?: 0.0
                }
            val fill = severityColor(polygonSeverity, 80)
            val stroke = severityColor(polygonSeverity)
            g2.color = fill
            g2.fillPolygon(polygon)
            g2.color = stroke
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            g2.drawPolygon(polygon)
        }

        g2.dispose()
    }

    private fun buildPolygon(
        centerX: Int,
        centerY: Int,
        radius: Double,
        size: Int,
        valueProvider: (Int) -> Double,
    ): Polygon {
        val polygon = Polygon()
        repeat(size) { index ->
            val angle = angleFor(index, size)
            val scale = valueProvider(index).coerceIn(0.0, 1.0)
            val pointRadius = radius * scale
            polygon.addPoint(
                centerX + (cos(angle) * pointRadius).toInt(),
                centerY + (sin(angle) * pointRadius).toInt(),
            )
        }
        return polygon
    }

    private fun angleFor(
        index: Int,
        size: Int,
    ): Double = -Math.PI / 2 + 2.0 * Math.PI * index / size

    private fun labelFor(factor: FactorType): String =
        when (factor) {
            FactorType.SIZE -> "Size"
            FactorType.CONTROL_FLOW -> "Flow"
            FactorType.NESTING -> "Nest"
            FactorType.DOMAIN_COUPLING -> "Domain"
            FactorType.READABILITY -> "Read"
        }
}

private class PoopRatingStrip(
    iconSize: Int,
    gap: Int,
) : JComponent() {
    private val scaledIconSize = JBUI.scale(iconSize)
    private val scaledGap = JBUI.scale(gap)

    private var activeCount = 0
    private var severity = Severity.GREEN
    private var selected = false

    init {
        isOpaque = false
        preferredSize = Dimension((scaledIconSize * 5) + (scaledGap * 4), scaledIconSize + JBUI.scale(6))
        minimumSize = preferredSize
    }

    fun setScore(
        score: Int,
        severity: Severity,
        selected: Boolean = false,
    ) {
        activeCount = poopCountForScore(score)
        this.severity = severity
        this.selected = selected
        toolTipText = buildPoopTooltip(score)
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val iconHeight = scaledIconSize + JBUI.scale(2)
        var x = 0
        val y = ((height - iconHeight) / 2).coerceAtLeast(0)
        repeat(5) { index ->
            val active = index < activeCount
            val accent = poopAccentColor(severity)
            val fill =
                if (active) {
                    Color(accent.red, accent.green, accent.blue, if (selected) 220 else 255)
                } else {
                    poopMutedColor()
                }
            val outline =
                if (active) {
                    accent.darker()
                } else {
                    poopMutedColor().darker()
                }
            drawPoopIcon(g2, x, y, scaledIconSize, fill, outline)
            x += scaledIconSize + scaledGap
        }
        g2.dispose()
    }
}

private fun drawPoopIcon(
    graphics: Graphics2D,
    x: Int,
    y: Int,
    size: Int,
    fill: Color,
    outline: Color,
) {
    val baseWidth = (size * 0.78).toInt()
    val baseHeight = (size * 0.28).toInt()
    val middleWidth = (size * 0.62).toInt()
    val middleHeight = (size * 0.24).toInt()
    val topWidth = (size * 0.44).toInt()
    val topHeight = (size * 0.19).toInt()
    val dripWidth = (size * 0.15).toInt().coerceAtLeast(2)
    val dripHeight = (size * 0.23).toInt().coerceAtLeast(3)
    val tipHalfWidth = (size * 0.08).toInt().coerceAtLeast(1)

    val baseX = x + (size - baseWidth) / 2
    val baseY = y + (size * 0.58).toInt()
    val middleX = x + (size - middleWidth) / 2 - JBUI.scale(1)
    val middleY = y + (size * 0.36).toInt()
    val topX = x + (size - topWidth) / 2 + JBUI.scale(1)
    val topY = y + (size * 0.18).toInt()
    val tipX = topX + topWidth / 2 + JBUI.scale(1)
    val tipTop = y + JBUI.scale(1)
    val dripX = baseX + (baseWidth * 0.64).toInt()
    val dripY = baseY + baseHeight - JBUI.scale(2)

    fun fillBody() {
        graphics.fillRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
        graphics.fillOval(baseX - JBUI.scale(2), baseY + baseHeight / 3, baseHeight / 2, baseHeight / 2)
        graphics.fillOval(baseX + baseWidth - baseHeight / 3, baseY + baseHeight / 4, baseHeight / 2, baseHeight / 2)
        graphics.fillRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
        graphics.fillRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
        graphics.fillOval(dripX, dripY, dripWidth, dripHeight)
        graphics.fillPolygon(
            intArrayOf(tipX - tipHalfWidth, tipX, tipX + tipHalfWidth),
            intArrayOf(topY + topHeight / 3, tipTop, topY + topHeight / 3 + JBUI.scale(1)),
            3,
        )
    }

    graphics.color = fill
    fillBody()

    graphics.color = Color(255, 255, 255, 30)
    graphics.fillOval(topX + JBUI.scale(2), topY + JBUI.scale(2), (topWidth * 0.28).toInt().coerceAtLeast(2), (topHeight * 0.35).toInt().coerceAtLeast(2))

    graphics.color = outline
    graphics.stroke = BasicStroke(JBUI.scale(1).toFloat())
    graphics.drawRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
    graphics.drawOval(baseX - JBUI.scale(2), baseY + baseHeight / 3, baseHeight / 2, baseHeight / 2)
    graphics.drawOval(baseX + baseWidth - baseHeight / 3, baseY + baseHeight / 4, baseHeight / 2, baseHeight / 2)
    graphics.drawRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
    graphics.drawRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
    graphics.drawOval(dripX, dripY, dripWidth, dripHeight)
    graphics.drawPolyline(
        intArrayOf(tipX - tipHalfWidth, tipX, tipX + tipHalfWidth),
        intArrayOf(topY + topHeight / 3, tipTop, topY + topHeight / 3 + JBUI.scale(1)),
        3,
    )
}

private fun poopCountForScore(score: Int): Int {
    val clamped = score.coerceIn(0, 100)
    return if (clamped == 0) {
        0
    } else {
        ((clamped - 1) / 20) + 1
    }
}

private fun severityForScore(score: Int): Severity =
    when {
        score > 75 -> Severity.RED
        score > 50 -> Severity.ORANGE
        score > 25 -> Severity.YELLOW
        else -> Severity.GREEN
    }

private fun buildPoopTooltip(score: Int): String = "Poop ${poopCountForScore(score)}/5 | Raw $score/100"

private fun severityColor(
    severity: Severity,
    alpha: Int = 255,
): Color {
    val base =
        when (severity) {
            Severity.GREEN -> JBColor(Color(0x49A55E), Color(0x78C27A))
            Severity.YELLOW -> JBColor(Color(0xBF8B14), Color(0xE0B84B))
            Severity.ORANGE -> JBColor(Color(0xD96E10), Color(0xF08A24))
            Severity.RED -> JBColor(Color(0xCC4747), Color(0xF26D6D))
        }
    return Color(base.red, base.green, base.blue, alpha.coerceIn(0, 255))
}
