package com.sqb.complexityradar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class RadarToolbarPanel(
    private val onScanProject: () -> Unit,
    private val onRefresh: () -> Unit,
) : JPanel(BorderLayout()) {
    private val scanButton = primaryButton("Scan Project") { onScanProject() }
    private val refreshButton = secondaryButton("Refresh") { onRefresh() }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 12, 8, 12)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(scanButton)
            add(hgap(6))
            add(refreshButton)
        }, BorderLayout.WEST)
    }

    fun setScanRunning(running: Boolean) {
        scanButton.isEnabled = !running
        refreshButton.isEnabled = !running
    }

    private fun primaryButton(text: String, action: () -> Unit): JButton =
        JButton(text).apply {
            isOpaque = true
            background = UiThemeTokens.btnPrimaryBg
            foreground = UiThemeTokens.btnPrimaryFg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiThemeTokens.btnPrimaryBorder),
                JBUI.Borders.empty(5, 14),
            )
            font = font.deriveFont(Font.BOLD)
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { if (isEnabled) background = UiThemeTokens.btnPrimaryHover }
                override fun mouseExited(e: MouseEvent) { background = UiThemeTokens.btnPrimaryBg }
            })
            addActionListener { action() }
        }

    private fun secondaryButton(text: String, action: () -> Unit): JButton =
        JButton(text).apply {
            isOpaque = true
            background = UiThemeTokens.btnSecondaryBg
            foreground = UiThemeTokens.btnSecondaryFg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiThemeTokens.btnSecondaryBorder),
                JBUI.Borders.empty(5, 12),
            )
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { if (isEnabled) background = UiThemeTokens.btnSecondaryHover }
                override fun mouseExited(e: MouseEvent) { background = UiThemeTokens.btnSecondaryBg }
            })
            addActionListener { action() }
        }

    private fun hgap(w: Int) = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(w), 1)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
}
