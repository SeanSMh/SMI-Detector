package com.sqb.complexityradar.ide.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

internal class RadarHeaderPanel(
    private val project: Project,
    private val onExport: () -> Unit,
    private val onToggleGutter: () -> Unit,
    private val gutterLabelProvider: () -> String,
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(10, 12, 6, 12)

        add(JLabel("Complexity Radar").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            foreground = UiThemeTokens.textPrimary
        }, BorderLayout.WEST)

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(iconButton(AllIcons.General.Settings) { openSettings() })
            add(hgap(4))
            add(iconButton(AllIcons.Actions.More) { btn -> showMoreMenu(btn) })
        }, BorderLayout.EAST)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Complexity Radar")
    }

    private fun showMoreMenu(source: JButton) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Export Report").apply { addActionListener { onExport() } })
        menu.addSeparator()
        menu.add(JMenuItem(gutterLabelProvider()).apply {
            addActionListener {
                onToggleGutter()
                text = gutterLabelProvider()
            }
        })
        menu.show(source, 0, source.height)
    }

    private fun iconButton(icon: Icon, action: (JButton) -> Unit): JButton =
        JButton(icon).apply {
            isOpaque = false
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(26), JBUI.scale(26))
            minimumSize = preferredSize
            maximumSize = preferredSize
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isContentAreaFilled = true
                    background = UiThemeTokens.btnIconHover
                }
                override fun mouseExited(e: MouseEvent) {
                    isContentAreaFilled = false
                }
            })
            addActionListener { action(this) }
        }

    private fun hgap(w: Int) = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(w), 1)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
}
