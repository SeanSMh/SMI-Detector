package com.bril.code_radar.ide.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants

internal class LoadingOverlayPanel : JPanel(BorderLayout()) {
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

        val boxWidth = minOf(width - JBUI.scale(40), JBUI.scale(260))
            .coerceAtLeast(minOf(JBUI.scale(180), width - JBUI.scale(20)))
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
