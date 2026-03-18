package com.sqb.complexityradar.ide.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar

internal class RadarFooterPanel : JPanel(BorderLayout()) {
    private val statusLabel = JLabel("Idle").apply {
        foreground = UiThemeTokens.textSecondary
        font = font.deriveFont(font.size2D - 0.5f)
    }
    private val versionLabel = JLabel("v0.1").apply {
        foreground = UiThemeTokens.textSecondary
        font = font.deriveFont(font.size2D - 1f)
    }
    private val progressBar = JProgressBar().apply {
        isBorderPainted = false
        isStringPainted = false
        foreground = JBColor(Color(0xA87B44), Color(0xD0A873))
        background = JBColor(Color(0xE0D0BC), Color(0x302921))
        preferredSize = java.awt.Dimension(JBUI.scale(120), JBUI.scale(4))
        isVisible = false
    }

    init {
        isOpaque = true
        background = UiThemeTokens.footerBg
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UiThemeTokens.footerBorder),
            JBUI.Borders.empty(5, 12),
        )
        val leftPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.WEST)
            add(progressBar, BorderLayout.CENTER)
        }
        add(leftPanel, BorderLayout.WEST)
        add(versionLabel, BorderLayout.EAST)
    }

    fun setIdle(message: String = "Idle") {
        statusLabel.text = message
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN)
        progressBar.isVisible = false
    }

    fun setScanning(message: String, processed: Int = 0, total: Int = 0) {
        statusLabel.text = message
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD)
        progressBar.isVisible = true
        if (total > 0) {
            progressBar.isIndeterminate = false
            progressBar.minimum = 0
            progressBar.maximum = total
            progressBar.value = processed.coerceIn(0, total)
        } else {
            progressBar.isIndeterminate = true
        }
    }

    fun setVersion(version: String) {
        versionLabel.text = version
    }
}
