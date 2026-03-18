# SMI Detector UI Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the Complexity Radar plugin ToolWindow UI to match the "SMI Detector" design: new branding, large score card with gradient bar, underline tabs with Issues badge, severity filter chips in Issues tab, and radar-only Overview.

**Architecture:** Pure visual rewrite — 7 new `Smi*` panel files replace 7 old `Radar*` files. Core services (`ComplexityRadarProjectService`, `ComplexityResultStore`, `RadarUiRefreshService`), data models, scoring engine, and `FocusedViewSnapshot` are untouched. `SmiDashboardPanel` directly inherits the `buildSnapshot()` + `applySnapshot()` pattern from the old dashboard.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (Swing — `JPanel`, `JBColor`, `JBList`, `JBUI`), Gradle with IntelliJ Platform Gradle Plugin 2.7.0, JDK 17.

---

## File Map

| Action | File | Responsibility |
|---|---|---|
| Modify | `src/main/resources/META-INF/plugin.xml` | Rename ToolWindow id to "SMI Detector" |
| Modify | `src/main/kotlin/.../ide/toolwindow/ComplexityRadarToolWindowFactory.kt` | Instantiate `SmiDashboardPanel` |
| Modify | `src/main/kotlin/.../ide/ui/UiThemeTokens.kt` | Add 6 new color tokens |
| Modify | `src/main/kotlin/.../ide/toolwindow/RadarChartPanel.kt` | Rename 5 axis label strings |
| Create | `src/main/kotlin/.../ide/toolwindow/SmiDashboardPanel.kt` | Main container, `RefreshableComplexityView` |
| Create | `src/main/kotlin/.../ide/toolwindow/SmiHeaderPanel.kt` | Title + icon buttons |
| Create | `src/main/kotlin/.../ide/toolwindow/SmiToolbarPanel.kt` | Pill-shaped Scan button |
| Create | `src/main/kotlin/.../ide/toolwindow/SmiNavPanel.kt` | Underline tabs + Issues badge |
| Create | `src/main/kotlin/.../ide/toolwindow/SmiOverviewPanel.kt` | Scope toggle + score card + radar |
| Create | `src/main/kotlin/.../ide/toolwindow/SmiIssuesPanel.kt` | Severity filters + file tree |
| Create | `src/main/kotlin/.../ide/toolwindow/SmiFooterPanel.kt` | Status icon + label + version |
| Delete | `src/main/kotlin/.../ide/toolwindow/ComplexityRadarDashboardPanel.kt` | Replaced by SmiDashboardPanel |
| Delete | `src/main/kotlin/.../ide/toolwindow/RadarHeaderPanel.kt` | Replaced |
| Delete | `src/main/kotlin/.../ide/toolwindow/RadarToolbarPanel.kt` | Replaced |
| Delete | `src/main/kotlin/.../ide/toolwindow/RadarNavPanel.kt` | Replaced |
| Delete | `src/main/kotlin/.../ide/toolwindow/RadarOverviewPanel.kt` | Replaced |
| Delete | `src/main/kotlin/.../ide/toolwindow/RadarIssuesPanel.kt` | Replaced |
| Delete | `src/main/kotlin/.../ide/toolwindow/RadarFooterPanel.kt` | Replaced |

> **Base package path:** `src/main/kotlin/com/sqb/complexityradar`

---

## Task 1: Add Theme Tokens + Rename Radar Axis Labels

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/ide/ui/UiThemeTokens.kt`
- Modify: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarChartPanel.kt`

### Context
`UiThemeTokens` is an `object` of `JBColor` values (light/dark pairs). New tokens use same hex for both — intentionally theme-invariant per design spec. `RadarChartPanel.labelFor()` at line 132 returns abbreviated strings; rename them.

- [ ] **Step 1: Add 6 new tokens to UiThemeTokens.kt**

Append after the `// Footer` block (end of object, before closing `}`):

```kotlin
    // Severity tiers (theme-invariant — matches SMI design palette)
    val severityCritical = JBColor(Color(0x8B4513), Color(0x8B4513))
    val severityWarning  = JBColor(Color(0xCD853F), Color(0xCD853F))
    val severityGreen    = JBColor(Color(0x57965C), Color(0x57965C))

    // Score card gradient endpoints
    val gradientCritical = JBColor(Color(0x8B4513), Color(0x8B4513))
    val gradientClean    = JBColor(Color(0x57965C), Color(0x57965C))

    // Active tab underline
    val tabUnderline     = JBColor(Color(0xA87B44), Color(0xA87B44))
```

- [ ] **Step 2: Rename axis labels in RadarChartPanel.kt**

Edit `labelFor()` at line 132–139 (exact replacement):

```kotlin
    private fun labelFor(factor: FactorType): String =
        when (factor) {
            FactorType.SIZE           -> "Size"
            FactorType.CONTROL_FLOW   -> "Complexity"
            FactorType.NESTING        -> "Nesting"
            FactorType.DOMAIN_COUPLING -> "Duplication"
            FactorType.READABILITY    -> "Smells"
        }
```

- [ ] **Step 3: Verify compilation**

```bash
cd /Users/sqb/projects/code_analy_plugin && ./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/ui/UiThemeTokens.kt \
        src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarChartPanel.kt
git commit -m "feat: add SMI theme tokens and rename radar axis labels"
```

---

## Task 2: SmiFooterPanel

**Files:**
- Create: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiFooterPanel.kt`

### Context
Simple status bar at the bottom. Two states: idle (green checkmark) and scanning (amber spinning indicator). No progress bar — that goes to `LoadingOverlayPanel`. The old `RadarFooterPanel.setScanning(msg, processed, total)` three-arg form is **removed**; this panel only has `setScanning(message: String)`.

- [ ] **Step 1: Create SmiFooterPanel.kt**

```kotlin
package com.sqb.complexityradar.ide.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Icon
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
        statusIcon.icon = coloredIcon(AllIcons.RunConfigurations.TestPassed, UiThemeTokens.severityGreen)
        statusLabel.text = message
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN)
    }

    fun setScanning(message: String) {
        statusIcon.icon = AllIcons.Actions.Refresh   // IDE will animate if needed
        statusLabel.text = message
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD)
    }

    fun setVersion(version: String) {
        versionLabel.text = "v$version"
    }

    /** Tints a copy of [icon] using a colored label trick — simple approach for small icons. */
    private fun coloredIcon(icon: Icon, color: Color): Icon = icon  // Use as-is; IntelliJ auto-tints in dark theme
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiFooterPanel.kt
git commit -m "feat: add SmiFooterPanel with idle/scanning states"
```

---

## Task 3: SmiHeaderPanel

**Files:**
- Create: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiHeaderPanel.kt`

### Context
Header bar: title on the left, Settings + More icon buttons on the right. "More" opens a popup with Export Report and Show/Hide Gutter Icons. Mirrors `RadarHeaderPanel` structure but with new branding. Icon buttons are 26×26, no border, hover fill `btnIconHover`. The caller provides callbacks for `onExport`, `onToggleGutter`, and `gutterLabelProvider` (function that returns the dynamic menu label).

- [ ] **Step 1: Create SmiHeaderPanel.kt**

```kotlin
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
import javax.swing.BorderFactory
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

        // Title
        val title = JLabel("SMI Detector").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = UiThemeTokens.textPrimary
            icon = AllIcons.Debugger.Db_exception_breakpoint
            iconTextGap = 6
        }

        // Settings icon button
        val settingsBtn = iconButton(AllIcons.General.Settings) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "Complexity Radar")
        }

        // More icon button
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
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiHeaderPanel.kt
git commit -m "feat: add SmiHeaderPanel with SMI Detector branding"
```

---

## Task 4: SmiToolbarPanel

**Files:**
- Create: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiToolbarPanel.kt`

### Context
Single pill-shaped "▷ Scan Project" button. Background `btnPrimaryBg`, white text, bold font. Pill shape via `paintComponent` with `fillRoundRect`. Scanning state: disabled + text "Scanning...". `onScanClicked` callback fires on click.

- [ ] **Step 1: Create SmiToolbarPanel.kt**

```kotlin
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
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JLabel

internal class SmiToolbarPanel(
    var onScanClicked: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private var isHovered = false
    private var isRunning = false

    private val pillButton = object : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(6, 16)
            add(JLabel("▷  Scan Project").apply {
                foreground = UiThemeTokens.btnPrimaryFg
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.CENTER)
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
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg: Color = when {
                isRunning  -> UiThemeTokens.btnPrimaryBg.darker()
                isHovered  -> UiThemeTokens.btnPrimaryHover
                else       -> UiThemeTokens.btnPrimaryBg
            }
            g2.color = bg
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(20), JBUI.scale(20))
            g2.color = UiThemeTokens.btnPrimaryBorder
            g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(20), JBUI.scale(20))
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private val buttonLabel: JLabel
        get() = pillButton.components.filterIsInstance<JLabel>().first()

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
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiToolbarPanel.kt
git commit -m "feat: add SmiToolbarPanel with pill-shaped scan button"
```

---

## Task 5: SmiNavPanel

**Files:**
- Create: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiNavPanel.kt`

### Context
Two tabs drawn via `paintComponent`: "Overview" and "Issues". Active tab has 3px amber bottom underline + bold primary text. Issues tab has a circular badge (`redCount`) drawn top-right of the label; hidden when count is 0. `DashboardTab` enum is already defined in the `toolwindow` package (it's used by the old `ComplexityRadarDashboardPanel`).

- [ ] **Step 1: Create SmiNavPanel.kt**

```kotlin
package com.sqb.complexityradar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JPanel

internal class SmiNavPanel(
    var onTabChange: (DashboardTab) -> Unit = {},
) : JPanel() {

    private var selectedTab = DashboardTab.OVERVIEW
    private var badgeCount = 0

    private val tabs = listOf(DashboardTab.OVERVIEW, DashboardTab.ISSUES)
    private val labels = mapOf(DashboardTab.OVERVIEW to "Overview", DashboardTab.ISSUES to "Issues")

    init {
        isOpaque = false
        preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
        minimumSize = Dimension(0, JBUI.scale(36))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UiThemeTokens.footerBorder),
            JBUI.Borders.empty(4, 12, 0, 12),
        )
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val tab = tabAtX(e.x) ?: return
                if (tab != selectedTab) {
                    selectedTab = tab
                    onTabChange(tab)
                    repaint()
                }
            }
        })
    }

    fun selectTab(tab: DashboardTab) {
        selectedTab = tab
        repaint()
    }

    fun updateBadge(count: Int) {
        badgeCount = count
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val fm = g2.fontMetrics
        var x = 0

        tabs.forEachIndexed { i, tab ->
            val label = labels[tab]!!
            val isActive = tab == selectedTab
            val tabFont = if (isActive) g2.font.deriveFont(Font.BOLD) else g2.font.deriveFont(Font.PLAIN)
            g2.font = tabFont
            val textWidth = g2.fontMetrics.stringWidth(label)
            val tabWidth = textWidth + JBUI.scale(24)
            val textX = x + (tabWidth - textWidth) / 2
            val textY = height - JBUI.scale(10)

            // Label
            g2.color = if (isActive) UiThemeTokens.textPrimary else UiThemeTokens.textSecondary
            g2.drawString(label, textX, textY)

            // Active underline
            if (isActive) {
                g2.color = UiThemeTokens.tabUnderline
                g2.fillRect(x, height - JBUI.scale(3), tabWidth, JBUI.scale(3))
            }

            // Badge on Issues tab
            if (tab == DashboardTab.ISSUES && badgeCount > 0) {
                val badgeText = badgeCount.toString()
                val badgeFont = g2.font.deriveFont(Font.BOLD, g2.font.size2D - 2f)
                g2.font = badgeFont
                val badgeFm = g2.fontMetrics
                val badgeDiameter = JBUI.scale(16)
                val badgeX = textX + textWidth + JBUI.scale(3)
                val badgeY = textY - fm.ascent - JBUI.scale(2)
                g2.color = UiThemeTokens.severityCritical
                g2.fillOval(badgeX, badgeY, badgeDiameter, badgeDiameter)
                g2.color = Color.WHITE
                val bw = badgeFm.stringWidth(badgeText)
                g2.drawString(badgeText, badgeX + (badgeDiameter - bw) / 2, badgeY + badgeDiameter - badgeFm.descent - JBUI.scale(2))
            }

            x += tabWidth + JBUI.scale(4)
        }

        g2.dispose()
    }

    /** Returns which tab the x-coordinate falls in, or null if outside tabs. */
    private fun tabAtX(mouseX: Int): DashboardTab? {
        val fm = getFontMetrics(font)
        var x = 0
        return tabs.firstOrNull { tab ->
            val tabWidth = fm.stringWidth(labels[tab]!!) + JBUI.scale(24)
            val hit = mouseX in x..(x + tabWidth)
            x += tabWidth + JBUI.scale(4)
            hit
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiNavPanel.kt
git commit -m "feat: add SmiNavPanel with underline tabs and Issues badge"
```

---

## Task 6: SmiOverviewPanel

**Files:**
- Create: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiOverviewPanel.kt`

### Context
Scrollable vertical layout:
1. Scope toggle (reuses `SegmentedToggle` with new colors)
2. Score card (gradient top bar + large score + severity pill)
3. Metrics header row ("METRICS" left, "Project Average"/"File Score" right)
4. `RadarChartPanel`

`SegmentedToggle`'s selected/unselected tokens need to be overridden here. The easiest approach: set the toggle's `toggleSelectedBg` indirectly by subclassing or by using the existing `UiThemeTokens.accentPrimary` as the selected color — note that `SegmentedToggle` uses `UiThemeTokens.toggleSelectedBg` / `toggleSelectedFg` etc. Since those tokens aren't changing globally, we'll set the scope toggle's background color after construction using a wrapper.

> **Simpler approach**: instead of fighting `SegmentedToggle`'s token system, create a minimal inline scope toggle as two styled `JPanel` pill buttons — same visual result, less coupling.

- [ ] **Step 1: Create SmiOverviewPanel.kt**

```kotlin
package com.sqb.complexityradar.ide.toolwindow

import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants

internal class SmiOverviewPanel(
    var onScopeChange: (DashboardScope) -> Unit = {},
) : JPanel(BorderLayout()) {

    private var currentScope = DashboardScope.PROJECT

    // Scope toggle
    private val projectBtn  = scopeButton("Project")
    private val fileBtn     = scopeButton("Current File")

    // Score card
    private val cardLabel  = JLabel("PROJECT SMI", SwingConstants.CENTER)
    private val scoreLabel = JLabel("—", SwingConstants.CENTER)
    private val slashLabel = JLabel("/100", SwingConstants.LEFT)
    private val badgeLabel = JLabel()

    // Radar
    private val radarChart = RadarChartPanel()
    private val metricsHeader = buildMetricsHeader()

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false

        // Scope toggle row
        val toggleRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
            add(projectBtn)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(fileBtn)
            add(Box.createHorizontalGlue())
        }
        selectScopeButton(DashboardScope.PROJECT)

        // Score card with gradient bar
        val scoreCard = buildScoreCard()

        // Wrap score card in a panel to add horizontal margin
        val scoreCardWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 0, 12)
            add(scoreCard, BorderLayout.CENTER)
        }

        contentPanel.add(toggleRow)
        contentPanel.add(scoreCardWrapper)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
        contentPanel.add(metricsHeader)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
        contentPanel.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(radarChart, BorderLayout.CENTER)
        })

        val scroll = JScrollPane(contentPanel).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
        }
        add(scroll, BorderLayout.CENTER)

        projectBtn.addMouseListener(scopeListener(DashboardScope.PROJECT))
        fileBtn.addMouseListener(scopeListener(DashboardScope.CURRENT_FILE))
    }

    fun selectScope(scope: DashboardScope) {
        currentScope = scope
        selectScopeButton(scope)
    }

    fun update(snapshot: FocusedViewSnapshot, scope: DashboardScope) {
        currentScope = scope
        selectScopeButton(scope)

        val score: Int
        val severity: Severity
        when (scope) {
            DashboardScope.PROJECT -> {
                score = snapshot.averageScore.toInt()
                severity = snapshot.aggregateSeverity
                cardLabel.text = "PROJECT SMI"
                radarChart.setAggregate(snapshot.aggregateValues, severity)
                updateMetricsHeader("Project Average")
            }
            DashboardScope.CURRENT_FILE -> {
                val r = snapshot.currentResult
                score = r?.score ?: 0
                severity = r?.severity ?: Severity.GREEN
                cardLabel.text = "FILE SMI"
                radarChart.setResult(r)
                updateMetricsHeader("File Score")
            }
        }
        scoreLabel.text = score.toString()
        updateBadge(severity)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun buildScoreCard(): JPanel {
        // Gradient bar (custom painted, 3px tall)
        val gradientBar = object : JPanel() {
            init { preferredSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(3)); isOpaque = false }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                val paint = GradientPaint(
                    0f, 0f, UiThemeTokens.gradientCritical,
                    width / 2f, 0f, Color(0xCD853F),
                ).let { _ ->
                    // Two-stop gradient: critical → mid → clean
                    // Java GradientPaint is 2-stop; use a custom approach
                    if (width > 0) {
                        g2.color = UiThemeTokens.gradientCritical
                        val mid = width / 2
                        val p1 = GradientPaint(0f, 0f, UiThemeTokens.gradientCritical, mid.toFloat(), 0f, Color(0xCD853F))
                        val p2 = GradientPaint(mid.toFloat(), 0f, Color(0xCD853F), width.toFloat(), 0f, UiThemeTokens.gradientClean)
                        g2.paint = p1; g2.fillRect(0, 0, mid, height)
                        g2.paint = p2; g2.fillRect(mid, 0, width - mid, height)
                    }
                }
                g2.dispose()
            }
        }

        // Score row: big number + /100
        val scoreRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(Box.createHorizontalGlue())
            add(scoreLabel.apply {
                font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(36f))
                foreground = UiThemeTokens.accentPrimary
            })
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(slashLabel.apply {
                font = font.deriveFont(14f)
                foreground = UiThemeTokens.textSecondary
                alignmentY = 0.8f  // align baseline with score
            })
            add(Box.createHorizontalGlue())
        }

        // Card label
        cardLabel.apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = UiThemeTokens.textSecondary
        }

        // Badge
        badgeLabel.apply {
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xCD853F), 1, true),
                JBUI.Borders.empty(4, 12),
            )
            isOpaque = true
        }
        val badgeWrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(Box.createHorizontalGlue())
            add(badgeLabel)
            add(Box.createHorizontalGlue())
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = UiThemeTokens.bgCard
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiThemeTokens.borderDefault, 1, true),
                JBUI.Borders.empty(0, 16, 16, 16),
            )
            add(gradientBar.apply { alignmentX = 0f; maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(3)) })
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(cardLabel.apply { alignmentX = 0.5f })
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(scoreRow.apply { alignmentX = 0f; maximumSize = java.awt.Dimension(Int.MAX_VALUE, scoreRow.preferredSize.height) })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(badgeWrapper.apply { alignmentX = 0f; maximumSize = java.awt.Dimension(Int.MAX_VALUE, badgeWrapper.preferredSize.height) })
        }
    }

    private fun buildMetricsHeader(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 12, 4, 12)
        add(JLabel("METRICS").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = UiThemeTokens.textPrimary
        }, BorderLayout.WEST)
        add(JLabel("Project Average").apply {
            foreground = UiThemeTokens.textSecondary
            putClientProperty("smiMetricsRight", true)
        }, BorderLayout.EAST)
    }

    private fun updateMetricsHeader(rightText: String) {
        val right = metricsHeader.components
            .filterIsInstance<JLabel>()
            .firstOrNull { it.getClientProperty("smiMetricsRight") == true }
        right?.text = rightText
    }

    private fun updateBadge(severity: Severity) {
        val (text, bg, fg, border) = when (severity) {
            Severity.RED    -> listOf("🔥 Critical", Color(0x8B4513), Color(0xCD853F), Color(0xCD853F))
            Severity.ORANGE -> listOf("⚠ Warning",  Color(0xCD853F), Color(0xCD853F), Color(0xCD853F))
            Severity.YELLOW -> listOf("● Caution",  Color(0xA87B44), UiThemeTokens.accentPrimary, UiThemeTokens.accentPrimary)
            Severity.GREEN  -> listOf("✓ Clean",    Color(0x57965C), Color(0x57965C), Color(0x57965C))
        }
        @Suppress("UNCHECKED_CAST")
        val colors = listOf(text, bg, fg, border) as List<Any>
        badgeLabel.text        = colors[0] as String
        badgeLabel.background  = withAlpha(colors[1] as Color, 77)
        badgeLabel.foreground  = colors[2] as Color
        badgeLabel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(colors[3] as Color, 1, true),
            JBUI.Borders.empty(4, 12),
        )
    }

    private fun withAlpha(color: Color, alpha: Int) =
        Color(color.red, color.green, color.blue, alpha)

    private fun scopeButton(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(Font.PLAIN)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiThemeTokens.borderDefault, 1, true),
            JBUI.Borders.empty(4, 12),
        )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = true
    }

    private fun selectScopeButton(scope: DashboardScope) {
        listOf(projectBtn to DashboardScope.PROJECT, fileBtn to DashboardScope.CURRENT_FILE).forEach { (btn, s) ->
            if (s == scope) {
                btn.background = UiThemeTokens.accentPrimary
                btn.foreground = Color.WHITE
                btn.font = btn.font.deriveFont(Font.BOLD)
            } else {
                btn.background = UiThemeTokens.bgCard
                btn.foreground = UiThemeTokens.textSecondary
                btn.font = btn.font.deriveFont(Font.PLAIN)
            }
        }
    }

    private fun scopeListener(scope: DashboardScope) = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (currentScope != scope) {
                currentScope = scope
                selectScopeButton(scope)
                onScopeChange(scope)
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiOverviewPanel.kt
git commit -m "feat: add SmiOverviewPanel with score card and radar chart"
```

---

## Task 7: SmiIssuesPanel

**Files:**
- Create: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiIssuesPanel.kt`

### Context
Replaces `RadarIssuesPanel`. Single `BorderLayout`:
- NORTH: 3 severity filter chips (multi-select, all active by default)
- CENTER: single `JBList` (no CardLayout — scope-slicing is done internally)

Filter chip state: `Set<SeverityTier>` where all 3 are active by default. Empty set → show "No severity filter selected" empty state. File severity tier mapping: RED→Critical, ORANGE→Warning, YELLOW+GREEN→Info. `severityColor()` is still available from `PoopRatingStrip.kt` (kept file).

- [ ] **Step 1: Create SmiIssuesPanel.kt**

```kotlin
package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.Hotspot
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.ui.UiThemeTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class SmiIssuesPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    enum class SeverityTier { CRITICAL, WARNING, INFO }

    sealed class IssueItem {
        data class FileHeader(val result: ComplexityResult) : IssueItem()
        data class HotspotRow(val hotspot: Hotspot, val fileUrl: String, val tier: SeverityTier) : IssueItem()
        object Empty : IssueItem()
        object NoFilter : IssueItem()
    }

    private val activeFilters = mutableSetOf(SeverityTier.CRITICAL, SeverityTier.WARNING, SeverityTier.INFO)
    private val chipCritical = filterChip("🔥 Critical (0)", SeverityTier.CRITICAL, UiThemeTokens.severityCritical)
    private val chipWarning  = filterChip("⚠ Warning (0)",  SeverityTier.WARNING,  UiThemeTokens.severityWarning)
    private val chipInfo     = filterChip("ℹ Info (0)",      SeverityTier.INFO,     UiThemeTokens.accentPrimary)

    private val listModel = DefaultListModel<IssueItem>()
    private val issueList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = -1
        cellRenderer = IssueRenderer()
    }

    private var cachedResults: List<ComplexityResult> = emptyList()
    private var cachedScope: DashboardScope = DashboardScope.PROJECT
    private var cachedCurrent: ComplexityResult? = null

    init {
        isOpaque = false

        val filterRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(6, 12)
            add(chipCritical); add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)))
            add(chipWarning);  add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)))
            add(chipInfo);     add(javax.swing.Box.createHorizontalGlue())
        }

        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val item = issueList.selectedValue ?: return
                when (item) {
                    is IssueItem.FileHeader  -> navigateToFile(item.result.fileUrl)
                    is IssueItem.HotspotRow  -> navigateToLine(item.fileUrl, item.hotspot.line)
                    else -> Unit
                }
            }
        })

        add(filterRow, BorderLayout.NORTH)
        add(JBScrollPane(issueList), BorderLayout.CENTER)
    }

    fun update(
        results: List<ComplexityResult>,
        scope: DashboardScope,
        currentResult: ComplexityResult?,
    ) {
        cachedResults = results
        cachedScope   = scope
        cachedCurrent = currentResult
        rebuildList()
    }

    private fun rebuildList() {
        listModel.removeAllElements()

        val effective = when (cachedScope) {
            DashboardScope.PROJECT      -> cachedResults
            DashboardScope.CURRENT_FILE -> listOfNotNull(cachedCurrent)
        }

        if (cachedScope == DashboardScope.CURRENT_FILE && cachedCurrent == null) {
            listModel.addElement(IssueItem.Empty)
            issueList.emptyText.text = "Open a file to analyze"
            return
        }

        if (activeFilters.isEmpty()) {
            listModel.addElement(IssueItem.NoFilter)
            return
        }

        var criticalCount = 0; var warningCount = 0; var infoCount = 0
        effective.forEach { r ->
            val tier = r.severity.toTier()
            when (tier) {
                SeverityTier.CRITICAL -> criticalCount++
                SeverityTier.WARNING  -> warningCount++
                SeverityTier.INFO     -> infoCount++
            }
            if (tier in activeFilters) {
                listModel.addElement(IssueItem.FileHeader(r))
                r.hotspots.forEach { h -> listModel.addElement(IssueItem.HotspotRow(h, r.fileUrl, tier)) }
            }
        }

        // Update chip labels with counts
        chipCritical.text = "🔥 Critical ($criticalCount)"
        chipWarning.text  = "⚠ Warning ($warningCount)"
        chipInfo.text     = "ℹ Info ($infoCount)"

        if (listModel.isEmpty) listModel.addElement(IssueItem.Empty)
    }

    private fun Severity.toTier(): SeverityTier = when (this) {
        Severity.RED    -> SeverityTier.CRITICAL
        Severity.ORANGE -> SeverityTier.WARNING
        Severity.YELLOW -> SeverityTier.INFO
        Severity.GREEN  -> SeverityTier.INFO
    }

    private fun filterChip(initialText: String, tier: SeverityTier, color: Color): JLabel =
        JLabel(initialText).apply {
            isOpaque = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            setChipActive(this, true, color)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (tier in activeFilters) activeFilters.remove(tier) else activeFilters.add(tier)
                    setChipActive(this@apply, tier in activeFilters, color)
                    rebuildList()
                }
            })
        }

    private fun setChipActive(chip: JLabel, active: Boolean, color: Color) {
        chip.background = if (active) withAlpha(color, 51) else withAlpha(color, 20)
        chip.foreground = color
        chip.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(withAlpha(color, if (active) 180 else 60), 1, true),
            JBUI.Borders.empty(3, 8),
        )
    }

    private fun withAlpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    private fun navigateToFile(url: String) {
        VirtualFileManager.getInstance().findFileByUrl(url)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    private fun navigateToLine(url: String, line: Int) {
        VirtualFileManager.getInstance().findFileByUrl(url)?.let {
            OpenFileDescriptor(project, it, (line - 1).coerceAtLeast(0), 0).navigate(true)
        }
    }

    private fun fileName(path: String) = path.substringAfterLast('/', path)
    private fun relativePath(path: String): String {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return path
        val norm = path.replace('\\', '/')
        return if (norm.startsWith("$base/")) norm.removePrefix("$base/") else norm
    }
    private fun shortenMiddle(v: String, max: Int): String {
        if (v.length <= max) return v
        val h = max / 2 - 2; val t = max - h - 3
        return v.take(h) + "..." + v.takeLast(t)
    }

    private inner class IssueRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component = when (val item = value as? IssueItem) {
            is IssueItem.FileHeader  -> buildFileHeader(item.result, isSelected, list)
            is IssueItem.HotspotRow  -> buildHotspotRow(item, isSelected, list)
            is IssueItem.Empty       -> buildMessage("No issues found.", isSelected, list)
            is IssueItem.NoFilter    -> buildMessage("No severity filter selected.", isSelected, list)
            null                     -> buildMessage("", isSelected, list)
        }

        private fun buildFileHeader(result: ComplexityResult, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else UiThemeTokens.bgCard
                val tierColor = result.severity.toTier().color()
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, tierColor),
                        BorderFactory.createLineBorder(UiThemeTokens.borderDefault),
                    ),
                    JBUI.Borders.empty(7, 10),
                )
                val nameLabel = JLabel(fileName(result.filePath)).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textPrimary
                }
                val pathLabel = JLabel(shortenMiddle(relativePath(result.filePath), 42)).apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                    font = font.deriveFont(font.size2D - 1f)
                }
                val center = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(nameLabel); add(pathLabel)
                }
                val badge = buildScoreBadge(result.score, result.severity.toTier())
                add(center, BorderLayout.CENTER)
                add(badge, BorderLayout.EAST)
            }

        private fun buildHotspotRow(item: IssueItem.HotspotRow, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(5, 24, 5, 10)
                val icon = item.tier.icon()
                add(JLabel("$icon  ${item.hotspot.methodName}").apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textPrimary
                }, BorderLayout.CENTER)
                add(JLabel("L${item.hotspot.line}  ·  ${item.hotspot.score}").apply {
                    foreground = if (isSelected) list.selectionForeground else UiThemeTokens.textSecondary
                    font = font.deriveFont(font.size2D - 0.5f)
                    border = JBUI.Borders.emptyLeft(8)
                }, BorderLayout.EAST)
            }

        private fun buildMessage(msg: String, isSelected: Boolean, list: JList<*>): JPanel =
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(8, 12)
                add(JLabel(msg).apply { foreground = UiThemeTokens.textSecondary }, BorderLayout.CENTER)
            }

        private fun buildScoreBadge(score: Int, tier: SeverityTier): JLabel = JLabel(score.toString()).apply {
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            foreground = tier.color()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(withAlpha(tier.color(), 100), 1, true),
                JBUI.Borders.empty(2, 7),
            )
            isOpaque = false
        }

        private fun SeverityTier.color(): Color = when (this) {
            SeverityTier.CRITICAL -> UiThemeTokens.severityCritical
            SeverityTier.WARNING  -> UiThemeTokens.severityWarning
            SeverityTier.INFO     -> UiThemeTokens.accentPrimary
        }

        private fun SeverityTier.icon(): String = when (this) {
            SeverityTier.CRITICAL -> "🔥"
            SeverityTier.WARNING  -> "⚠"
            SeverityTier.INFO     -> "ℹ"
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiIssuesPanel.kt
git commit -m "feat: add SmiIssuesPanel with severity filter chips and file tree"
```

---

## Task 8: SmiDashboardPanel

**Files:**
- Create: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiDashboardPanel.kt`

### Context
Main orchestrator. Implements `RefreshableComplexityView` (interface in `ide/services/RadarUiRefreshService.kt`). Retains the exact `buildSnapshot()` + `applySnapshot()` pattern from `ComplexityRadarDashboardPanel` — copy and adapt. Key changes:
- Replace `RadarHeaderPanel` → `SmiHeaderPanel`
- Replace `RadarToolbarPanel` → `SmiToolbarPanel`
- Replace `RadarNavPanel` → `SmiNavPanel` (scope toggle removed from nav)
- Replace `RadarOverviewPanel` → `SmiOverviewPanel` (scope toggle inside overview)
- Replace `RadarIssuesPanel` → `SmiIssuesPanel`
- Replace `RadarFooterPanel` → `SmiFooterPanel` (single-arg `setScanning`)
- In `runScanProject()`: replace `footerPanel.setScanning(msg, processed, total)` with `loadingOverlay.showProgress()` only
- `onScopeChanged()` now comes from `SmiOverviewPanel.onScopeChange` (not from nav panel)

- [ ] **Step 1: Create SmiDashboardPanel.kt**

```kotlin
package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService
import com.sqb.complexityradar.ide.services.ComplexityResultListener
import com.sqb.complexityradar.ide.services.RefreshableComplexityView
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.Timer
import kotlin.math.min
import kotlin.math.roundToInt

internal class SmiDashboardPanel(
    private val project: Project,
) : JPanel(BorderLayout()), RefreshableComplexityView {

    private val service = ComplexityRadarProjectService.getInstance(project)
    private val connection: MessageBusConnection = project.messageBus.connect(project)

    // Sub-panels
    private val headerPanel = SmiHeaderPanel(
        project = project,
        onExport = ::runExport,
        onToggleGutter = ::onToggleGutter,
        gutterLabelProvider = ::gutterButtonLabel,
    )
    private val toolbarPanel = SmiToolbarPanel(onScanClicked = ::runScanProject)
    private val navPanel = SmiNavPanel(onTabChange = ::onTabChanged)
    private val overviewPanel = SmiOverviewPanel(onScopeChange = ::onScopeChanged)
    private val issuesPanel = SmiIssuesPanel(project = project)
    private val footerPanel = SmiFooterPanel()
    private val loadingOverlay = LoadingOverlayPanel()

    // State
    private var currentTab = DashboardTab.OVERVIEW
    private var currentScope = DashboardScope.PROJECT
    private val refreshVersion = AtomicInteger(0)
    private val refreshDebounceTimer = Timer(300) { refreshViewNow() }.apply { isRepeats = false }

    private var hasLoadedOnce = false
    private var isViewRefreshing = false
    private var isAnalyzeRunning = false
    private var preferredFileUrl: String? = null
    private var lastStatusMessage: String? = null
    private val pendingUrls = linkedSetOf<String>()
    private var analysisTargetCount = 0
    private var analysisCompletedCount = 0

    private lateinit var contentCards: JPanel
    private lateinit var contentCardLayout: CardLayout

    init {
        preferredSize = Dimension(980, 720)

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerPanel)
            add(toolbarPanel)
            add(navPanel)
        }, BorderLayout.NORTH)

        contentCardLayout = CardLayout()
        contentCards = JPanel(contentCardLayout).apply {
            isOpaque = false
            add(overviewPanel, DashboardTab.OVERVIEW.name)
            add(issuesPanel,   DashboardTab.ISSUES.name)
        }

        add(JPanel().apply {
            layout = OverlayLayout(this)
            add(contentCards.apply { alignmentX = 0.5f; alignmentY = 0.5f })
            add(loadingOverlay.apply { alignmentX = 0.5f; alignmentY = 0.5f })
        }, BorderLayout.CENTER)

        add(footerPanel, BorderLayout.SOUTH)

        // Set initial plugin version in footer
        val pluginVersion = com.intellij.ide.plugins.PluginManagerCore
            .getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.sqb.complexityradar"))
            ?.version ?: ""
        footerPanel.setVersion(pluginVersion)

        showContentCard()
        refreshViewNow()

        connection.subscribe(ComplexityResultListener.TOPIC, ComplexityResultListener { batch ->
            onResultsUpdated(batch.fileUrls)
            scheduleRefreshView()
        })
    }

    // ── RefreshableComplexityView ──────────────────────────────────────────────

    override fun refreshView() { scheduleRefreshView() }

    override fun revealFile(file: VirtualFile?) {
        preferredFileUrl = file?.url
        navPanel.selectTab(DashboardTab.OVERVIEW)
        currentTab = DashboardTab.OVERVIEW
        currentScope = DashboardScope.CURRENT_FILE
        overviewPanel.selectScope(DashboardScope.CURRENT_FILE)
        showContentCard()
        if (file != null) service.getResult(file)?.let { hasLoadedOnce = true }
        refreshViewNow()
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun onTabChanged(tab: DashboardTab) {
        currentTab = tab
        showContentCard()
    }

    private fun onScopeChanged(scope: DashboardScope) {
        currentScope = scope
        // Re-render both panels for new scope
        scheduleRefreshView()
    }

    private fun showContentCard() {
        contentCardLayout.show(contentCards, currentTab.name)
    }

    // ── Refresh (same pattern as ComplexityRadarDashboardPanel) ───────────────

    private fun scheduleRefreshView() {
        if (project.isDisposed) return
        if (ApplicationManager.getApplication().isDispatchThread) {
            refreshDebounceTimer.restart()
        } else {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) refreshDebounceTimer.restart()
            }, project.disposed)
        }
    }

    private fun refreshViewNow() {
        val refreshId = refreshVersion.incrementAndGet()
        val targetFileUrl = preferredFileUrl ?: currentEditorFileUrl()
        isViewRefreshing = true
        if (!hasLoadedOnce) loadingOverlay.showIndeterminate("Initializing SMI Detector...")
        updateLoadingUi()
        AppExecutorUtil.getAppExecutorService().execute {
            val snapshot = runCatching { buildSnapshot(targetFileUrl) }.getOrElse {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || refreshId != refreshVersion.get()) return@invokeLater
                    isViewRefreshing = false
                    updateLoadingUi()
                }
                return@execute
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || refreshId != refreshVersion.get()) return@invokeLater
                applySnapshot(snapshot)
            }
        }
    }

    private fun buildSnapshot(targetFileUrl: String?): FocusedViewSnapshot {
        val allResults = service.allResults()
        val targetFile = targetFileUrl?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
        val currentResult =
            targetFileUrl?.let { target -> allResults.firstOrNull { it.fileUrl == target } }
                ?: allResults.firstOrNull { it.fileUrl == currentEditorFileUrl() }
        val sortedResults = allResults.sortedWith(
            compareByDescending<ComplexityResult> { it.priority }
                .thenByDescending { it.score }
                .thenBy { it.filePath },
        )
        val averageScore = allResults.map { it.score }.average().takeIf { !it.isNaN() } ?: 0.0
        val aggregateValues = FactorType.entries.associateWith { factor ->
            if (allResults.isEmpty()) 0.0
            else allResults.map { r -> r.contributions.firstOrNull { it.type == factor }?.normalized ?: 0.0 }.average()
        }
        val strongestFactors = aggregateValues.entries.sortedByDescending { it.value }.take(2).map { it.key.displayName }
        val projectSummary = if (allResults.isEmpty()) "" else buildString {
            append("Top factors: ")
            append(strongestFactors.joinToString(" + ").ifBlank { "balanced" })
        }
        return FocusedViewSnapshot(
            currentResult = currentResult,
            projectResults = allResults,
            topFiles = sortedResults.take(5),
            averageScore = averageScore,
            redCount = allResults.count { it.severity == Severity.RED },
            aggregateValues = aggregateValues,
            aggregateSeverity = service.configFor().severityFor(averageScore.roundToInt()),
            projectSummary = projectSummary,
            targetFileUrl = targetFileUrl,
            targetFilePath = targetFile?.path,
        )
    }

    private fun applySnapshot(snapshot: FocusedViewSnapshot) {
        navPanel.updateBadge(snapshot.redCount)
        overviewPanel.update(snapshot, currentScope)
        issuesPanel.update(snapshot.projectResults, currentScope, snapshot.currentResult)
        hasLoadedOnce = true
        isViewRefreshing = false
        if (preferredFileUrl != null && snapshot.currentResult?.fileUrl == preferredFileUrl) {
            preferredFileUrl = null
        }
        updateLoadingUi()
        updateFooterStatus(snapshot.projectResults.size)
    }

    // ── Scan ───────────────────────────────────────────────────────────────────

    private fun runScanProject() {
        if (isAnalyzeRunning) return
        isAnalyzeRunning = true
        pendingUrls.clear()
        analysisTargetCount = 0
        analysisCompletedCount = 0
        loadingOverlay.showProgress("Queueing project scan...", 0, 0)
        updateLoadingUi()
        footerPanel.setScanning("Scanning...")   // single-arg — no progress % in footer
        AppExecutorUtil.getAppExecutorService().execute {
            val reportResult = runCatching {
                service.queueProjectAnalysis { processed, total ->
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || !isAnalyzeRunning) return@invokeLater
                        // Progress goes to overlay only, NOT to footer
                        loadingOverlay.showProgress("Queueing project scan...", processed, total)
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                reportResult.fold(
                    onSuccess = { report ->
                        if (report.queuedCount <= 0) {
                            lastStatusMessage = "No analyzable files queued."
                            pendingUrls.clear()
                            loadingOverlay.showIndeterminate("No analyzable files queued.")
                            footerPanel.setIdle("No analyzable files queued.")
                        } else {
                            pendingUrls.clear()
                            pendingUrls.addAll(report.queuedFileUrls)
                            analysisTargetCount = report.queuedCount
                            analysisCompletedCount = 0
                            lastStatusMessage = "Queued ${report.queuedCount} files. Running analysis..."
                            loadingOverlay.showProgress("Analyzing ${report.queuedCount} files...", 0, report.queuedCount)
                        }
                    },
                    onFailure = { t ->
                        lastStatusMessage = "Project scan failed."
                        loadingOverlay.showIndeterminate("Project scan failed.")
                        footerPanel.setIdle("Scan failed: ${t.message}")
                        pendingUrls.clear()
                    },
                )
                isAnalyzeRunning = false
                updateLoadingUi()
            }
        }
    }

    private fun onResultsUpdated(fileUrls: Collection<String>) {
        if (pendingUrls.isEmpty()) return
        var changed = false
        fileUrls.forEach { url ->
            if (pendingUrls.remove(url)) { analysisCompletedCount += 1; changed = true }
        }
        if (!changed) return
        if (pendingUrls.isEmpty()) {
            lastStatusMessage = "Analysis complete — $analysisTargetCount files"
            footerPanel.setIdle("Analysis Complete ($analysisTargetCount files)")
            loadingOverlay.isVisible = false
        } else {
            // Progress to overlay only
            loadingOverlay.showProgress("Analyzing... $analysisCompletedCount / $analysisTargetCount", analysisCompletedCount, analysisTargetCount)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun runExport() {
        val path = service.exportReports()
        if (path == null) Messages.showErrorDialog(project, "Failed to export report.", "SMI Detector")
        else Messages.showInfoMessage(project, "Report exported to $path", "SMI Detector")
    }

    private fun gutterButtonLabel(): String =
        if (service.uiSettings().showGutterIcons) "Hide Gutter Icons" else "Show Gutter Icons"

    private fun onToggleGutter() {
        val uiSettings = project.getService(com.sqb.complexityradar.settings.ComplexityUiSettingsService::class.java)
        uiSettings.update { it.showGutterIcons = !it.showGutterIcons }
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
    }

    private fun updateLoadingUi() {
        val visible = when {
            isAnalyzeRunning -> true
            !hasLoadedOnce && isViewRefreshing -> { loadingOverlay.showIndeterminate("Initializing SMI Detector..."); true }
            else -> false
        }
        loadingOverlay.isVisible = visible
        toolbarPanel.setScanRunning(isAnalyzeRunning)
    }

    private fun updateFooterStatus(fileCount: Int) {
        val msg = lastStatusMessage
        if (msg != null) footerPanel.setIdle(msg)
        else footerPanel.setIdle("Analysis Complete ($fileCount files)")
    }

    private fun currentEditorFileUrl(): String? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.url
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/SmiDashboardPanel.kt
git commit -m "feat: add SmiDashboardPanel as main ToolWindow container"
```

---

## Task 9: Wire Factory + plugin.xml + Delete Old Files

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/ComplexityRadarToolWindowFactory.kt`
- Delete: 7 old panel files

### Context
The ToolWindow `id` in plugin.xml is `"Complexity Radar"` — changing it renames the sidebar tab. The factory just needs to instantiate `SmiDashboardPanel` instead of `ComplexityRadarDashboardPanel`. Old panel files are deleted last, after the new panels compile cleanly.

- [ ] **Step 1: Update plugin.xml ToolWindow id**

In `src/main/resources/META-INF/plugin.xml`, find:

```xml
id="Complexity Radar"
```

Replace with:

```xml
id="SMI Detector"
```

- [ ] **Step 2: Update ComplexityRadarToolWindowFactory.kt**

Replace the factory body to use `SmiDashboardPanel`:

```kotlin
package com.sqb.complexityradar.ide.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class ComplexityRadarToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = SmiDashboardPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 3: Verify compilation before deletion**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL` (old files still exist but are now unused)

- [ ] **Step 4: Delete old panel files**

```bash
cd /Users/sqb/projects/code_analy_plugin
rm src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/ComplexityRadarDashboardPanel.kt
rm src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarHeaderPanel.kt
rm src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarToolbarPanel.kt
rm src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarNavPanel.kt
rm src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarOverviewPanel.kt
rm src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarIssuesPanel.kt
rm src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarFooterPanel.kt
```

- [ ] **Step 5: Full build to confirm no broken references**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL` with no compilation errors. Test failures are acceptable if any tests reference old class names (see Step 6).

- [ ] **Step 6: Fix any test references (if Step 5 fails)**

Search for references to deleted class names in test files:

```bash
grep -r "ComplexityRadarDashboardPanel\|RadarHeaderPanel\|RadarToolbarPanel\|RadarNavPanel\|RadarOverviewPanel\|RadarIssuesPanel\|RadarFooterPanel" src/test/
```

If found, update or remove the affected test file references.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: wire SMI Detector factory, update plugin.xml, remove old panels"
```

---

## Task 10: Smoke Test in IDE Sandbox

**Files:** None (manual verification)

- [ ] **Step 1: Launch IDE sandbox**

```bash
./gradlew runIde
```

Wait for IntelliJ IDEA to open.

- [ ] **Step 2: Open a project with Java/Kotlin files**

Use File → Open to open any project containing `.java` or `.kt` files.

- [ ] **Step 3: Verify Success Criteria**

Check each item:

| # | Criterion | Pass/Fail |
|---|---|---|
| 1 | ToolWindow tab shows "SMI Detector" (not "Complexity Radar") | |
| 2 | Header shows "SMI Detector" label | |
| 3 | "Scan Project" button is pill-shaped, amber colored | |
| 4 | Tabs are "Overview" and "Issues" with underline style | |
| 5 | Issues tab badge appears after scan (count of RED files) | |
| 6 | Overview: Scope toggle → Score card → Metrics + radar (no file list) | |
| 7 | Score card shows gradient top bar + large number + severity pill | |
| 8 | Radar chart shows "Complexity", "Duplication", "Nesting", "Smells" labels | |
| 9 | Issues: severity filter chips visible, multi-select works | |
| 10 | Clicking Issues list navigates to file/line | |
| 11 | Footer shows "Analysis Complete (N files)" after scan | |
| 12 | Light/dark theme toggle: all panels render correctly | |

- [ ] **Step 3: Commit any fixes found during smoke test**

```bash
git add -A
git commit -m "fix: smoke test corrections for SMI Detector UI"
```

---

## Done

All 10 tasks complete → SMI Detector UI migration is live. Run `./gradlew buildPlugin` to produce a distributable ZIP.
