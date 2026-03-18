package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.Hotspot
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
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

    private val chipCritical = filterChip("🔥 Critical (0)", SeverityTier.CRITICAL, UiThemeTokens.severityCritical)
    private val chipWarning  = filterChip("⚠ Warning (0)",  SeverityTier.WARNING,  UiThemeTokens.severityWarning)
    private val chipInfo     = filterChip("ℹ Info (0)",      SeverityTier.INFO,     UiThemeTokens.accentPrimary)

    private val listModel = DefaultListModel<IssueItem>()
    private val issueList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = -1
        cellRenderer = IssueRenderer()
    }

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

        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val item = issueList.selectedValue ?: return
                when (item) {
                    is IssueItem.FileHeader  -> navigateToFile(item.result.fileUrl)
                    is IssueItem.HotspotRow  -> navigateToLine(item.fileUrl, item.hotspot.line)
                    else -> Unit
                }
            }
        })

        add(filterRow, BorderLayout.NORTH)
        add(JBScrollPane(issueList), BorderLayout.CENTER)
    }

    fun update(
        results: List<ComplexityResult>,
        scope: DashboardScope,
        currentResult: ComplexityResult?,
    ) {
        cachedResults = results
        cachedScope   = scope
        cachedCurrent = currentResult
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

        chipCritical.text = "🔥 Critical ($criticalCount)"
        chipWarning.text  = "⚠ Warning ($warningCount)"
        chipInfo.text     = "ℹ Info ($infoCount)"

        if (listModel.isEmpty) listModel.addElement(IssueItem.Empty)
    }

    private fun Severity.toTier(): SeverityTier = when (this) {
        Severity.RED    -> SeverityTier.CRITICAL
        Severity.ORANGE -> SeverityTier.WARNING
        Severity.YELLOW -> SeverityTier.INFO
        Severity.GREEN  -> SeverityTier.INFO
    }

    private fun filterChip(initialText: String, tier: SeverityTier, color: Color): JLabel =
        JLabel(initialText).apply {
            isOpaque = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD).deriveFont(font.size2D - 1f)
            setChipActive(this, true, color)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (tier in activeFilters) activeFilters.remove(tier) else activeFilters.add(tier)
                    setChipActive(this@apply, tier in activeFilters, color)
                    rebuildList()
                }
            })
        }

    private fun setChipActive(chip: JLabel, active: Boolean, color: Color) {
        chip.background = withAlpha(color, if (active) 51 else 20)
        chip.foreground = color
        chip.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(withAlpha(color, if (active) 180 else 60), 1, true),
            JBUI.Borders.empty(3, 8),
        )
    }

    private fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

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

        private fun buildScoreBadge(score: Int, tier: SeverityTier): JLabel = JLabel(score.toString()).apply {
            font = font.deriveFont(Font.BOLD).deriveFont(font.size2D - 1f)
            foreground = tier.color()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(withAlpha(tier.color(), 100), 1, true),
                JBUI.Borders.empty(2, 7),
            )
            isOpaque = false
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
}
