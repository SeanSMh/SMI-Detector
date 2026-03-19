package com.sqb.complexityradar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel

internal class SmiToolbarPanel(
    var onScanClicked: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private var isHovered = false
    private var isRunning = false

    private val buttonLabel = JLabel("▷  Scan Project").apply {
        foreground = UiThemeTokens.btnPrimaryFg
        font = font.deriveFont(Font.BOLD)
    }

    private val pillButton = object : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(6, 16)
            add(buttonLabel, BorderLayout.CENTER)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!isRunning) onScanClicked()
                }
                override fun mouseEntered(e: MouseEvent) { isHovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { isHovered = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg: Color = when {
                isRunning -> Color(
                    UiThemeTokens.btnPrimaryBg.red,
                    UiThemeTokens.btnPrimaryBg.green,
                    UiThemeTokens.btnPrimaryBg.blue,
                ).darker()
                isHovered -> UiThemeTokens.btnPrimaryHover
                else      -> UiThemeTokens.btnPrimaryBg
            }
            g2.color = bg
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(12), JBUI.scale(12))
            g2.color = UiThemeTokens.btnPrimaryBorder
            g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
            g2.dispose()
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 12)
        add(pillButton, BorderLayout.WEST)
    }

    fun setScanRunning(running: Boolean) {
        isRunning = running
        buttonLabel.text = if (running) "Scanning..." else "▷  Scan Project"
        pillButton.cursor = if (running)
            Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        else
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        pillButton.repaint()
    }
}
