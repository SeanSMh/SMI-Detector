package com.sqb.complexityradar.ide.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JPanel

internal enum class DashboardTab { OVERVIEW, ISSUES }
internal enum class DashboardScope { PROJECT, CURRENT_FILE }

internal class RadarNavPanel(
    private val onTabChange: (DashboardTab) -> Unit,
    private val onScopeChange: (DashboardScope) -> Unit,
) : JPanel(BorderLayout()) {
    private val tabToggle = SegmentedToggle(
        options = listOf("Overview", "Issues"),
        initialIndex = 0,
    ) { index -> onTabChange(if (index == 0) DashboardTab.OVERVIEW else DashboardTab.ISSUES) }

    private val scopeToggle = SegmentedToggle(
        options = listOf("Project", "Current File"),
        initialIndex = 0,
    ) { index -> onScopeChange(if (index == 0) DashboardScope.PROJECT else DashboardScope.CURRENT_FILE) }

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(Color(0xD6D6D6), Color(0x393B40))),
            JBUI.Borders.empty(4, 12, 6, 12),
        )
        add(tabToggle, BorderLayout.WEST)
        add(scopeToggle, BorderLayout.EAST)
    }

    fun selectTab(tab: DashboardTab) {
        tabToggle.selectIndex(if (tab == DashboardTab.OVERVIEW) 0 else 1)
    }

    fun selectScope(scope: DashboardScope) {
        scopeToggle.selectIndex(if (scope == DashboardScope.PROJECT) 0 else 1)
    }
}
