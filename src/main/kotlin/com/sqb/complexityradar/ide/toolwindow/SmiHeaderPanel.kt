package com.sqb.complexityradar.ide.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel

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
            val gutterLabel = gutterLabelProvider()
            val items = listOf("Export Report", gutterLabel)
            val step = object : BaseListPopupStep<String>(null, items) {
                override fun getIconFor(value: String): Icon = when (value) {
                    "Export Report" -> AllIcons.Actions.Download
                    else            -> AllIcons.General.Locate
                }
                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                    when (selectedValue) {
                        "Export Report" -> onExport()
                        else            -> onToggleGutter()
                    }
                    return FINAL_CHOICE
                }
            }
            JBPopupFactory.getInstance()
                .createListPopup(step)
                .showUnderneathOf(e.component)
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

    private fun iconButton(icon: javax.swing.Icon, onClick: (MouseEvent) -> Unit): JPanel {
        var hovered = false
        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                preferredSize = Dimension(JBUI.scale(26), JBUI.scale(26))
                maximumSize = preferredSize
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(JLabel(icon), BorderLayout.CENTER)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onClick(e)
                    override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                })
            }
            override fun paintComponent(g: Graphics) {
                if (hovered) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = UiThemeTokens.btnIconHover
                    val inset = JBUI.scale(2)
                    val r = JBUI.scale(6)
                    g2.fillRoundRect(inset, inset, width - inset * 2, height - inset * 2, r, r)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
    }
}
