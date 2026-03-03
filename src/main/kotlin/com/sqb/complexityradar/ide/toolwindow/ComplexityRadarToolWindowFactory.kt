package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.services.AggregateBucket
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
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
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

    private val reanalyzeFileButton = actionButton("Reanalyze File") { service.reanalyzeCurrentFile() }
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
    private val projectRadarPanel = RadarChartPanel()
    private val projectNotesPane = JEditorPane("text/html", projectEmptyState())
    private val topFilesModel = DefaultListModel<ComplexityResult>()
    private val topFilesList = JBList(topFilesModel)

    private var hasLoadedOnce = false
    private var isViewRefreshing = false
    private var isAnalyzeProjectRunning = false
    private var preferredFileUrl: String? = null

    init {
        add(buildNorthPanel(), BorderLayout.NORTH)
        add(buildContentLayer(), BorderLayout.CENTER)

        preferredSize = Dimension(980, 720)
        configureCurrentPane()
        configureTopFilesList()
        refreshView()

        connection.subscribe(
            ComplexityResultListener.TOPIC,
            ComplexityResultListener {
                refreshView()
            },
        )
    }

    override fun refreshView() {
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
        refreshView()
    }

    private fun buildSnapshot(targetFileUrl: String?): FocusedViewSnapshot {
        val allResults = service.allResults()
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
        )
    }

    private fun applySnapshot(snapshot: FocusedViewSnapshot) {
        applyCurrentFile(snapshot.currentResult)
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
                    add(reanalyzeFileButton)
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
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(14, 16, 8, 16)
                    add(currentTitleLabel)
                    add(currentPoopStrip.apply { alignmentX = Component.LEFT_ALIGNMENT })
                    add(currentMetaLabel)
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
                    add(projectRadarPanel, BorderLayout.NORTH)
                    configureHtmlPane(projectNotesPane)
                    add(JBScrollPane(projectNotesPane), BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
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
        isAnalyzeProjectRunning = true
        loadingOverlay.showProgress("Queueing project scan...", 0, 0)
        updateLoadingUi()
        AppExecutorUtil.getAppExecutorService().execute {
            val queued =
                runCatching {
                    service.queueProjectAnalysis { processed, total ->
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || !isAnalyzeProjectRunning) {
                                return@invokeLater
                            }
                            loadingOverlay.showProgress("Queueing project scan...", processed, total)
                        }
                    }
                }.getOrDefault(0)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                if (queued <= 0) {
                    loadingOverlay.showIndeterminate("No analyzable files found.")
                }
                isAnalyzeProjectRunning = false
                updateLoadingUi()
            }
        }
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
        if (snapshot.projectResults.isEmpty()) {
            return projectEmptyState()
        }
        val topFiles =
            snapshot.topFiles.joinToString("<br/>") { result ->
                "<b>${html(fileName(result.filePath))}</b>  ${poopCountForScore(result.score)}/5  (${result.score})"
            }
        return """
            <html><body style='padding:8px;'>
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
)

private class ComplexityRadarToolWindowPanel(
    private val project: Project,
) : JPanel(BorderLayout()), RefreshableComplexityView {
    private val service = ComplexityRadarProjectService.getInstance(project)
    private val connection: MessageBusConnection = project.messageBus.connect(project)

    private val filterField = JBTextField()
    private val severityFilter = ComboBox(arrayOf("All", "GREEN", "YELLOW", "ORANGE", "RED"))
    private val queueModeFilter = ComboBox(arrayOf("All Files", "Changed First", "Changed Only"))

    private val scannedTile = MetricTile("Scanned", "files in scope")
    private val focusTile = MetricTile("Focus", "current lens")
    private val redTile = MetricTile("Red", "critical files")
    private val changedTile = MetricTile("Changed", "vcs touched")
    private val avgTile = MetricTile("Avg", "mean score")

    private val queueModel = DefaultListModel<QueueEntry>()
    private val queueList = JBList(queueModel)
    private val moduleModel = tableModel(arrayOf("Module", "Avg", "Worst", "Red", "Files"))
    private val packageModel = tableModel(arrayOf("Package", "Avg", "Worst", "Red", "Files"))
    private val moduleTable = JBTable(moduleModel)
    private val packageTable = JBTable(packageModel)

    private val queueTitleLabel = JLabel("Risk Queue")
    private val queuePoopStrip = PoopRatingStrip(iconSize = 18, gap = 5)
    private val queueMetaLabel = JLabel("Select a file to inspect its breakdown.")
    private val queueRadarPanel = RadarChartPanel()
    private val queueDetailsPane = JEditorPane("text/html", emptyStateHtml("Select a file to inspect details."))

    private val insightTitleLabel = JLabel("Insights")
    private val insightMetaLabel = JLabel("Select a module or package bucket.")
    private val insightDetailsPane = JEditorPane("text/html", emptyStateHtml("Select a bucket to inspect its cluster."))

    private val displayRenderer = DisplayTextRenderer()
    private val scoreRenderer = PoopScoreCellRenderer()
    private val refreshVersion = AtomicInteger(0)
    private val loadingOverlay = LoadingOverlayPanel()
    private val reanalyzeFileButton = actionButton("Reanalyze File") { service.reanalyzeCurrentFile() }
    private val analyzeProjectButton = actionButton("Analyze Project") { runAnalyzeProject() }
    private val exportButton = actionButton("Export") { runExport() }
    private var hasLoadedOnce = false
    private var isViewRefreshing = false
    private var isAnalyzeProjectRunning = false
    private var suppressAutoRefresh = false
    private var preferredQueueUrl: String? = null

    private var queueRows: List<QueueEntry> = emptyList()
    private var moduleRows: List<AggregateBucket> = emptyList()
    private var packageRows: List<AggregateBucket> = emptyList()

    init {
        add(buildNorthPanel(), BorderLayout.NORTH)
        add(buildContentLayer(), BorderLayout.CENTER)

        preferredSize = Dimension(1080, 760)
        configureQueueList()
        configureBucketTable(moduleTable, "Module") { moduleRows }
        configureBucketTable(packageTable, "Package") { packageRows }
        installFilters()
        installActions()
        refreshView()

        connection.subscribe(
            ComplexityResultListener.TOPIC,
            ComplexityResultListener {
                refreshView()
            },
        )
    }

    override fun refreshView() {
        val request = currentViewRequest()
        val refreshId = refreshVersion.incrementAndGet()
        isViewRefreshing = true
        if (!hasLoadedOnce) {
            loadingOverlay.showIndeterminate("Opening radar...")
        }
        updateLoadingUi()
        AppExecutorUtil.getAppExecutorService().execute {
            val snapshot =
                runCatching { buildSnapshot(request) }.getOrElse {
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

    private fun currentViewRequest(): ViewRequest =
        ViewRequest(
            query = filterField.text.trim(),
            severity = severityFilter.selectedItem?.toString()?.takeIf { it != "All" },
            queueMode = queueModeFilter.selectedItem?.toString() ?: "All Files",
            selectedQueueUrl = preferredQueueUrl ?: selectedQueueUrl(),
            selectedModuleName = selectedBucketName(moduleTable, moduleRows),
            selectedPackageName = selectedBucketName(packageTable, packageRows),
        )

    override fun revealFile(file: com.intellij.openapi.vfs.VirtualFile?) {
        preferredQueueUrl = file?.url
        resetFiltersForReveal()
        refreshView()
    }

    private fun buildSnapshot(request: ViewRequest): ViewSnapshot {
        val visibleResults = service.allResults()
        val changedUrls = service.changedResults().mapTo(linkedSetOf()) { it.fileUrl }
        val filteredResults = applyFilters(visibleResults, request.query, request.severity)
        return ViewSnapshot(
            visibleResults = visibleResults,
            changedUrls = changedUrls,
            filteredResults = filteredResults,
            queueEntries = buildQueueEntries(filteredResults, changedUrls, request.queueMode),
            selectedQueueUrl = request.selectedQueueUrl,
            moduleBuckets = service.groupedByModule(filteredResults),
            selectedModuleName = request.selectedModuleName,
            packageBuckets = service.groupedByPackage(filteredResults),
            selectedPackageName = request.selectedPackageName,
        )
    }

    private fun applySnapshot(snapshot: ViewSnapshot) {
        updateOverview(snapshot.visibleResults, snapshot.changedUrls, snapshot.filteredResults)
        updateQueue(snapshot.queueEntries, snapshot.selectedQueueUrl)
        if (preferredQueueUrl != null && queueRows.any { it.result.fileUrl == preferredQueueUrl }) {
            preferredQueueUrl = null
        }
        updateModuleBuckets(snapshot.moduleBuckets, snapshot.selectedModuleName)
        updatePackageBuckets(snapshot.packageBuckets, snapshot.selectedPackageName)
        hasLoadedOnce = true
        isViewRefreshing = false
        updateLoadingUi()

        if (queueRows.isEmpty()) {
            queueTitleLabel.text = "Risk Queue"
            queuePoopStrip.setScore(0, Severity.GREEN)
            queueMetaLabel.text = "No files match the current lens."
            queueRadarPanel.setResult(null)
            queueDetailsPane.text = emptyStateHtml("No files match the current filter, severity, and scope.")
        }
        if (moduleRows.isEmpty() && packageRows.isEmpty()) {
            insightTitleLabel.text = "Insights"
            insightMetaLabel.text = "No module or package clusters are available."
            insightDetailsPane.text = emptyStateHtml("No grouped insights are available for the current lens.")
        }
    }

    private fun buildNorthPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(buildOverviewPanel(), BorderLayout.NORTH)
            add(buildLensPanel(), BorderLayout.SOUTH)
        }

    private fun buildContentLayer(): JComponent =
        JPanel().apply {
            layout = OverlayLayout(this)
            add(buildCenterPanel().apply {
                alignmentX = 0.5f
                alignmentY = 0.5f
            })
            add(loadingOverlay.apply {
                alignmentX = 0.5f
                alignmentY = 0.5f
            })
        }

    private fun buildOverviewPanel(): JComponent =
        JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            add(buildMetricStrip(), BorderLayout.WEST)
            add(buildActionPanel(), BorderLayout.EAST)
        }

    private fun buildMetricStrip(): JComponent =
        JPanel(GridLayout(1, 5, JBUI.scale(8), 0)).apply {
            add(scannedTile)
            add(focusTile)
            add(redTile)
            add(changedTile)
            add(avgTile)
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(88))
        }

    private fun buildActionPanel(): JComponent =
        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(reanalyzeFileButton)
            add(analyzeProjectButton)
            add(exportButton)
        }

    private fun buildLensPanel(): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyTop(10)
            add(JLabel("Lens: "))
            add(filterField)
            add(JLabel("  Severity: "))
            add(severityFilter)
            add(JLabel("  Queue: "))
            add(queueModeFilter)
        }

    private fun buildCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        tabs.addTab("Queue", buildQueueTab())
        tabs.addTab("Insights", buildInsightsTab())
        return tabs
    }

    private fun buildQueueTab(): JComponent {
        val splitPane =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildQueueListPanel(), buildQueueDetailsPanel()).apply {
                border = JBUI.Borders.empty(0, 10, 10, 10)
                resizeWeight = 0.58
                dividerSize = JBUI.scale(8)
            }
        return splitPane
    }

    private fun buildQueueListPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JLabel("Hot Risk Queue").apply {
                    font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                    border = JBUI.Borders.empty(10, 12, 6, 12)
                },
                BorderLayout.NORTH,
            )
            queueList.emptyText.text = "No files match the current lens."
            add(JBScrollPane(queueList), BorderLayout.CENTER)
        }

    private fun buildQueueDetailsPanel(): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(10, 12, 6, 12)
                    add(queueTitleLabel.apply { font = font.deriveFont(Font.BOLD, font.size2D + 2f) })
                    add(queuePoopStrip.apply { alignmentX = Component.LEFT_ALIGNMENT })
                    add(queueMetaLabel.apply { foreground = secondaryTextColor() })
                },
                BorderLayout.NORTH,
            )
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(0, 12, 12, 12)
                    add(queueRadarPanel, BorderLayout.NORTH)
                    configureHtmlPane(queueDetailsPane)
                    add(JBScrollPane(queueDetailsPane), BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

    private fun buildInsightsTab(): JComponent {
        val leftTabs = JBTabbedPane()
        leftTabs.addTab("By Module", tablePanel(moduleTable, "Modules in the current lens"))
        leftTabs.addTab("By Package", tablePanel(packageTable, "Packages in the current lens"))

        return JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            leftTabs,
            JPanel(BorderLayout()).apply {
                border = cardBorder()
                add(
                    JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(10, 12, 6, 12)
                        add(insightTitleLabel.apply { font = font.deriveFont(Font.BOLD, font.size2D + 2f) }, BorderLayout.NORTH)
                        add(insightMetaLabel.apply { foreground = secondaryTextColor() }, BorderLayout.SOUTH)
                    },
                    BorderLayout.NORTH,
                )
                add(
                    JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(0, 12, 12, 12)
                        configureHtmlPane(insightDetailsPane)
                        add(JBScrollPane(insightDetailsPane), BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER,
                )
            },
        ).apply {
            border = JBUI.Borders.empty(0, 10, 10, 10)
            resizeWeight = 0.58
            dividerSize = JBUI.scale(8)
        }
    }

    private fun tablePanel(
        table: JBTable,
        title: String,
    ): JComponent =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(
                JLabel(title).apply {
                    font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                    border = JBUI.Borders.empty(10, 12, 6, 12)
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(table).apply {
                    border = JBUI.Borders.empty(0, 0, 0, 0)
                },
                BorderLayout.CENTER,
            )
        }

    private fun configureQueueList() {
        queueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        queueList.visibleRowCount = -1
        queueList.fixedCellHeight = JBUI.scale(104)
        queueList.cellRenderer = RiskCardRenderer()
        queueList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) {
                return@addListSelectionListener
            }
            updateQueueSelection(queueList.selectedValue)
        }
        queueList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount != 2) {
                        return
                    }
                    queueList.selectedValue?.let { openResult(it.result) }
                }
            },
        )
    }

    private fun configureBucketTable(
        table: JBTable,
        bucketType: String,
        rowsProvider: () -> List<AggregateBucket>,
    ) {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        table.rowHeight = JBUI.scale(26)
        table.columnModel.getColumn(0).cellRenderer = displayRenderer
        table.columnModel.getColumn(2).cellRenderer = scoreRenderer
        table.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting) {
                return@addListSelectionListener
            }
            val bucket = selectedBucket(table, rowsProvider()) ?: return@addListSelectionListener
            insightTitleLabel.text = "$bucketType Insight"
            insightMetaLabel.text = "${bucket.fileCount} files | worst ${poopCountForScore(bucket.maxScore)}/5 | red ${bucket.redCount}"
            insightDetailsPane.text = renderBucketDetails(bucketType, bucket)
        }
    }

    private fun installFilters() {
        filterField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = refreshIfAllowed()

                override fun removeUpdate(e: DocumentEvent) = refreshIfAllowed()

                override fun changedUpdate(e: DocumentEvent) = refreshIfAllowed()
            },
        )
        severityFilter.addActionListener { refreshIfAllowed() }
        queueModeFilter.addActionListener { refreshIfAllowed() }
    }

    private fun installActions() {
        configureHtmlPane(queueDetailsPane)
        configureHtmlPane(insightDetailsPane)
    }

    private fun updateOverview(
        allResults: List<ComplexityResult>,
        changedUrls: Set<String>,
        filteredResults: List<ComplexityResult>,
    ) {
        val average = allResults.map { it.score }.average().takeIf { !it.isNaN() } ?: 0.0
        scannedTile.update(allResults.size.toString())
        focusTile.update(filteredResults.size.toString())
        redTile.update(allResults.count { it.severity == Severity.RED }.toString())
        changedTile.update(changedUrls.size.toString())
        avgTile.update(String.format("%.1f", average))
    }

    private fun updateQueue(
        entries: List<QueueEntry>,
        selectedFileUrl: String?,
    ) {
        queueRows = entries
        queueModel.removeAllElements()
        entries.forEach(queueModel::addElement)

        val selectedIndex = entries.indexOfFirst { it.result.fileUrl == selectedFileUrl }
        when {
            selectedIndex >= 0 -> queueList.selectedIndex = selectedIndex
            entries.isNotEmpty() -> queueList.selectedIndex = 0
        }
    }

    private fun updateModuleBuckets(
        buckets: List<AggregateBucket>,
        selectedName: String?,
    ) {
        moduleRows = buckets
        moduleModel.rowCount = 0
        moduleRows.forEach { bucket ->
            moduleModel.addRow(
                arrayOf(
                    bucketNameValue(bucket.name),
                    String.format("%.1f", bucket.averageScore),
                    bucket.maxScore,
                    bucket.redCount,
                    bucket.fileCount,
                ),
            )
        }
        restoreTableSelection(moduleTable, moduleRows, selectedName)
    }

    private fun updatePackageBuckets(
        buckets: List<AggregateBucket>,
        selectedName: String?,
    ) {
        packageRows = buckets
        packageModel.rowCount = 0
        packageRows.forEach { bucket ->
            packageModel.addRow(
                arrayOf(
                    bucketNameValue(bucket.name),
                    String.format("%.1f", bucket.averageScore),
                    bucket.maxScore,
                    bucket.redCount,
                    bucket.fileCount,
                ),
            )
        }
        restoreTableSelection(packageTable, packageRows, selectedName)
    }

    private fun updateQueueSelection(entry: QueueEntry?) {
        if (entry == null) {
            queueTitleLabel.text = "Risk Queue"
            queuePoopStrip.setScore(0, Severity.GREEN)
            queueMetaLabel.text = "Select a file to inspect its breakdown."
            queueRadarPanel.setResult(null)
            queueDetailsPane.text = emptyStateHtml("Select a file to inspect details.")
            return
        }
        val result = entry.result
        queueTitleLabel.text = fileName(result.filePath)
        queuePoopStrip.setScore(result.score, result.severity)
        queueMetaLabel.text =
            buildString {
                append(relativePath(result.filePath))
                append("  |  Poop ")
                append(poopCountForScore(result.score))
                append("/5")
                append("  |  Raw ")
                append(result.score)
                append("  |  ")
                append(result.severity.label)
                if (entry.changed) {
                    append("  |  Changed")
                }
            }
        queueRadarPanel.setResult(result)
        queueDetailsPane.text = renderResultDetails(entry)
    }

    private fun applyFilters(
        results: List<ComplexityResult>,
        query: String,
        severity: String?,
    ): List<ComplexityResult> {
        return results.filter { result ->
            (query.isBlank() || relativePath(result.filePath).contains(query, ignoreCase = true)) &&
                (severity == null || result.severity.name == severity)
        }
    }

    private fun buildQueueEntries(
        filteredResults: List<ComplexityResult>,
        changedUrls: Set<String>,
        queueMode: String,
    ): List<QueueEntry> {
        val baseEntries = filteredResults.map { result -> QueueEntry(result, result.fileUrl in changedUrls) }
        val scopedEntries =
            when (queueMode) {
                "Changed Only" -> baseEntries.filter { it.changed }
                else -> baseEntries
            }
        return when (queueMode) {
            "Changed First" ->
                scopedEntries.sortedWith(
                    compareByDescending<QueueEntry> { it.changed }
                        .thenByDescending { it.result.priority }
                        .thenByDescending { it.result.score }
                        .thenBy { it.result.filePath },
                )

            else ->
                scopedEntries.sortedWith(
                    compareByDescending<QueueEntry> { it.result.priority }
                        .thenByDescending { it.result.score }
                        .thenBy { it.result.filePath },
                )
        }
    }

    private fun selectedQueueUrl(): String? = queueList.selectedValue?.result?.fileUrl

    private fun selectedBucketName(
        table: JBTable,
        rows: List<AggregateBucket>,
    ): String? = selectedBucket(table, rows)?.name

    private fun restoreTableSelection(
        table: JBTable,
        rows: List<AggregateBucket>,
        selectedName: String?,
    ) {
        if (rows.isEmpty()) {
            table.clearSelection()
            return
        }
        val index = rows.indexOfFirst { it.name == selectedName }.takeIf { it >= 0 } ?: 0
        val viewIndex = table.convertRowIndexToView(index)
        table.selectionModel.setSelectionInterval(viewIndex, viewIndex)
    }

    private fun selectedBucket(
        table: JBTable,
        rows: List<AggregateBucket>,
    ): AggregateBucket? {
        val viewRow = table.selectedRow
        if (viewRow < 0) {
            return null
        }
        val row = table.convertRowIndexToModel(viewRow)
        return rows.getOrNull(row)
    }

    private fun renderResultDetails(entry: QueueEntry): String {
        val result = entry.result
        val topFactors = result.contributions.take(3)
        val hotspot = result.hotspots.firstOrNull()
        val recommendation = hotspot?.recommendation ?: "Split the file by responsibility and flatten nested control flow."
        return buildString {
            append("<html><body style='padding:6px;'>")
            append("<b>Poop Rating</b><br/>")
            append("${poopCountForScore(result.score)} / 5 (raw ${result.score})")
            append("<br/><br/>")
            append("<b>Score Breakdown</b><br/>")
            append(
                result.contributions.joinToString("<br/>") {
                    "${html(it.type.displayName)}: ${(it.weightedScore * 100).toInt()} points (${(it.normalized * 100).toInt()}%)"
                },
            )
            append("<br/><br/><b>Why It Is High</b><br/>")
            append(
                topFactors.joinToString("<br/>") {
                    "${html(it.type.displayName)} is adding pressure because ${html(it.explanation)}"
                },
            )
            if (result.hotspots.isNotEmpty()) {
                append("<br/><br/><b>Hotspot Methods</b><br/>")
                append(
                    result.hotspots.take(4).joinToString("<br/>") {
                        "${html(it.methodName)} @ line ${it.line}: ${it.score} (${html(it.severity.name)})"
                    },
                )
            }
            append("<br/><br/><b>Suggested Refactor Moves</b><br/>")
            append(html(recommendation))
            if (entry.changed) {
                append("<br/><br/><b>Delivery Signal</b><br/>This file is part of the current changed set, so it is a good candidate for immediate cleanup.")
            }
            append("</body></html>")
        }
    }

    private fun renderBucketDetails(
        bucketType: String,
        bucket: AggregateBucket,
    ): String =
        buildString {
            append("<html><body style='padding:6px;'>")
            append("<b>")
            append(html(bucketType))
            append(": ")
            append(html(bucket.name))
            append("</b><br/>")
            append("Average <b>${String.format("%.1f", bucket.averageScore)}</b>")
            append(" | Worst <b>${poopCountForScore(bucket.maxScore)}/5</b> (raw ${bucket.maxScore})")
            append(" | Red <b>${bucket.redCount}</b>")
            append(" | Files <b>${bucket.fileCount}</b><br/><br/>")
            append("<b>Top Files</b><br/>")
            append(
                bucket.topResults.joinToString("<br/>") { result ->
                    "${html(relativePath(result.filePath))} - ${poopCountForScore(result.score)}/5 (raw ${result.score}, ${html(result.severity.name)})"
                },
            )
            append("</body></html>")
        }

    private fun openResult(result: ComplexityResult) {
        val file = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(result.fileUrl) ?: return
        OpenFileDescriptor(project, file).navigate(true)
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

    private fun refreshIfAllowed() {
        if (!suppressAutoRefresh) {
            refreshView()
        }
    }

    private fun resetFiltersForReveal() {
        suppressAutoRefresh = true
        try {
            if (filterField.text.isNotBlank()) {
                filterField.text = ""
            }
            if (severityFilter.selectedIndex != 0) {
                severityFilter.selectedIndex = 0
            }
            if (queueModeFilter.selectedIndex != 0) {
                queueModeFilter.selectedIndex = 0
            }
        } finally {
            suppressAutoRefresh = false
        }
    }

    private fun runAnalyzeProject() {
        if (isAnalyzeProjectRunning) {
            return
        }
        isAnalyzeProjectRunning = true
        loadingOverlay.showProgress("Queueing project scan...", 0, 0)
        updateLoadingUi()
        AppExecutorUtil.getAppExecutorService().execute {
            val queued =
                runCatching {
                    service.queueProjectAnalysis { processed, total ->
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || !isAnalyzeProjectRunning) {
                                return@invokeLater
                            }
                            loadingOverlay.showProgress("Queueing project scan...", processed, total)
                        }
                    }
                }.getOrDefault(0)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                if (queued <= 0) {
                    loadingOverlay.showProgress("No analyzable files found.", 0, 0)
                }
                isAnalyzeProjectRunning = false
                updateLoadingUi()
            }
        }
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

    private fun displayPathValue(filePath: String): DisplayText {
        val relative = relativePath(filePath)
        return DisplayText(shortenMiddle(relative, 76), filePath)
    }

    private fun bucketNameValue(name: String): DisplayText = DisplayText(shortenMiddle(name, 48), name)

    private fun relativePath(filePath: String): String {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return filePath.replace('\\', '/')
        val normalized = filePath.replace('\\', '/')
        val prefix = "$basePath/"
        return if (normalized.startsWith(prefix)) {
            normalized.removePrefix(prefix)
        } else {
            normalized
        }
    }

    private fun fileName(filePath: String): String = filePath.substringAfterLast('/', filePath)

    private fun shortenMiddle(
        value: String,
        maxLength: Int,
    ): String {
        if (value.length <= maxLength) {
            return value
        }
        val head = maxLength / 2 - 2
        val tail = maxLength - head - 3
        return value.take(head) + "..." + value.takeLast(tail)
    }

    private fun html(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun emptyStateHtml(message: String): String =
        "<html><body style='padding:6px;color:#8f96a3;'>${html(message)}</body></html>"

    private fun secondaryTextColor(): Color = JBColor(Color(0x6F7885), Color(0x9097A0))

    private fun cardBorder() =
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(0xD9DEE7), Color(0x373D4A))),
            JBUI.Borders.empty(0),
        )

    private fun tableModel(columns: Array<String>): DefaultTableModel =
        object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(
                row: Int,
                column: Int,
            ): Boolean = false
        }

    private inner class RiskCardRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val entry = value as? QueueEntry ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val result = entry.result
            val root =
                JPanel(BorderLayout()).apply {
                    border =
                        BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, severityColor(result.severity)),
                            BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(JBColor(Color(0xD9DEE7), Color(0x373D4A))),
                                JBUI.Borders.empty(10, 12),
                            ),
                        )
                    background = if (isSelected) list.selectionBackground else list.background
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                    toolTipText = buildPoopTooltip(result.score)
                }

            val center =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }

            val title = JLabel(fileName(result.filePath)).apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                foreground = root.foreground
            }
            val subtitle = JLabel(shortenMiddle(relativePath(result.filePath), 72)).apply {
                foreground = if (isSelected) root.foreground else secondaryTextColor()
            }
            val factors = JLabel(renderFactorSummary(result)).apply {
                foreground = if (isSelected) root.foreground else JBColor(Color(0x4E5968), Color(0xA9B0BB))
            }
            val signal = JLabel(renderSignalLine(entry)).apply {
                foreground = if (isSelected) root.foreground else secondaryTextColor()
            }

            center.add(title)
            center.add(subtitle)
            center.add(factors)
            center.add(signal)

            val scorePanel =
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    preferredSize = Dimension(JBUI.scale(142), 0)
                }
            val poopStrip =
                PoopRatingStrip(iconSize = 14, gap = 3).apply {
                    setScore(result.score, result.severity, isSelected)
                    alignmentX = Component.CENTER_ALIGNMENT
                }
            val scoreLabel = JLabel("${result.score} / 100", SwingConstants.CENTER).apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                foreground = root.foreground
            }
            val severityLabel = JLabel("${result.severity.name}  |  ${poopCountForScore(result.score)}/5", SwingConstants.CENTER).apply {
                foreground = if (isSelected) root.foreground else severityColor(result.severity)
            }
            scorePanel.add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(poopStrip)
                    add(scoreLabel)
                    add(severityLabel)
                },
                BorderLayout.CENTER,
            )

            root.add(center, BorderLayout.CENTER)
            root.add(scorePanel, BorderLayout.EAST)
            return root
        }

        private fun renderFactorSummary(result: ComplexityResult): String =
            result.contributions
                .take(3)
                .joinToString("    ") { contribution ->
                    "${contribution.type.displayName} +${(contribution.weightedScore * 100).toInt()}"
                }

        private fun renderSignalLine(entry: QueueEntry): String {
            val hotspot = entry.result.hotspots.firstOrNull()?.let { "${it.methodName} @ ${it.score}" } ?: "No hotspot methods"
            return if (entry.changed) {
                "Changed now  |  $hotspot"
            } else {
                hotspot
            }
        }
    }
}

private data class QueueEntry(
    val result: ComplexityResult,
    val changed: Boolean,
)

private data class ViewRequest(
    val query: String,
    val severity: String?,
    val queueMode: String,
    val selectedQueueUrl: String?,
    val selectedModuleName: String?,
    val selectedPackageName: String?,
)

private data class ViewSnapshot(
    val visibleResults: List<ComplexityResult>,
    val changedUrls: Set<String>,
    val filteredResults: List<ComplexityResult>,
    val queueEntries: List<QueueEntry>,
    val selectedQueueUrl: String?,
    val moduleBuckets: List<AggregateBucket>,
    val selectedModuleName: String?,
    val packageBuckets: List<AggregateBucket>,
    val selectedPackageName: String?,
)

private data class DisplayText(
    val text: String,
    val tooltip: String = text,
) : Comparable<DisplayText> {
    override fun compareTo(other: DisplayText): Int = text.compareTo(other.text, ignoreCase = true)

    override fun toString(): String = text
}

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

private class MetricTile(
    title: String,
    caption: String,
) : JPanel(BorderLayout()) {
    private val valueLabel = JLabel("0")

    init {
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0xD9DEE7), Color(0x373D4A))),
                JBUI.Borders.empty(8, 10),
            )
        add(
            JLabel(title).apply {
                foreground = JBColor(Color(0x5A6573), Color(0xA4ACB8))
            },
            BorderLayout.NORTH,
        )
        add(
            valueLabel.apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 8f)
            },
            BorderLayout.CENTER,
        )
        add(
            JLabel(caption).apply {
                foreground = JBColor(Color(0x7A8390), Color(0x8F96A3))
            },
            BorderLayout.SOUTH,
        )
    }

    fun update(value: String) {
        valueLabel.text = value
    }
}

private class SeverityDistributionBar : JPanel(BorderLayout()) {
    private val bar = SeverityBarCanvas()
    private val labels =
        mapOf(
            Severity.GREEN to legendLabel(),
            Severity.YELLOW to legendLabel(),
            Severity.ORANGE to legendLabel(),
            Severity.RED to legendLabel(),
        )

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 8, 10)
        add(bar, BorderLayout.CENTER)
        add(
            JPanel(GridLayout(1, 4, JBUI.scale(6), 0)).apply {
                isOpaque = false
                labels.forEach { (severity, label) ->
                    add(
                        JPanel(BorderLayout()).apply {
                            isOpaque = false
                            add(
                                JLabel(severity.name, SwingConstants.LEFT).apply {
                                    foreground = severityColor(severity)
                                },
                                BorderLayout.WEST,
                            )
                            add(label, BorderLayout.EAST)
                        },
                    )
                }
            },
            BorderLayout.SOUTH,
        )
        preferredSize = Dimension(JBUI.scale(260), JBUI.scale(76))
    }

    fun update(counts: Map<Severity, Int>) {
        bar.counts = counts
        labels.forEach { (severity, label) ->
            label.text = counts[severity]?.toString() ?: "0"
        }
        repaint()
    }

    private fun legendLabel(): JLabel =
        JLabel("0", SwingConstants.RIGHT).apply {
            foreground = JBColor(Color(0x5A6573), Color(0xA4ACB8))
        }
}

private class SeverityBarCanvas : JComponent() {
    var counts: Map<Severity, Int> = emptyMap()

    init {
        preferredSize = Dimension(JBUI.scale(240), JBUI.scale(22))
        minimumSize = preferredSize
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor(Color(0xEEF1F5), Color(0x2D323C))
        g2.fillRoundRect(0, 0, width, height, height, height)

        val total = counts.values.sum()
        if (total <= 0) {
            g2.dispose()
            return
        }

        var x = 0
        Severity.entries.forEach { severity ->
            val count = counts[severity] ?: 0
            if (count <= 0) {
                return@forEach
            }
            val segmentWidth =
                if (severity == Severity.entries.last()) {
                    width - x
                } else {
                    (width * (count.toDouble() / total)).toInt().coerceAtLeast(1)
                }
            g2.color = severityColor(severity)
            g2.fillRoundRect(x, 0, segmentWidth, height, height, height)
            x += segmentWidth
        }
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

private class DisplayTextRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: javax.swing.JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
        val display = value as? DisplayText
        component.text = display?.text ?: value?.toString().orEmpty()
        component.toolTipText = display?.tooltip ?: component.text
        return component
    }
}

private class PoopScoreCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: javax.swing.JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val rawScore =
            when (value) {
                is Number -> value.toInt()
                else -> value?.toString()?.toIntOrNull() ?: 0
            }
        val panel =
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = if (isSelected) table.selectionBackground else table.background
                border = JBUI.Borders.empty(0, 6)
                toolTipText = buildPoopTooltip(rawScore)
            }
        panel.add(
            PoopRatingStrip(iconSize = 11, gap = 2).apply {
                setScore(rawScore, severityForScore(rawScore), isSelected)
            },
            BorderLayout.CENTER,
        )
        return panel
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
