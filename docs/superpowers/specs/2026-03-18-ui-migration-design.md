# UI Migration Design: Complexity Radar → SMI Detector
**Date:** 2026-03-18
**Status:** Approved

---

## Overview

Migrate the Complexity Radar IntelliJ plugin ToolWindow UI from its current "engineering panel" aesthetic to the new "SMI Detector" product design. The migration is **visual-only**: the core scoring engine (5 dimensions, all adapters, cache, services) remains unchanged. Only the ToolWindow presentation layer is rewritten.

---

## Scope

### In scope
- Rename plugin branding to "SMI Detector" (ToolWindow title, header label)
- Rewrite all `ide/toolwindow/` panel files to match new design
- Update `UiThemeTokens.kt` with new color tokens
- Rename radar chart axis labels (visual only, no scoring change)
- Add Issues tab severity filter badges
- Add Issues count badge on Issues tab
- Replace current score "hero card" with large score card + gradient bar + severity pill
- Replace Fix First file list with radar chart section in Overview
- Move Scope toggle (Project/Current File) into Overview content area
- Change Tab style from SegmentedToggle to underline style

### Out of scope
- Core scoring engine changes
- New analysis dimensions (Duplication, Magic Numbers as separate metrics)
- Radar chart axis count change (stays at 5)
- Settings/configuration UI
- Editor banner, gutter icons, project view decorator

---

## Architecture

File structure is preserved; files are renamed and rewritten:

```
ComplexityRadarToolWindowFactory   ← update title to "SMI Detector"
└── SmiDashboardPanel              ← replaces ComplexityRadarDashboardPanel
    ├── SmiHeaderPanel             ← bug icon + "SMI Detector" + ⚙️ + ⋮
    ├── SmiToolbarPanel            ← pill-shaped "Scan Project" only (remove Refresh button)
    ├── SmiNavPanel                ← underline-style tabs (Overview / Issues + badge)
    ├── SmiOverviewPanel           ← score card + scope toggle + radar chart
    ├── SmiIssuesPanel             ← severity filters + file tree + hotspot rows
    └── SmiFooterPanel             ← ✅ icon + status text + version
```

Retained files (no changes):
- `RadarChartPanel.kt` — only axis label strings changed
- `SegmentedToggle.kt` — reused for Scope toggle with new styling
- `PoopRatingStrip.kt` — removed from Overview, retained for potential future use
- `FocusedViewSnapshot.kt` — unchanged
- `LoadingOverlayPanel.kt` — unchanged
- `UiThemeTokens.kt` — extended with new tokens

---

## Theme Tokens

### New tokens added to `UiThemeTokens.kt`

| Token | Light | Dark | Usage |
|---|---|---|---|
| `severityCritical` | `#8B4513` | `#8B4513` | Critical badge bg/icon |
| `severityWarning` | `#CD853F` | `#CD853F` | Warning badge |
| `severityInfo` | `#A87B44` | `#A87B44` | Info badge (reuses accent) |
| `gradientCritical` | `#8B4513` | `#8B4513` | Score card gradient left |
| `gradientClean` | `#57965C` | `#57965C` | Score card gradient right |
| `tabUnderline` | `#A87B44` | `#A87B44` | Active tab underline |

### Existing tokens retained
- `accentPrimary` (#A87B44 dark) — primary button, score text, scope active bg
- `textPrimary`, `textSecondary` — unchanged
- `bgCard`, `bgHover`, `borderDefault` — unchanged
- `btnPrimaryBg/Fg/Hover/Border` — reused for Scan button
- `footerBg`, `footerBorder` — unchanged
- `severityGreen` (#57965C) — footer success icon

---

## Component Specifications

### SmiHeaderPanel
- Layout: `BorderLayout`
- WEST: `AllIcons.Actions.Find` (or custom bug icon 16px, `accentPrimary`) + "SMI Detector" bold label
- EAST: Settings icon button (26×26, hover fill `btnIconHover`) + MoreVertical icon button (26×26)
- More menu items: "Export Report", "Show/Hide Gutter Icons"
- Border: `empty(10, 12, 6, 12)`

### SmiToolbarPanel
- Layout: `BorderLayout`
- WEST: Single pill-shaped "▷ Scan Project" primary button
  - Background: `btnPrimaryBg` (#A87B44)
  - Foreground: white
  - Corner radius: 20px (fully rounded)
  - Font: Bold
  - Scanning state: disabled, text → "Scanning..."
  - Border: compound (line + empty padding 6, 16)
- Removes the "Refresh" secondary button from old design

### SmiNavPanel
- Layout: Custom `paintComponent` — two tab labels side by side
- Tab items: "Overview", "Issues"
- Active tab: bottom 3px underline in `tabUnderline` (#A87B44), `textPrimary` color, bold
- Inactive tab: no underline, `textSecondary` color
- Issues tab: circular badge (16px diameter, `#8B4513` bg, white text) overlaid top-right of label, showing `redCount` (files with RED severity)
- Border: bottom separator line (`footerBorder` color) + empty padding (4, 12, 0, 12)
- Fires `onTabChange(DashboardTab)` callback

### SmiOverviewPanel (replaces RadarOverviewPanel)
Layout: `BoxLayout.Y_AXIS` in a `JScrollPane`

**Scope Toggle** (top):
- Reuses `SegmentedToggle` with restyled tokens:
  - Active: `accentPrimary` fill + white text
  - Inactive: transparent + `borderDefault` outline + `textSecondary`
- Options: "Project", "Current File"
- Margin: `empty(8, 12, 8, 12)`

**Score Card**:
- Background: `bgCard`
- Border: `borderDefault` rounded 8px + empty padding (0, 16, 16, 16)
- Top gradient bar: 3px tall `GradientPaint` from `gradientCritical` (#8B4513) → `#CD853F` → `gradientClean` (#57965C), full width
- Label: "PROJECT SMI" or "FILE SMI" — small caps, `textSecondary`
- Score: 36pt bold, `accentPrimary`, centered
- "/100" suffix: 14pt, `textSecondary`, inline
- Severity pill badge:
  - CRITICAL (score 76–100): bg `#8B4513/30`, text+icon `#CD853F`, label "🔥 Critical"
  - WARNING (score 51–75): bg `#CD853F/30`, text `#CD853F`, label "⚠ Warning"
  - CAUTION (score 26–50): bg `#A87B44/30`, text `#A87B44`, label "● Caution"
  - CLEAN (score 0–25): bg `#57965C/30`, text `#57965C`, label "✓ Clean"
  - Shape: rounded pill, padding (4, 12), font bold -1pt

**Metrics Section**:
- Header row: "METRICS" (bold, `textPrimary`) left + "Project Average"/"File Score" (`textSecondary`) right
- `RadarChartPanel` below (existing component, no structural change)
- Axis label renames (strings only):
  - Size → "Size"
  - Control Flow → "Complexity"
  - Nesting → "Nesting"
  - Domain Coupling → "Duplication"
  - Readability → "Smells"

**Removed from Overview**: Fix First file list, TopFileRenderer, PoopRatingStrip

### SmiIssuesPanel (replaces RadarIssuesPanel)
Layout: `BorderLayout`

**NORTH — Severity Filter Row**:
- `FlowLayout` LEFT, 3 clickable badge chips:
  - **Critical (N)**: bg `#8B4513/20`, text/border `#8B4513`, Flame icon, label "Critical (N)"
  - **Warning (N)**: bg `#CD853F/20`, text/border `#CD853F`, AlertTriangle icon, label "Warning (N)"
  - **Info (N)**: bg `#A87B44/20`, text/border `#A87B44`, Info icon, label "Info (N)"
- Active filter: filled background (full opacity), deselected: 20% opacity bg
- All filters selected = show all (default state)
- Severity mapping: RED → Critical, ORANGE → Warning, YELLOW+GREEN → Info
- Padding: `empty(6, 12, 6, 12)`

**CENTER — JScrollPane > JList**:
- `IssueItem` sealed class (preserved): `FileHeader`, `HotspotRow`, `Empty`
- FileHeader cell:
  - Left 4px color bar (severity color)
  - File name bold + relative path secondary
  - Right: score badge colored by severity (same color system)
- HotspotRow cell:
  - Left 24px indent
  - Severity icon: 🔥 (`#8B4513`) / ⚠ (`#CD853F`) / ℹ (`#A87B44`)
  - Method name + "L{lineNumber}" secondary
  - Hover: show Zap (⚡) action icon on right edge
- Click FileHeader → navigate to file
- Click HotspotRow → navigate to line
- Filter chips control visible items (re-filter list on chip toggle)

### SmiFooterPanel (replaces RadarFooterPanel)
- Layout: `BorderLayout`
- WEST: Icon + status label
  - Idle/complete: green CheckCircle icon (16px, `#57965C`) + "Analysis Complete (N files)"
  - Scanning: spinning RefreshCw icon (`accentPrimary`) + "Scanning..."
  - Progress bar: hidden (remove from footer, scanning state shown in LoadingOverlayPanel)
- EAST: version label `textSecondary`, small
- Background: `footerBg`, top border `footerBorder`, padding `empty(5, 12, 5, 12)`

---

## Data Flow (unchanged)

```
File open/edit → ComplexityRadarProjectService (debounced queue)
  → LanguageAdapter.analyze(PsiFile) → FileAstSummary
  → ComplexityScorer.score(summary, config) → ComplexityResult
  → ComplexityResultStore (cache L1/L2/L3)
  → RadarUiRefreshService → SmiDashboardPanel.refresh(FocusedViewSnapshot)
```

`FocusedViewSnapshot` is unchanged. `SmiDashboardPanel` reads `redCount`, `averageScore`, `topFiles`, `currentResult`, `aggregateValues` from snapshot exactly as before.

---

## Issues Severity Mapping

The existing `SeverityLevel` enum maps to new UI severity tiers:

| SeverityLevel | UI Tier | Color | Icon |
|---|---|---|---|
| RED | Critical | `#8B4513` / `#CD853F` | 🔥 Flame |
| ORANGE | Warning | `#CD853F` | ⚠ AlertTriangle |
| YELLOW | Info | `#A87B44` | ℹ Info |
| GREEN | Info | `#A87B44` | ℹ Info |

Issues count badge on Issues tab = count of `FileHeader` items with RED severity (`redCount` from snapshot).

---

## Files to Create/Modify

| Action | File |
|---|---|
| Modify | `ComplexityRadarToolWindowFactory.kt` — title + instantiate SmiDashboardPanel |
| Create | `SmiDashboardPanel.kt` — replaces ComplexityRadarDashboardPanel |
| Create | `SmiHeaderPanel.kt` |
| Create | `SmiToolbarPanel.kt` |
| Create | `SmiNavPanel.kt` |
| Create | `SmiOverviewPanel.kt` |
| Create | `SmiIssuesPanel.kt` |
| Create | `SmiFooterPanel.kt` |
| Modify | `UiThemeTokens.kt` — add 6 new tokens |
| Modify | `RadarChartPanel.kt` — rename 5 axis labels |
| Delete | `RadarHeaderPanel.kt` |
| Delete | `RadarToolbarPanel.kt` |
| Delete | `RadarNavPanel.kt` |
| Delete | `RadarOverviewPanel.kt` |
| Delete | `RadarIssuesPanel.kt` |
| Delete | `RadarFooterPanel.kt` |
| Delete | `ComplexityRadarDashboardPanel.kt` |

---

## Success Criteria

1. ToolWindow shows "SMI Detector" branding with bug icon
2. Score card displays large score (36pt) + gradient bar + severity pill
3. Overview tab shows Scope toggle + Score card + Metrics header + Radar chart (no file list)
4. Issues tab shows severity filter badges + file tree with hotspot rows
5. Issues tab badge shows count of RED-severity files
6. Scan button is pill-shaped, single button (no Refresh)
7. All existing analysis functionality (scan, cache, navigation, settings) works unchanged
8. Light and dark theme both render correctly
