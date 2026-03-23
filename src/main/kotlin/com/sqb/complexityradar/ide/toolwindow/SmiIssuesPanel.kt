package com.bril.code_radar.ide.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.bril.code_radar.core.model.ComplexityResult
import com.bril.code_radar.core.model.Hotspot
import com.bril.code_radar.core.model.Severity
import com.bril.code_radar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel

internal class SmiIssuesPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    enum class SeverityTier { CRITICAL, WARNING, INFO }

    sealed class IssueItem {
        data class FileHeader(val result: ComplexityResult) : IssueItem()
        data class HotspotRow(val hotspot: Hotspot, val fileUrl: String, val tier: SeverityTier) : IssueItem()
        object Empty    : IssueItem()
        object NoFilter : IssueItem()
    }

    private val activeFilters = mutableSetOf(SeverityTier.CRITICAL, SeverityTier.WARNING, SeverityTier.INFO)

    private val chipCritical = FilterChip("🔥 Critical (0)", SeverityTier.CRITICAL, UiThemeTokens.severityCritical)
    private val chipWarning  = FilterChip("⚠ Warning (0)",  SeverityTier.WARNING,  UiThemeTokens.severityWarning)
    private val chipInfo     = FilterChip("ℹ Info (0)",      SeverityTier.INFO,     UiThemeTokens.accentPrimary)

    private val listModel = DefaultListModel<IssueItem>()
    private val issueList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = -1
        cellRenderer = IssueRenderer()
    }
    private val detailPanel = IssueDetailPanel()

    private var cachedResults: List<ComplexityResult> = emptyList()
    private var cachedScope: DashboardScope = DashboardScope.PROJECT
    private var cachedCurrent: ComplexityResult? = null

    init {
        isOpaque = false

        val filterRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(6, 12)
            add(chipCritical)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(chipWarning)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(chipInfo)
            add(Box.createHorizontalGlue())
        }

        issueList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            when (val item = issueList.selectedValue) {
                is IssueItem.FileHeader -> detailPanel.showFile(item)
                is IssueItem.HotspotRow -> detailPanel.showHotspot(item)
                else -> detailPanel.showPlaceholder()
            }
        }

        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val item = issueList.selectedValue ?: return
                when (item) {
                    is IssueItem.FileHeader -> navigateToFile(item.result.fileUrl)
                    is IssueItem.HotspotRow -> navigateToLine(item.fileUrl, item.hotspot.line)
                    else -> Unit
                }
            }
        })

        val listPane = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(filterRow, BorderLayout.NORTH)
            add(JBScrollPane(issueList).apply { border = null }, BorderLayout.CENTER)
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, listPane, detailPanel).apply {
            isOpaque = false
            border = null
            dividerSize = JBUI.scale(4)
            resizeWeight = 0.65
        }

        add(splitPane, BorderLayout.CENTER)
    }

    fun update(
        results: List<ComplexityResult>,
        scope: DashboardScope,
        currentResult: ComplexityResult?,
    ) {
        cachedResults = results
        cachedScope   = scope
        cachedCurrent = currentResult
        detailPanel.showPlaceholder()
        rebuildList()
    }

    private fun rebuildList() {
        listModel.removeAllElements()

        val effective = when (cachedScope) {
            DashboardScope.PROJECT      -> cachedResults
            DashboardScope.CURRENT_FILE -> listOfNotNull(cachedCurrent)
        }

        if (cachedScope == DashboardScope.CURRENT_FILE && cachedCurrent == null) {
            issueList.emptyText.text = "Open a file to analyze"
            listModel.addElement(IssueItem.Empty)
            return
        }

        if (activeFilters.isEmpty()) {
            listModel.addElement(IssueItem.NoFilter)
            return
        }

        var criticalCount = 0; var warningCount = 0; var infoCount = 0
        effective.forEach { r ->
            val tier = r.severity.toTier()
            when (tier) {
                SeverityTier.CRITICAL -> criticalCount++
                SeverityTier.WARNING  -> warningCount++
                SeverityTier.INFO     -> infoCount++
            }
            if (tier in activeFilters) {
                listModel.addElement(IssueItem.FileHeader(r))
                r.hotspots.forEach { h -> listModel.addElement(IssueItem.HotspotRow(h, r.fileUrl, tier)) }
            }
        }

        chipCritical.label = "🔥 Critical ($criticalCount)"
        chipWarning.label  = "⚠ Warning ($warningCount)"
        chipInfo.label     = "ℹ Info ($infoCount)"

        if (listModel.isEmpty) listModel.addElement(IssueItem.Empty)
    }

    private fun Severity.toTier(): SeverityTier = when (this) {
        Severity.RED    -> SeverityTier.CRITICAL
        Severity.ORANGE -> SeverityTier.WARNING
        Severity.YELLOW -> SeverityTier.INFO
        Severity.GREEN  -> SeverityTier.INFO
    }

    private fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    private inner class FilterChip(
        initialLabel: String,
        private val tier: SeverityTier,
        private val color: Color,
    ) : JPanel(BorderLayout()) {
        private val textLabel = JLabel(initialLabel).apply {
            isOpaque = false
            font = font.deriveFont(Font.BOLD).deriveFont(font.size2D - 1f)
            foreground = color
        }
        private var active = true

        var label: String
            get() = textLabel.text
            set(v) { textLabel.text = v }

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(3, 8)
            add(textLabel, BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (tier in activeFilters) activeFilters.remove(tier) else activeFilters.add(tier)
                    active = tier in activeFilters
                    repaint()
                    rebuildList()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(10)
            g2.color = withAlpha(color, if (active) 60 else 25)
            g2.fillRoundRect(0, 0, width, height, r, r)
            g2.color = withAlpha(color, if (active) 180 else 75)
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private fun navigateToFile(url: String) {
        VirtualFileManager.getInstance().findFileByUrl(url)
            ?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    private fun navigateToLine(url: String, line: Int) {
        VirtualFileManager.getInstance().findFileByUrl(url)
            ?.let { OpenFileDescriptor(project, it, (line - 1).coerceAtLeast(0), 0).navigate(true) }
    }

    private fun fileName(path: String) = path.substringAfterLast('/', path)

    private fun relativePath(path: String): String {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return path
        val norm = path.replace('\\', '/')
        return if (norm.startsWith("$base/")) norm.removePrefix("$base/") else norm
    }

    private fun shortenMiddle(v: String, max: Int): String {
        if (v.length <= max) return v
        val h = max / 2 - 2; val t = max - h - 3
        return v.take(h) + "..." + v.takeLast(t)
    }

    private inner class IssueRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component = when (val item = value as? IssueItem) {
            is IssueItem.FileHeader  -> buildFileHeader(item.result, isSelected, list)
            is IssueItem.HotspotRow  -> buildHotspotRow(item, isSelected, list)
            is IssueItem.Empty       -> buildMessage("No issues found.", isSelected, list)
            is IssueItem.NoFilter    -> buildMessage("No severity filter selected.", isSelected, list)
            null                     -> buildMessage("", isSelected, list)
        }

        private fun buildFileHeader(result: ComplexityResult, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else UiThemeTokens.bgCard
                val tierColor = result.severity.toTier().color()
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, tierColor),
                        BorderFactory.createLineBorder(UiThemeTokens.borderDefault),
                    ),
                    JBUI.Borders.empty(7, 10),
                )
                val nameLabel = JLabel(fileName(result.filePath)).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textPrimary
                }
                val pathLabel = JLabel(shortenMiddle(relativePath(result.filePath), 42)).apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                    font = font.deriveFont(font.size2D - 1f)
                }
                val center = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(nameLabel); add(pathLabel)
                }
                add(center, BorderLayout.CENTER)
                add(buildScoreBadge(result.score, result.severity.toTier()), BorderLayout.EAST)
            }

        private fun buildHotspotRow(item: IssueItem.HotspotRow, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(5, 24, 5, 10)
                add(JLabel("${item.tier.icon()}  ${item.hotspot.methodName}").apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textPrimary
                }, BorderLayout.CENTER)
                add(JLabel("L${item.hotspot.line}  ·  ${item.hotspot.score}").apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                    font = font.deriveFont(font.size2D - 0.5f)
                    border = JBUI.Borders.emptyLeft(8)
                }, BorderLayout.EAST)
            }

        private fun buildMessage(msg: String, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(8, 12)
                add(JLabel(msg).apply { foreground = UiThemeTokens.textSecondary }, BorderLayout.CENTER)
            }

        private fun buildScoreBadge(score: Int, tier: SeverityTier): JPanel {
            val color = tier.color()
            val label = JLabel(score.toString()).apply {
                font = font.deriveFont(Font.BOLD).deriveFont(font.size2D - 1f)
                foreground = color
                isOpaque = false
            }
            return object : JPanel(BorderLayout()) {
                init {
                    isOpaque = false
                    border = JBUI.Borders.empty(2, 7)
                    add(label, BorderLayout.CENTER)
                }
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val r = JBUI.scale(10)
                    g2.color = withAlpha(color, 30)
                    g2.fillRoundRect(0, 0, width, height, r, r)
                    g2.color = withAlpha(color, 120)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
                    g2.dispose()
                }
            }
        }

        private fun SeverityTier.color(): Color = when (this) {
            SeverityTier.CRITICAL -> UiThemeTokens.severityCritical
            SeverityTier.WARNING  -> UiThemeTokens.severityWarning
            SeverityTier.INFO     -> UiThemeTokens.accentPrimary
        }

        private fun SeverityTier.icon(): String = when (this) {
            SeverityTier.CRITICAL -> "🔥"
            SeverityTier.WARNING  -> "⚠"
            SeverityTier.INFO     -> "ℹ"
        }
    }

    private inner class IssueDetailPanel : JPanel(BorderLayout()) {
        private val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        init {
            isOpaque = false
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, UiThemeTokens.borderDefault)
            add(JBScrollPane(contentPanel).apply {
                border = null; isOpaque = false; viewport.isOpaque = false
            }, BorderLayout.CENTER)
            showPlaceholder()
        }

        fun showPlaceholder() = render {
            add(placeholderLabel("Select an issue to view details"))
        }

        fun showHotspot(item: IssueItem.HotspotRow) = render {
            val color = item.tier.color()
            add(titleRow("${item.hotspot.methodName}  ·  L${item.hotspot.line}", color))
            if (item.hotspot.recommendation.isNotBlank()) {
                add(sectionLabel("Recommendation"))
                add(bodyText(item.hotspot.recommendation))
            }
            if (item.hotspot.contributions.isNotEmpty()) {
                add(sectionLabel("Contributing Factors"))
                item.hotspot.contributions.take(3).forEach { c ->
                    add(factorRow(c.type.displayName, c.explanation, (c.weightedScore * 100).toInt()))
                }
            }
            add(Box.createVerticalGlue())
        }

        fun showFile(item: IssueItem.FileHeader) = render {
            val color = item.result.severity.toTier().color()
            add(titleRow(fileName(item.result.filePath), color))
            if (item.result.contributions.isNotEmpty()) {
                add(sectionLabel("Factor Breakdown"))
                item.result.contributions.sortedByDescending { it.weightedScore }.take(4).forEach { c ->
                    add(factorRow(c.type.displayName, c.explanation, (c.weightedScore * 100).toInt()))
                }
            }
            val evidences = item.result.evidences.take(3)
            if (evidences.isNotEmpty()) {
                add(sectionLabel("Code Smells"))
                evidences.forEach { e -> add(evidenceRow(e.rule, e.value)) }
            }
            add(Box.createVerticalGlue())
        }

        private fun render(block: JPanel.() -> Unit) {
            contentPanel.removeAll()
            contentPanel.block()
            contentPanel.revalidate()
            contentPanel.repaint()
        }

        private fun SeverityTier.color(): Color = when (this) {
            SeverityTier.CRITICAL -> UiThemeTokens.severityCritical
            SeverityTier.WARNING  -> UiThemeTokens.severityWarning
            SeverityTier.INFO     -> UiThemeTokens.accentPrimary
        }

        private fun placeholderLabel(text: String) = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))
            border = JBUI.Borders.empty(10, 12)
            add(JLabel(text).apply { foreground = UiThemeTokens.textSecondary }, BorderLayout.CENTER)
        }

        private fun titleRow(text: String, color: Color) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))
            border = JBUI.Borders.empty(8, 12, 8, 12)
            add(JLabel(text).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = color
            })
            add(Box.createHorizontalGlue())
        }

        private fun sectionLabel(text: String) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(24))
            border = JBUI.Borders.empty(8, 12, 4, 12)
            add(JLabel(text).apply {
                font = font.deriveFont(Font.BOLD).deriveFont(font.size2D - 1f)
                foreground = UiThemeTokens.textSecondary
            })
            add(Box.createHorizontalGlue())
        }

        private fun bodyText(text: String) = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 12, 4, 12)
            add(JLabel("<html><body style='width:220px'>$text</body></html>").apply {
                foreground = UiThemeTokens.textPrimary
                font = font.deriveFont(font.size2D - 0.5f)
            }, BorderLayout.CENTER)
        }

        private fun factorRow(factor: String, explanation: String, score: Int) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(24))
            border = JBUI.Borders.empty(2, 16, 2, 12)
            add(JLabel("• $factor").apply {
                font = font.deriveFont(Font.BOLD).deriveFont(font.size2D - 1f)
                foreground = UiThemeTokens.textPrimary
                preferredSize = java.awt.Dimension(JBUI.scale(110), preferredSize.height)
                minimumSize = preferredSize
            })
            add(JLabel(explanation).apply {
                font = font.deriveFont(font.size2D - 1f)
                foreground = UiThemeTokens.textSecondary
            })
            add(Box.createHorizontalGlue())
            add(JLabel("$score").apply {
                font = font.deriveFont(Font.BOLD).deriveFont(font.size2D - 1f)
                foreground = UiThemeTokens.accentPrimary
                border = JBUI.Borders.emptyLeft(8)
            })
        }

        private fun evidenceRow(rule: String, value: String) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(24))
            border = JBUI.Borders.empty(2, 16, 2, 12)
            add(JLabel("⚠ $rule").apply {
                font = font.deriveFont(font.size2D - 1f)
                foreground = UiThemeTokens.textPrimary
            })
            add(Box.createHorizontalGlue())
            add(JLabel(value).apply {
                font = font.deriveFont(font.size2D - 1f)
                foreground = UiThemeTokens.textSecondary
                border = JBUI.Borders.emptyLeft(8)
            })
        }
    }
}
