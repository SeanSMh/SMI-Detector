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
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class RadarIssuesPanel(
    private val project: Project,
) : JPanel(CardLayout()) {
    private val cardLayout = layout as CardLayout

    // Sealed list item type
    sealed class IssueItem {
        data class FileHeader(val result: ComplexityResult) : IssueItem()
        data class HotspotRow(val hotspot: Hotspot, val fileUrl: String) : IssueItem()
        object Empty : IssueItem()
    }

    private val projectModel = DefaultListModel<IssueItem>()
    private val projectList = JBList(projectModel)
    private val fileModel = DefaultListModel<IssueItem>()
    private val fileList = JBList(fileModel)

    init {
        isOpaque = false
        add(buildListPanel(projectList, projectModel, "Scan Project to see issues."), CARD_PROJECT)
        add(buildListPanel(fileList, fileModel, "Open a file and run analysis."), CARD_FILE)
        showCard(CARD_PROJECT)
        configureList(projectList)
        configureList(fileList)
    }

    fun showScope(scope: DashboardScope) {
        showCard(if (scope == DashboardScope.PROJECT) CARD_PROJECT else CARD_FILE)
    }

    fun update(snapshot: FocusedViewSnapshot) {
        updateProjectIssues(snapshot)
        updateFileIssues(snapshot)
    }

    private fun showCard(card: String) = cardLayout.show(this, card)

    private fun buildListPanel(list: JBList<IssueItem>, model: DefaultListModel<IssueItem>, emptyText: String): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            list.emptyText.text = emptyText
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

    private fun configureList(list: JBList<IssueItem>) {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = -1
        list.cellRenderer = IssueItemRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 1) return
                val item = list.selectedValue ?: return
                when (item) {
                    is IssueItem.FileHeader -> navigateToFile(item.result.fileUrl)
                    is IssueItem.HotspotRow -> navigateToHotspot(item.fileUrl, item.hotspot.line)
                    is IssueItem.Empty -> Unit
                }
            }
        })
    }

    private fun updateProjectIssues(snapshot: FocusedViewSnapshot) {
        projectModel.removeAllElements()
        if (snapshot.topFiles.isEmpty()) {
            projectModel.addElement(IssueItem.Empty)
            return
        }
        snapshot.topFiles.forEach { result ->
            projectModel.addElement(IssueItem.FileHeader(result))
            result.hotspots.take(3).forEach { hotspot ->
                projectModel.addElement(IssueItem.HotspotRow(hotspot, result.fileUrl))
            }
        }
    }

    private fun updateFileIssues(snapshot: FocusedViewSnapshot) {
        fileModel.removeAllElements()
        val result = snapshot.currentResult
        if (result == null) {
            fileModel.addElement(IssueItem.Empty)
            return
        }
        fileModel.addElement(IssueItem.FileHeader(result))
        if (result.hotspots.isEmpty()) {
            fileModel.addElement(IssueItem.Empty)
        } else {
            result.hotspots.forEach { hotspot ->
                fileModel.addElement(IssueItem.HotspotRow(hotspot, result.fileUrl))
            }
        }
    }

    private fun navigateToFile(fileUrl: String) {
        val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun navigateToHotspot(fileUrl: String, line: Int) {
        val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun fileName(filePath: String) = filePath.substringAfterLast('/', filePath)

    private fun relativePath(filePath: String): String {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return filePath
        val normalized = filePath.replace('\\', '/')
        return if (normalized.startsWith("$basePath/")) normalized.removePrefix("$basePath/") else normalized
    }

    private fun shortenMiddle(value: String, maxLen: Int): String {
        if (value.length <= maxLen) return value
        val head = (maxLen / 2) - 2
        val tail = maxLen - head - 3
        return value.take(head) + "..." + value.takeLast(tail)
    }

    private inner class IssueItemRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            return when (val item = value as? IssueItem) {
                is IssueItem.FileHeader -> buildFileHeaderCell(item.result, isSelected, list)
                is IssueItem.HotspotRow -> buildHotspotCell(item.hotspot, isSelected, list)
                is IssueItem.Empty, null -> buildEmptyCell(isSelected, list)
            }
        }

        private fun buildFileHeaderCell(result: ComplexityResult, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else UiThemeTokens.bgCard
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, severityColor(result.severity)),
                        BorderFactory.createLineBorder(UiThemeTokens.borderDefault),
                    ),
                    JBUI.Borders.empty(7, 10),
                )
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JLabel(fileName(result.filePath)).apply {
                        font = font.deriveFont(Font.BOLD)
                        foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textPrimary
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                    add(JLabel(shortenMiddle(relativePath(result.filePath), 42)).apply {
                        foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                        font = font.deriveFont(font.size2D - 1f)
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                }, BorderLayout.CENTER)
                add(buildSeverityChip(result.severity), BorderLayout.EAST)
                toolTipText = "Score ${result.score}  ·  ${result.severity.label}"
            }

        private fun buildHotspotCell(hotspot: Hotspot, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(5, 24, 5, 10)
                add(JLabel("⬦  ${hotspot.methodName}").apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textPrimary
                    alignmentX = Component.LEFT_ALIGNMENT
                }, BorderLayout.CENTER)
                add(JLabel("L${hotspot.line}  ·  ${hotspot.score}").apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                    font = font.deriveFont(font.size2D - 0.5f)
                    border = JBUI.Borders.emptyLeft(8)
                }, BorderLayout.EAST)
            }

        private fun buildEmptyCell(isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(8, 12)
                add(JLabel("No issues found.").apply {
                    foreground = UiThemeTokens.textSecondary
                }, BorderLayout.CENTER)
            }

        private fun buildSeverityChip(severity: Severity): JPanel =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(severityColor(severity, 80)),
                    JBUI.Borders.empty(2, 7),
                )
                add(JLabel(severity.label).apply {
                    foreground = severityColor(severity)
                    font = font.deriveFont(Font.BOLD, font.size2D - 1f)
                }, BorderLayout.CENTER)
            }
    }

    companion object {
        private const val CARD_PROJECT = "project"
        private const val CARD_FILE = "file"
    }
}
