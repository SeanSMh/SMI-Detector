package com.sqb.complexityradar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JPanel

internal class SmiNavPanel(
    var onTabChange: (DashboardTab) -> Unit = {},
) : JPanel() {

    private var selectedTab = DashboardTab.OVERVIEW
    private var badgeCount = 0

    private val tabs = listOf(DashboardTab.OVERVIEW, DashboardTab.ISSUES)
    private val labels = mapOf(DashboardTab.OVERVIEW to "Overview", DashboardTab.ISSUES to "Issues")

    init {
        isOpaque = false
        preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
        minimumSize = Dimension(0, JBUI.scale(36))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UiThemeTokens.footerBorder),
            JBUI.Borders.empty(4, 12, 0, 12),
        )
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val tab = tabAtX(e.x) ?: return
                if (tab != selectedTab) {
                    selectedTab = tab
                    onTabChange(tab)
                    repaint()
                }
            }
        })
    }

    fun selectTab(tab: DashboardTab) {
        selectedTab = tab
        repaint()
    }

    fun updateBadge(count: Int) {
        badgeCount = count
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val fm = getFontMetrics(font)
        var x = 0

        tabs.forEachIndexed { _, tab ->
            val label = labels[tab]!!
            val isActive = tab == selectedTab
            val tabFont = if (isActive) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
            g2.font = tabFont
            val textWidth = g2.fontMetrics.stringWidth(label)
            val tabWidth = textWidth + JBUI.scale(24)
            val textX = x + (tabWidth - textWidth) / 2
            val textY = height - JBUI.scale(10)

            g2.color = if (isActive) UiThemeTokens.textPrimary else UiThemeTokens.textSecondary
            g2.drawString(label, textX, textY)

            if (isActive) {
                g2.color = UiThemeTokens.tabUnderline
                g2.fillRect(x, height - JBUI.scale(3), tabWidth, JBUI.scale(3))
            }

            if (tab == DashboardTab.ISSUES && badgeCount > 0) {
                val badgeText = badgeCount.toString()
                val badgeFont = font.deriveFont(Font.BOLD, font.size2D - 2f)
                g2.font = badgeFont
                val badgeFm = g2.fontMetrics
                val badgeDiameter = JBUI.scale(16)
                val badgeX = textX + textWidth + JBUI.scale(3)
                val badgeY = textY - fm.ascent - JBUI.scale(2)
                g2.color = UiThemeTokens.severityCritical
                g2.fillOval(badgeX, badgeY, badgeDiameter, badgeDiameter)
                g2.color = Color.WHITE
                val bw = badgeFm.stringWidth(badgeText)
                g2.drawString(badgeText, badgeX + (badgeDiameter - bw) / 2, badgeY + badgeDiameter - badgeFm.descent - JBUI.scale(2))
            }

            x += tabWidth + JBUI.scale(4)
        }

        g2.dispose()
    }

    private fun tabAtX(mouseX: Int): DashboardTab? {
        val fm = getFontMetrics(font)
        var x = 0
        return tabs.firstOrNull { tab ->
            val tabWidth = fm.stringWidth(labels[tab]!!) + JBUI.scale(24)
            val hit = mouseX in x..(x + tabWidth)
            x += tabWidth + JBUI.scale(4)
            hit
        }
    }
}
