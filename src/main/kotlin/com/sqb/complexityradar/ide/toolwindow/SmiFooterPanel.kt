package com.bril.code_radar.ide.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import com.bril.code_radar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

internal class SmiFooterPanel : JPanel(BorderLayout()) {

    private val statusIcon  = JLabel()
    private val statusLabel = JLabel("Ready")
    private val versionLabel = JLabel()

    init {
        isOpaque = true
        background = UiThemeTokens.footerBg
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UiThemeTokens.footerBorder),
            JBUI.Borders.empty(5, 12),
        )

        val west = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusIcon.apply { border = JBUI.Borders.emptyRight(5) }, BorderLayout.WEST)
            add(statusLabel.apply {
                foreground = UiThemeTokens.textSecondary
                font = font.deriveFont(font.size2D - 0.5f)
            }, BorderLayout.CENTER)
        }

        versionLabel.apply {
            foreground = UiThemeTokens.textSecondary
            font = font.deriveFont(font.size2D - 1f)
        }

        add(west, BorderLayout.WEST)
        add(versionLabel, BorderLayout.EAST)
    }

    fun setIdle(message: String) {
        statusIcon.icon = AllIcons.RunConfigurations.TestPassed
        statusLabel.text = message
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN)
    }

    fun setScanning(message: String) {
        statusIcon.icon = AllIcons.Actions.Refresh
        statusLabel.text = message
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD)
    }

    fun setVersion(version: String) {
        versionLabel.text = "v$version"
    }
}
