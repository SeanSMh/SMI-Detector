package com.sqb.complexityradar.ide.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

internal class SmiHeaderPanel(
    private val project: Project,
    private val onExport: () -> Unit,
    private val onToggleGutter: () -> Unit,
    private val gutterLabelProvider: () -> String,
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(10, 12, 6, 12)

        val title = JLabel("SMI Detector").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = UiThemeTokens.textPrimary
            icon = AllIcons.Debugger.Db_exception_breakpoint
            iconTextGap = 6
        }

        val settingsBtn = iconButton(AllIcons.General.Settings) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "Complexity Radar")
        }

        val moreBtn = iconButton(AllIcons.Actions.More) { e ->
            val menu = JPopupMenu()
            menu.add(JMenuItem("Export Report").apply { addActionListener { onExport() } })
            menu.add(JMenuItem(gutterLabelProvider()).apply { addActionListener { onToggleGutter() } })
            menu.show(e.component, 0, e.component.height)
        }

        val east = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(settingsBtn)
            add(Box.createHorizontalStrut(2))
            add(moreBtn)
        }

        add(title, BorderLayout.WEST)
        add(east, BorderLayout.EAST)
    }

    private fun iconButton(icon: javax.swing.Icon, onClick: (MouseEvent) -> Unit): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(26), JBUI.scale(26))
            maximumSize = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(JLabel(icon), BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick(e)
                override fun mouseEntered(e: MouseEvent) { isOpaque = true; background = UiThemeTokens.btnIconHover; repaint() }
                override fun mouseExited(e: MouseEvent) { isOpaque = false; repaint() }
            })
        }
}
