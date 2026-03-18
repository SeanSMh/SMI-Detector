package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import com.sqb.complexityradar.ide.ui.poopScoreCount
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
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
import javax.swing.SwingConstants
import kotlin.math.roundToInt

internal class RadarOverviewPanel(
    private val project: Project,
    private val onFileOpen: (ComplexityResult) -> Unit,
    private val onFileSelect: (ComplexityResult) -> Unit,
) : JPanel(CardLayout()) {
    private val cardLayout = layout as CardLayout

    // Project scope components
    private val projectScoreLabel = heroValueLabel("–")
    private val projectSubtitleLabel = captionLabel("No data yet")
    private val projectRadarPanel = RadarChartPanel()
    private val projectPressureLabel = bodyLabel("Run Scan Project to build a global view.")
    private val topFilesModel = DefaultListModel<ComplexityResult>()
    private val topFilesList = JBList(topFilesModel)

    // Current file scope components
    private val fileScoreLabel = heroValueLabel("–")
    private val fileSubtitleLabel = captionLabel("Open a file and run analysis")
    private val fileRadarPanel = RadarChartPanel()
    private val fileFactorLabel = bodyLabel("–")
    private val fileHotspotsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        add(buildProjectView(), CARD_PROJECT)
        add(buildCurrentFileView(), CARD_FILE)
        showCard(CARD_PROJECT)
        configureTopFilesList()
    }

    fun showScope(scope: DashboardScope) {
        showCard(if (scope == DashboardScope.PROJECT) CARD_PROJECT else CARD_FILE)
    }

    fun update(snapshot: FocusedViewSnapshot) {
        updateProjectView(snapshot)
        updateCurrentFileView(snapshot)
    }

    private fun showCard(card: String) = cardLayout.show(this, card)

    // ── Project view ──────────────────────────────────────────────────────────

    private fun buildProjectView(): JPanel =
        JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 12, 12, 12)
            add(JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(buildProjectHeroCard(), BorderLayout.WEST)
                add(buildProjectRadarCard(), BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(buildTopFilesCard(), BorderLayout.CENTER)
        }

    private fun buildProjectHeroCard(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UiThemeTokens.accentBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiThemeTokens.accentPrimary),
                JBUI.Borders.empty(10, 14),
            )
            preferredSize = Dimension(JBUI.scale(110), JBUI.scale(115))
            minimumSize = preferredSize
            add(JLabel("Avg Score").apply {
                font = font.deriveFont(Font.BOLD, font.size2D - 0.5f)
                foreground = UiThemeTokens.accentPrimary
            }, BorderLayout.NORTH)
            add(projectScoreLabel, BorderLayout.CENTER)
            add(projectSubtitleLabel, BorderLayout.SOUTH)
        }

    private fun buildProjectRadarCard(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(projectPressureLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(vgap(4))
            add(projectRadarPanel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

    private fun buildTopFilesCard(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiThemeTokens.borderDefault),
                JBUI.Borders.empty(0),
            )
            add(JLabel("Fix First").apply {
                font = font.deriveFont(Font.BOLD)
                foreground = UiThemeTokens.accentPrimary
                border = JBUI.Borders.empty(8, 12, 6, 12)
            }, BorderLayout.NORTH)
            topFilesList.emptyText.text = "Scan Project to rank the worst files."
            add(JBScrollPane(topFilesList), BorderLayout.CENTER)
        }

    private fun updateProjectView(snapshot: FocusedViewSnapshot) {
        val avgScore = snapshot.averageScore.roundToInt().coerceIn(0, 100)
        projectScoreLabel.text = if (snapshot.projectResults.isEmpty()) "–" else avgScore.toString()
        projectSubtitleLabel.text = when {
            snapshot.projectResults.isEmpty() -> "No data yet"
            snapshot.redCount > 0 -> "${snapshot.projectResults.size} files · ${snapshot.redCount} critical"
            else -> "${snapshot.projectResults.size} files"
        }
        projectRadarPanel.setAggregate(snapshot.aggregateValues, snapshot.aggregateSeverity)
        projectPressureLabel.text = snapshot.projectSummary
        topFilesModel.removeAllElements()
        snapshot.topFiles.forEach(topFilesModel::addElement)
    }

    private fun configureTopFilesList() {
        topFilesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        topFilesList.visibleRowCount = -1
        topFilesList.fixedCellHeight = JBUI.scale(60)
        topFilesList.cellRenderer = TopFileRenderer()
        topFilesList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) topFilesList.selectedValue?.let { onFileOpen(it) }
            }
        })
        topFilesList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) topFilesList.selectedValue?.let { onFileSelect(it) }
        }
    }

    // ── Current file view ─────────────────────────────────────────────────────

    private fun buildCurrentFileView(): JPanel =
        JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 12, 12, 12)
            add(JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(buildFileHeroCard(), BorderLayout.WEST)
                add(buildFileRadarCard(), BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(buildFileHotspotsCard(), BorderLayout.CENTER)
        }

    private fun buildFileHeroCard(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UiThemeTokens.accentBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiThemeTokens.accentPrimary),
                JBUI.Borders.empty(10, 14),
            )
            preferredSize = Dimension(JBUI.scale(110), JBUI.scale(115))
            minimumSize = preferredSize
            add(JLabel("Score").apply {
                font = font.deriveFont(Font.BOLD, font.size2D - 0.5f)
                foreground = UiThemeTokens.accentPrimary
            }, BorderLayout.NORTH)
            add(fileScoreLabel, BorderLayout.CENTER)
            add(fileSubtitleLabel, BorderLayout.SOUTH)
        }

    private fun buildFileRadarCard(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(fileFactorLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(vgap(4))
            add(fileRadarPanel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

    private fun buildFileHotspotsCard(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiThemeTokens.borderDefault),
                JBUI.Borders.empty(0),
            )
            add(JLabel("Hotspots").apply {
                font = font.deriveFont(Font.BOLD)
                foreground = UiThemeTokens.accentPrimary
                border = JBUI.Borders.empty(8, 12, 6, 12)
            }, BorderLayout.NORTH)
            add(JBScrollPane(fileHotspotsContainer.apply {
                border = JBUI.Borders.empty(4, 12, 8, 12)
            }), BorderLayout.CENTER)
        }

    private fun updateCurrentFileView(snapshot: FocusedViewSnapshot) {
        val result = snapshot.currentResult
        if (result == null) {
            fileScoreLabel.text = "–"
            fileSubtitleLabel.text = if (snapshot.targetFilePath != null) "Waiting..." else "Open a file"
            fileFactorLabel.text = "–"
            fileRadarPanel.setResult(null)
            renderHotspots(null)
        } else {
            fileScoreLabel.text = result.score.toString()
            fileSubtitleLabel.text = result.severity.label
            fileFactorLabel.text = leadingFactors(result)
            fileRadarPanel.setResult(result)
            renderHotspots(result)
        }
    }

    private fun renderHotspots(result: ComplexityResult?) {
        fileHotspotsContainer.removeAll()
        if (result == null || result.hotspots.isEmpty()) {
            fileHotspotsContainer.add(bodyLabel(
                if (result == null) "Hotspot methods will appear here after analysis."
                else "No hotspot methods detected."
            ))
        } else {
            result.hotspots.take(3).forEachIndexed { i, hotspot ->
                if (i > 0) fileHotspotsContainer.add(vgap(5))
                fileHotspotsContainer.add(hotspotRow(
                    hotspot.methodName,
                    "Line ${hotspot.line}  ·  Score ${hotspot.score}",
                    hotspot.severity,
                ))
            }
            fileHotspotsContainer.add(vgap(6))
            fileHotspotsContainer.add(bodyLabel("→  ${result.hotspots.first().recommendation}"))
        }
        fileHotspotsContainer.revalidate()
        fileHotspotsContainer.repaint()
    }

    private fun hotspotRow(title: String, subtitle: String, severity: Severity): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UiThemeTokens.bgCard
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, severityColor(severity)),
                    BorderFactory.createLineBorder(UiThemeTokens.borderDefault),
                ),
                JBUI.Borders.empty(6, 10),
            )
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(JLabel(title).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = UiThemeTokens.textPrimary
                    alignmentX = Component.LEFT_ALIGNMENT
                })
                add(vgap(2))
                add(JLabel(subtitle).apply {
                    foreground = UiThemeTokens.textSecondary
                    font = font.deriveFont(font.size2D - 0.5f)
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }, BorderLayout.CENTER)
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun leadingFactors(result: ComplexityResult): String {
        val names = result.contributions.take(2).map { it.type.displayName }
        return when {
            names.isEmpty() -> "A stable profile"
            names.size == 1 -> "Driven by ${names.first()}"
            else -> "Driven by ${names[0]} and ${names[1]}"
        }
    }

    private fun heroValueLabel(text: String) =
        JLabel(text, SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 14f)
            foreground = UiThemeTokens.accentPrimary
        }

    private fun captionLabel(text: String) =
        JLabel(text, SwingConstants.CENTER).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = UiThemeTokens.textSecondary
        }

    private fun bodyLabel(text: String) =
        JLabel("<html>${esc(text)}</html>").apply {
            foreground = UiThemeTokens.textSecondary
            font = font.deriveFont(font.size2D - 0.5f)
        }

    private fun vgap(h: Int) = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(h))
        minimumSize = preferredSize
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(h))
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun fileName(filePath: String) = filePath.substringAfterLast('/', filePath)

    private fun relativePath(filePath: String): String {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return filePath.replace('\\', '/')
        val normalized = filePath.replace('\\', '/')
        return if (normalized.startsWith("$basePath/")) normalized.removePrefix("$basePath/") else normalized
    }

    private fun shortenMiddle(value: String, maxLen: Int): String {
        if (value.length <= maxLen) return value
        val head = (maxLen / 2) - 2
        val tail = maxLen - head - 3
        return value.take(head) + "..." + value.takeLast(tail)
    }

    private inner class TopFileRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            val result = value as? ComplexityResult
                ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            return JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, severityColor(result.severity)),
                    JBUI.Borders.empty(6, 10),
                )
                background = if (isSelected) list.selectionBackground else list.background
                toolTipText = buildPoopTooltip(result.score)

                add(JLabel("#${index + 1}").apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.accentPrimary
                    font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                    border = JBUI.Borders.emptyRight(8)
                }, BorderLayout.WEST)

                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(JLabel(fileName(result.filePath)).apply {
                        font = font.deriveFont(Font.BOLD)
                        foreground = if (isSelected) list.selectionForeground else list.foreground
                    })
                    add(JLabel(shortenMiddle(relativePath(result.filePath), 32)).apply {
                        foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                        font = font.deriveFont(font.size2D - 0.5f)
                    })
                }, BorderLayout.CENTER)

                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    add(PoopRatingStrip(iconSize = 10, gap = 2).apply {
                        setScore(result.score, result.severity, isSelected)
                    })
                    add(JLabel("${poopScoreCount(result.score)}/5").apply {
                        foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                        font = font.deriveFont(Font.BOLD, font.size2D - 0.5f)
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(
                                if (isSelected) list.selectionForeground else UiThemeTokens.borderDefault,
                            ),
                            JBUI.Borders.empty(2, 5),
                        )
                    })
                }, BorderLayout.EAST)
            }
        }
    }

    companion object {
        private const val CARD_PROJECT = "project"
        private const val CARD_FILE = "file"
    }
}
