package com.bril.code_radar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.bril.code_radar.core.model.Severity
import com.bril.code_radar.ide.ui.UiThemeTokens
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
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants

private const val CARD_RADIUS = 8

private const val SCOPE_BTN_RADIUS = 12

internal class SmiOverviewPanel(
    var onScopeChange: (DashboardScope) -> Unit = {},
) : JPanel(BorderLayout()) {

    private var currentScope = DashboardScope.PROJECT

    private val projectBtn = ScopeButton("Project")
    private val fileBtn    = ScopeButton("Current File")

    private val cardLabel  = JLabel("PROJECT SMI", SwingConstants.CENTER)
    private val scoreLabel = JLabel("—", SwingConstants.CENTER)
    private val slashLabel = JLabel("/100", SwingConstants.LEFT)
    private val badgeChip = BadgeChip()

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
            add(fileBtn)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(projectBtn)
            add(Box.createHorizontalGlue())
        }

        val scoreCardWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 0, 12)
            add(buildScoreCard(), BorderLayout.CENTER)
        }

        val metricsCardWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 12, 12)
            add(buildMetricsCard(), BorderLayout.CENTER)
        }

        contentPanel.add(toggleRow)
        contentPanel.add(scoreCardWrapper)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
        contentPanel.add(metricsCardWrapper)

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
        cardLabel.apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = UiThemeTokens.textSecondary
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

        val badgeWrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            add(Box.createHorizontalGlue())
            add(badgeChip)
            add(Box.createHorizontalGlue())
        }

        val barH = JBUI.scale(3)

        return object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(barH + JBUI.scale(10), 0, JBUI.scale(12), 0)
                add(cardLabel.apply { alignmentX = 0.5f; maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) })
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(scoreRow.apply { alignmentX = 0.5f })
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(badgeWrapper.apply { alignmentX = 0.5f })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = JBUI.scale(CARD_RADIUS).toFloat()
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r * 2, r * 2)

                // Background
                g2.color = UiThemeTokens.bgCard
                g2.fill(shape)

                // Gradient bar clipped to rounded top
                g2.clip(shape)
                val mid = width / 2
                val midColor = Color(0xCD853F)
                g2.paint = GradientPaint(0f, 0f, UiThemeTokens.gradientCritical, mid.toFloat(), 0f, midColor)
                g2.fillRect(0, 0, mid, barH)
                g2.paint = GradientPaint(mid.toFloat(), 0f, midColor, width.toFloat(), 0f, UiThemeTokens.gradientClean)
                g2.fillRect(mid, 0, width - mid, barH)
                g2.clip = null

                // Border
                g2.paint = null
                g2.color = UiThemeTokens.borderDefault
                g2.draw(shape)
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun buildMetricsCard(): JPanel {
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, JBUI.scale(8), 0)
            add(JLabel("METRICS").apply {
                font = font.deriveFont(Font.BOLD)
                foreground = UiThemeTokens.textPrimary
            }, BorderLayout.WEST)
            add(metricsRightLabel, BorderLayout.EAST)
        }

        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(JBUI.scale(12), JBUI.scale(12), JBUI.scale(12), JBUI.scale(12))
                add(header, BorderLayout.NORTH)
                add(radarChart, BorderLayout.CENTER)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = JBUI.scale(CARD_RADIUS).toFloat()
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r * 2, r * 2)
                g2.color = UiThemeTokens.bgCard
                g2.fill(shape)
                g2.color = UiThemeTokens.borderDefault
                g2.draw(shape)
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun updateBadge(severity: Severity) {
        val (text, bgColor, fgColor, borderColor) = when (severity) {
            Severity.RED    -> listOf("🔥 Critical", UiThemeTokens.severityCritical, UiThemeTokens.severityWarning,  UiThemeTokens.severityWarning)
            Severity.ORANGE -> listOf("⚠ Warning",   UiThemeTokens.severityWarning,  UiThemeTokens.severityWarning,  UiThemeTokens.severityWarning)
            Severity.YELLOW -> listOf("● Caution",   UiThemeTokens.accentPrimary,    UiThemeTokens.accentPrimary,    UiThemeTokens.accentPrimary)
            Severity.GREEN  -> listOf("✓ Clean",     UiThemeTokens.severityGreen,    UiThemeTokens.severityGreen,    UiThemeTokens.severityGreen)
        }
        badgeChip.update(text as String, withAlpha(bgColor as Color, 77), fgColor as Color, borderColor as Color)
    }

    private fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    private fun selectScopeButton(scope: DashboardScope) {
        projectBtn.selected = (scope == DashboardScope.PROJECT)
        fileBtn.selected    = (scope == DashboardScope.CURRENT_FILE)
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

    private inner class ScopeButton(text: String) : JPanel(BorderLayout()) {
        private val label = JLabel(text).apply { isOpaque = false }
        private var hovered = false

        var selected: Boolean = false
            set(value) {
                field = value
                label.foreground = if (value) Color.WHITE else UiThemeTokens.textSecondary
                label.font = label.font.deriveFont(if (value) Font.BOLD else Font.PLAIN)
                repaint()
            }

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(4, 12)
            add(label, BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(SCOPE_BTN_RADIUS)
            g2.color = when {
                selected -> UiThemeTokens.accentPrimary
                hovered  -> UiThemeTokens.bgHover
                else     -> UiThemeTokens.bgCard
            }
            g2.fillRoundRect(0, 0, width, height, r, r)
            g2.color = if (selected) UiThemeTokens.accentPrimary else UiThemeTokens.borderDefault
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.dispose()
        }
    }

    private inner class BadgeChip : JPanel(BorderLayout()) {
        private val label = JLabel("", SwingConstants.CENTER).apply { isOpaque = false }
        private var chipBg: Color = Color(0x57965C)
        private var chipBorder: Color = Color(0x57965C)

        init {
            isOpaque = false
            border = JBUI.Borders.empty(4, 12)
            add(label, BorderLayout.CENTER)
        }

        override fun getMaximumSize(): Dimension = preferredSize

        fun update(text: String, bg: Color, fg: Color, border: Color) {
            label.text = text
            label.foreground = fg
            chipBg = bg
            chipBorder = border
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(12)
            g2.color = chipBg
            g2.fillRoundRect(0, 0, width, height, r, r)
            g2.color = chipBorder
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.dispose()
        }
    }
}
