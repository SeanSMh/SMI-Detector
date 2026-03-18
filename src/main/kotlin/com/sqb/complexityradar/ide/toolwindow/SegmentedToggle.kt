package com.sqb.complexityradar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

internal class SegmentedToggle(
    private val options: List<String>,
    initialIndex: Int = 0,
    private val onChange: (Int) -> Unit,
) : JPanel() {
    private var selectedIndex: Int = initialIndex
    private val itemPadH = JBUI.scale(12)
    private val itemPadV = JBUI.scale(4)
    private val cornerRadius = JBUI.scale(5)

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = indexAt(e.x)
                if (idx >= 0 && idx != selectedIndex) {
                    selectedIndex = idx
                    repaint()
                    onChange(idx)
                }
            }
        })
    }

    fun selectIndex(index: Int) {
        if (selectedIndex != index) {
            selectedIndex = index
            repaint()
        }
    }

    private fun itemWidth(): Int {
        val fm = getFontMetrics(font.deriveFont(Font.BOLD))
        val maxTextWidth = options.maxOf { fm.stringWidth(it) }
        return maxTextWidth + itemPadH * 2
    }

    override fun getPreferredSize(): Dimension {
        val iw = itemWidth()
        val fm = getFontMetrics(font)
        val h = fm.height + itemPadV * 2 + JBUI.scale(4)
        return Dimension(iw * options.size + JBUI.scale(4), h)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val iw = itemWidth()
        val h = height
        val fm = g2.fontMetrics

        // Background pill
        g2.color = UiThemeTokens.toggleBg
        g2.fillRoundRect(0, 0, width, h, cornerRadius * 2, cornerRadius * 2)

        // Selected item highlight
        val selX = JBUI.scale(2) + selectedIndex * iw
        g2.color = UiThemeTokens.toggleSelectedBg
        g2.fillRoundRect(selX, JBUI.scale(2), iw, h - JBUI.scale(4), cornerRadius * 2, cornerRadius * 2)

        // Text
        options.forEachIndexed { i, option ->
            val isSelected = i == selectedIndex
            g2.font = if (isSelected) font.deriveFont(Font.BOLD) else font
            val textWidth = g2.fontMetrics.stringWidth(option)
            val textX = i * iw + (iw - textWidth) / 2
            val textY = (h + fm.ascent - fm.descent) / 2
            g2.color = if (isSelected) UiThemeTokens.toggleSelectedFg else UiThemeTokens.toggleUnselectedFg
            g2.drawString(option, textX, textY)
        }
        g2.dispose()
    }

    private fun indexAt(x: Int): Int {
        val iw = itemWidth()
        return (x / iw).coerceIn(0, options.size - 1)
    }
}
