package com.sqb.complexityradar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants

internal class SmiOverviewPanel(
    var onScopeChange: (DashboardScope) -> Unit = {},
) : JPanel(BorderLayout()) {

    private var currentScope = DashboardScope.PROJECT

    private val projectBtn = scopeButton("Project")
    private val fileBtn    = scopeButton("Current File")

    private val cardLabel  = JLabel("PROJECT SMI", SwingConstants.CENTER)
    private val scoreLabel = JLabel("—", SwingConstants.CENTER)
    private val slashLabel = JLabel("/100", SwingConstants.LEFT)
    private val badgeLabel = JLabel("", SwingConstants.CENTER).apply {
        font = font.deriveFont(Font.BOLD, font.size2D - 1f)
        isOpaque = true
    }

    private val radarChart    = RadarChartPanel()
    private val metricsRightLabel = JLabel("Project Average").apply {
        foreground = UiThemeTokens.textSecondary
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        selectScopeButton(DashboardScope.PROJECT)

        val toggleRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
            add(projectBtn)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(fileBtn)
            add(Box.createHorizontalGlue())
        }

        val scoreCard = buildScoreCard()
        val scoreCardWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 0, 12)
            add(scoreCard, BorderLayout.CENTER)
        }

        val metricsHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 4, 12)
            add(JLabel("METRICS").apply {
                font = font.deriveFont(Font.BOLD)
                foreground = UiThemeTokens.textPrimary
            }, BorderLayout.WEST)
            add(metricsRightLabel, BorderLayout.EAST)
        }

        val radarWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 12, 12)
            add(radarChart, BorderLayout.CENTER)
        }

        contentPanel.add(toggleRow)
        contentPanel.add(scoreCardWrapper)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
        contentPanel.add(metricsHeader)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        contentPanel.add(radarWrapper)

        val scroll = JScrollPane(contentPanel).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
        }
        add(scroll, BorderLayout.CENTER)

        projectBtn.addMouseListener(scopeListener(DashboardScope.PROJECT))
        fileBtn.addMouseListener(scopeListener(DashboardScope.CURRENT_FILE))
    }

    fun selectScope(scope: DashboardScope) {
        currentScope = scope
        selectScopeButton(scope)
    }

    fun update(snapshot: FocusedViewSnapshot, scope: DashboardScope) {
        currentScope = scope
        selectScopeButton(scope)

        val score: Int
        val severity: Severity
        when (scope) {
            DashboardScope.PROJECT -> {
                score    = snapshot.averageScore.toInt()
                severity = snapshot.aggregateSeverity
                cardLabel.text = "PROJECT SMI"
                metricsRightLabel.text = "Project Average"
                radarChart.setAggregate(snapshot.aggregateValues, severity)
            }
            DashboardScope.CURRENT_FILE -> {
                val r    = snapshot.currentResult
                score    = r?.score ?: 0
                severity = r?.severity ?: Severity.GREEN
                cardLabel.text = "FILE SMI"
                metricsRightLabel.text = "File Score"
                radarChart.setResult(r)
            }
        }
        scoreLabel.text = score.toString()
        updateBadge(severity)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun buildScoreCard(): JPanel {
        val gradientBar = object : JPanel() {
            init {
                isOpaque = false
                preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(3))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(3))
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (width <= 0) return
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val mid = width / 2
                val midColor = Color(0xCD853F)
                g2.paint = GradientPaint(0f, 0f, UiThemeTokens.gradientCritical, mid.toFloat(), 0f, midColor)
                g2.fillRect(0, 0, mid, height)
                g2.paint = GradientPaint(mid.toFloat(), 0f, midColor, width.toFloat(), 0f, UiThemeTokens.gradientClean)
                g2.fillRect(mid, 0, width - mid, height)
                g2.dispose()
            }
        }

        val scoreRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))
            add(Box.createHorizontalGlue())
            add(scoreLabel.apply {
                font = font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(36f))
                foreground = UiThemeTokens.accentPrimary
            })
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(slashLabel.apply {
                font = font.deriveFont(14f)
                foreground = UiThemeTokens.textSecondary
            })
            add(Box.createHorizontalGlue())
        }

        cardLabel.apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = UiThemeTokens.textSecondary
        }

        val badgeWrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            add(Box.createHorizontalGlue())
            add(badgeLabel)
            add(Box.createHorizontalGlue())
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = UiThemeTokens.bgCard
            border = BorderFactory.createLineBorder(UiThemeTokens.borderDefault, 1, true)
            add(gradientBar.apply { alignmentX = 0f })
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(cardLabel.apply { alignmentX = 0.5f; maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(scoreRow.apply { alignmentX = 0f })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(badgeWrapper.apply { alignmentX = 0f })
            add(Box.createVerticalStrut(JBUI.scale(12)))
        }
    }

    private fun updateBadge(severity: Severity) {
        val (text, bgColor, fgColor, borderColor) = when (severity) {
            Severity.RED    -> listOf("🔥 Critical", Color(0x8B4513), Color(0xCD853F), Color(0xCD853F))
            Severity.ORANGE -> listOf("⚠ Warning",   Color(0xCD853F), Color(0xCD853F), Color(0xCD853F))
            Severity.YELLOW -> listOf("● Caution",   Color(0xA87B44), UiThemeTokens.accentPrimary as Any, UiThemeTokens.accentPrimary as Any)
            Severity.GREEN  -> listOf("✓ Clean",     Color(0x57965C), Color(0x57965C), Color(0x57965C))
        }
        badgeLabel.text       = text as String
        badgeLabel.background = withAlpha(bgColor as Color, 77)
        badgeLabel.foreground = fgColor as Color
        badgeLabel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor as Color, 1, true),
            JBUI.Borders.empty(4, 12),
        )
    }

    private fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    private fun scopeButton(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(Font.PLAIN)
        isOpaque = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun selectScopeButton(scope: DashboardScope) {
        listOf(projectBtn to DashboardScope.PROJECT, fileBtn to DashboardScope.CURRENT_FILE).forEach { (btn, s) ->
            if (s == scope) {
                btn.background = UiThemeTokens.accentPrimary
                btn.foreground = Color.WHITE
                btn.font       = btn.font.deriveFont(Font.BOLD)
                btn.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UiThemeTokens.accentPrimary, 1, true),
                    JBUI.Borders.empty(4, 12),
                )
            } else {
                btn.background = UiThemeTokens.bgCard
                btn.foreground = UiThemeTokens.textSecondary
                btn.font       = btn.font.deriveFont(Font.PLAIN)
                btn.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UiThemeTokens.borderDefault, 1, true),
                    JBUI.Borders.empty(4, 12),
                )
            }
        }
    }

    private fun scopeListener(scope: DashboardScope) = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (currentScope != scope) {
                currentScope = scope
                selectScopeButton(scope)
                onScopeChange(scope)
            }
        }
    }
}
