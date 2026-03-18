# UI Migration Design: Complexity Radar → SMI Detector
**Date:** 2026-03-18
**Status:** Approved

---

## Overview

Migrate the Complexity Radar IntelliJ plugin ToolWindow UI from its current "engineering panel" aesthetic to the new "SMI Detector" product design. The migration is **visual-only**: the core scoring engine (5 dimensions, all adapters, cache, services) remains unchanged. Only the ToolWindow presentation layer is rewritten.

---

## Scope

### In scope
- Rename plugin branding to "SMI Detector" (ToolWindow title in plugin.xml, header label)
- Rewrite all `ide/toolwindow/` panel files to match new design
- Update `UiThemeTokens.kt` with new color tokens
- Rename radar chart axis labels (visual only, no scoring change)
- Add Issues tab severity filter badges with multi-select behavior
- Add Issues count badge on Issues tab
- Replace current score "hero card" with large score card + gradient bar + severity pill
- Remove Fix First file list from Overview; show radar chart only
- Move Scope toggle (Project/Current File) into Overview content area
- Change Tab style from SegmentedToggle to underline style
- Issues panel shows all `projectResults` (not just top 5)

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
plugin.xml                         ← update toolWindow displayName to "SMI Detector"
ComplexityRadarToolWindowFactory   ← update to instantiate SmiDashboardPanel
└── SmiDashboardPanel              ← replaces ComplexityRadarDashboardPanel
    ├── SmiHeaderPanel             ← bug icon + "SMI Detector" + ⚙️ + ⋮
    ├── SmiToolbarPanel            ← pill-shaped "Scan Project" only (remove Refresh button)
    ├── SmiNavPanel                ← underline-style tabs (Overview / Issues + badge)
    ├── SmiOverviewPanel           ← scope toggle + score card + radar chart
    ├── SmiIssuesPanel             ← severity filters + file tree + hotspot rows
    └── SmiFooterPanel             ← ✅ icon + status text + version
```

Retained files (no changes):
- `RadarChartPanel.kt` — only axis label strings changed
- `SegmentedToggle.kt` — reused for Scope toggle with new styling
- `PoopRatingStrip.kt` — removed from Overview, retained for potential future use
- `FocusedViewSnapshot.kt` — unchanged
- `LoadingOverlayPanel.kt` — unchanged

---

## Theme Tokens

### New tokens added to `UiThemeTokens.kt`

All new tokens are intentionally theme-invariant (same value for light and dark) to match the design mockup which targets a dark IDE aesthetic. Light-mode variants can be refined later if needed.

| Token | Light | Dark | Usage |
|---|---|---|---|
| `severityCritical` | `#8B4513` | `#8B4513` | Critical badge bg/icon |
| `severityWarning` | `#CD853F` | `#CD853F` | Warning badge |
| `severityGreen` | `#57965C` | `#57965C` | Footer success icon, Clean severity |
| `gradientCritical` | `#8B4513` | `#8B4513` | Score card gradient left |
| `gradientClean` | `#57965C` | `#57965C` | Score card gradient right |
| `tabUnderline` | `#A87B44` | `#A87B44` | Active tab underline |

**Note:** Info badge color uses `accentPrimary` directly (no new token needed — `accentPrimary` dark is `#A87B44`). Do not create a separate `severityInfo` token.

### Existing tokens retained (unchanged)
- `accentPrimary` — primary button, score text, scope active bg
- `textPrimary`, `textSecondary` — unchanged
- `bgCard`, `bgHover`, `borderDefault` — unchanged
- `btnPrimaryBg/Fg/Hover/Border` — reused for Scan button
- `footerBg`, `footerBorder` — unchanged

---

## Component Specifications

### SmiDashboardPanel

Replaces `ComplexityRadarDashboardPanel`. Implements `RefreshableComplexityView`.

**Layout:** `BorderLayout`
- NORTH: `BoxLayout.Y_AXIS` — SmiHeaderPanel, SmiToolbarPanel, SmiNavPanel
- CENTER: `CardLayout` — SmiOverviewPanel card, SmiIssuesPanel card (+ LoadingOverlayPanel overlay)
- SOUTH: SmiFooterPanel

**State:**
- `currentTab: DashboardTab` (OVERVIEW | ISSUES)
- `currentScope: DashboardScope` (PROJECT | CURRENT_FILE)
- `isAnalyzeRunning: Boolean`

**Callbacks / event wiring:**
- `SmiNavPanel.onTabChange` → `currentTab` update → show correct card, update issues badge
- `SmiOverviewPanel.onScopeChange` → `currentTab` update + `currentScope` update → re-render both Overview and Issues for the new scope
- `SmiToolbarPanel.onScanClicked` → trigger project analysis, call `setScanRunning(true)`, footer `setScanning()`
- On `ComplexityResultListener` event → build `FocusedViewSnapshot` → call `refresh(snapshot)` on all sub-panels

**`RefreshableComplexityView` interface contract (unchanged):**
- `SmiDashboardPanel` implements `refreshView()` and `revealFile(file: VirtualFile?)` exactly as before
- Internally retains the existing `buildSnapshot()` + `applySnapshot(snapshot)` pattern from `ComplexityRadarDashboardPanel`
- No new method is added to the interface — `RadarUiRefreshService` calls `refreshView()` → `SmiDashboardPanel` builds `FocusedViewSnapshot` internally on a background thread, then calls `applySnapshot()` on EDT

**`applySnapshot(snapshot: FocusedViewSnapshot)` (replaces old internal method):**
- Updates `SmiNavPanel` badge count (`snapshot.redCount`)
- Calls `SmiOverviewPanel.update(snapshot, currentScope)`
- Calls `SmiIssuesPanel.update(snapshot.projectResults, currentScope, snapshot.currentResult)`
  - `SmiIssuesPanel` owns scope-slicing internally: it always receives the full `projectResults`, and when `scope == CURRENT_FILE` it uses `listOfNotNull(currentResult)` internally
- Calls `SmiFooterPanel.setIdle("Analysis Complete (${snapshot.projectResults.size} files)")`

**`runScanProject()` scan progress loop:**
- On scan start: call `footer.setScanning("Scanning...")` once (single-arg)
- Per-file progress callbacks: call `LoadingOverlayPanel.showProgress(message, processed, total)` only — **do NOT call `footer.setScanning(msg, processed, total)`** (three-arg form is removed)
- On scan complete: call `footer.setIdle(...)` and hide `LoadingOverlayPanel`

**`revealFile(file: VirtualFile?)` implementation:**
- Set `currentScope = CURRENT_FILE`
- Set `currentTab = OVERVIEW`
- Call `SmiNavPanel.selectTab(DashboardTab.OVERVIEW)`
- Call `SmiOverviewPanel.selectScope(DashboardScope.CURRENT_FILE)`
- Trigger refresh via `refreshView()`

---

### SmiHeaderPanel
- Layout: `BorderLayout`
- WEST: `AllIcons.Debugger.Db_exception_breakpoint` (or similar bug-like icon, 16px, tinted `accentPrimary`) + "SMI Detector" bold label, gap 6px
- EAST: Settings icon button (26×26, hover fill `btnIconHover`) + MoreVertical icon button (26×26)
- More menu items: "Export Report", "Show/Hide Gutter Icons"
- Border: `empty(10, 12, 6, 12)`

---

### SmiToolbarPanel
- Layout: `BorderLayout`
- WEST: Single pill-shaped "▷ Scan Project" primary button
  - Background: `btnPrimaryBg` (#A87B44)
  - Foreground: white
  - Corner radius: 20px (fully rounded, custom `paintComponent`)
  - Font: Bold
  - Normal state: text "▷ Scan Project"
  - Scanning state: disabled, text → "Scanning..."
  - Border: compound (line + empty padding 6, 16)
- API: `fun setScanRunning(running: Boolean)` — disables button and changes text
- Fires `onScanClicked: () -> Unit` callback

---

### SmiNavPanel
- Layout: Custom `paintComponent` — two tab labels drawn side by side with equal spacing
- Tab items: "Overview", "Issues"
- Active tab: bottom 3px underline in `tabUnderline`, `textPrimary` color, bold font
- Inactive tab: no underline, `textSecondary` color, normal font
- Issues tab: circular badge (16px diameter, `#8B4513` bg, white 10pt text) drawn top-right of "Issues" label; hidden when count is 0
- Border: bottom separator line (`footerBorder` color) + empty padding (4, 12, 0, 12)
- API:
  - `fun selectTab(tab: DashboardTab)` — programmatically selects a tab (used by `revealFile`)
  - `fun updateBadge(count: Int)` — updates Issues badge count
  - `var onTabChange: (DashboardTab) -> Unit` — fires on user click

---

### SmiOverviewPanel

Layout: `JScrollPane` wrapping `BoxLayout.Y_AXIS` panel

**Scope Toggle** (top of content):
- Reuses `SegmentedToggle` component, restyled:
  - Active segment: `accentPrimary` fill + white text
  - Inactive segment: transparent bg + `borderDefault` line border + `textSecondary` text
- Options: ["Project", "Current File"]
- Margin wrapper: `empty(8, 12, 8, 12)`
- API: `var onScopeChange: (DashboardScope) -> Unit` — fires when user switches scope
- `fun selectScope(scope: DashboardScope)` — programmatically selects scope (used by `revealFile`)

**Score Card**:
- Background: `bgCard`, rounded 8px border (`borderDefault`), empty padding (0, 16, 16, 16)
- Top gradient bar: custom-painted `GradientPaint`, 3px tall, full width, colors: `gradientCritical` → `#CD853F` → `gradientClean`
- Label row: "PROJECT SMI" or "FILE SMI" depending on scope — small caps, 10pt, `textSecondary`, centered
- Score row: score value 36pt bold `accentPrimary` + " /100" 14pt `textSecondary`, centered
- Severity pill badge (centered below score) — maps `Severity` enum:
  - `Severity.RED` → bg `new Color(0x8B4513).withAlpha(77)`, text+icon `#CD853F`, label "🔥 Critical"
  - `Severity.ORANGE` → bg `new Color(0xCD853F).withAlpha(77)`, text `#CD853F`, label "⚠ Warning"
  - `Severity.YELLOW` → bg `new Color(0xA87B44).withAlpha(77)`, text `accentPrimary`, label "● Caution"
  - `Severity.GREEN` → bg `new Color(0x57965C).withAlpha(77)`, text `#57965C`, label "✓ Clean"
  - Shape: rounded pill, padding (4, 12), bold -1pt font

**Metrics Section**:
- Header row: "METRICS" (bold, `textPrimary`, left) + right-aligned label "Project Average" or "File Score" (`textSecondary`)
- `RadarChartPanel` below — existing component
- Axis label renames — edit `labelFor(factor: FactorType)` in `RadarChartPanel.kt` (currently returns abbreviated strings):

| FactorType | Current string | New string | Note |
|---|---|---|---|
| `SIZE` | `"Size"` | `"Size"` | unchanged |
| `CONTROL_FLOW` | `"Flow"` | `"Complexity"` | intentional product rename |
| `NESTING` | `"Nest"` | `"Nesting"` | expand abbreviation |
| `DOMAIN_COUPLING` | `"Domain"` | `"Duplication"` | intentional product rename; metric still measures import coupling |
| `READABILITY` | `"Read"` | `"Smells"` | intentional product rename |

**API:**
- `fun update(snapshot: FocusedViewSnapshot, scope: DashboardScope)` — re-renders entire panel for new scope/data

---

### SmiIssuesPanel

Layout: `BorderLayout`

**Data source:** receives full `snapshot.projectResults: List<ComplexityResult>` (all analyzed files, not just top 5). In Current File scope, receives single-file list from `snapshot.currentResult`.

**NORTH — Severity Filter Row:**
- `FlowLayout(LEFT, 6, 6)` with 3 toggle chips:
  - **Critical (N)**: bg `#8B4513/20%`, text/border `#8B4513`, label "🔥 Critical (N)"
  - **Warning (N)**: bg `#CD853F/20%`, text/border `#CD853F`, label "⚠ Warning (N)"
  - **Info (N)**: bg `#A87B44/20%`, text/border `#A87B44`, label "ℹ Info (N)"
- **Multi-select behavior**: each chip toggles independently; any combination is valid
- **Default state**: all 3 chips active (show all items)
- **Deselect-all behavior**: if user deselects all 3 chips, list shows empty state (not "show all") with message "No severity filter selected"
- Active chip: full-opacity background; inactive chip: 20% opacity background
- Padding: `empty(6, 12, 6, 12)`

**CENTER — JScrollPane > JList:**
- `IssueItem` sealed class (preserved from old panel): `FileHeader`, `HotspotRow`, `Empty`
- Items are built from filtered `projectResults`: for each `ComplexityResult`, add `FileHeader`; for each hotspot add `HotspotRow`
- Filter logic: show `FileHeader` + its `HotspotRow`s if the file's severity tier matches any active chip
- Severity mapping for filtering: RED → Critical, ORANGE → Warning, YELLOW+GREEN → Info (intentional collapse)

**FileHeader cell renderer:**
- Left 4px color bar (severity color: `#8B4513`/`#CD853F`/`#A87B44`)
- Center: file name bold `textPrimary` + relative path `textSecondary` (max 42 chars, truncate with "…")
- Right: score badge (rounded, colored by severity)
- Click → navigate to file in editor

**HotspotRow cell renderer:**
- Left 24px indent
- Severity icon: 🔥/⚠/ℹ (colored by tier)
- Method name bold + " · L{lineNumber}" `textSecondary`
- Hover only: Zap ⚡ icon appears on right edge (16×16, `accentPrimary`)
- Click → navigate to line in editor

**API:**
- `fun update(results: List<ComplexityResult>, scope: DashboardScope, currentResult: ComplexityResult?)` — rebuilds list for new data/scope/filter state
- When `scope == CURRENT_FILE`: effective results = `listOfNotNull(currentResult)`; if `currentResult` is null, show empty state message "Open a file to analyze"
- When `scope == PROJECT`: effective results = full `results` list

---

### SmiFooterPanel

- Layout: `BorderLayout`
- WEST: Icon (16×16) + status label, gap 6px
  - `setIdle(message: String)` — CheckCircle icon (`#57965C`) + message (e.g. "Analysis Complete (245 files)")
  - `setScanning(message: String)` — animated spinning RefreshCw icon (`accentPrimary`) + static message "Scanning..." (no progress %)
  - No progress bar in footer; incremental `processed/total` updates go only to `LoadingOverlayPanel.showProgress()`
- EAST: version label `textSecondary`, 10pt
- Background: `footerBg`, top border line `footerBorder`, padding `empty(5, 12, 5, 12)`
- API: `fun setIdle(message: String)`, `fun setScanning(message: String)`, `fun setVersion(version: String)`
- **Caller note:** `SmiDashboardPanel` calls `footer.setScanning("Scanning...")` once on scan start; all per-file `(processed, total)` progress passes to `LoadingOverlayPanel` only

---

## Data Flow

```
File open/edit → ComplexityRadarProjectService (debounced queue)
  → LanguageAdapter.analyze(PsiFile) → FileAstSummary
  → ComplexityScorer.score(summary, config) → ComplexityResult
  → ComplexityResultStore (cache L1/L2/L3)
  → RadarUiRefreshService → SmiDashboardPanel.refresh(FocusedViewSnapshot)
    → SmiNavPanel.updateBadge(snapshot.redCount)
    → SmiOverviewPanel.update(snapshot, currentScope)
    → SmiIssuesPanel.update(snapshot.projectResults, currentScope, snapshot.currentResult)
    → SmiFooterPanel.setIdle("Analysis Complete (N files)")
```

`FocusedViewSnapshot` is unchanged. Fields used:
- `redCount` → Issues tab badge
- `averageScore`, `aggregateSeverity`, `aggregateValues` → Overview Project view
- `currentResult` → Overview Current File view + Issues Current File view
- `projectResults` → Issues Project view (all files, replaces old `topFiles`)
- `projectSummary` — not used in new design

---

## Issues Severity Mapping

| Severity | UI Tier | Background | Foreground | Icon |
|---|---|---|---|---|
| RED | Critical | `#8B4513` at 20% | `#8B4513` | 🔥 Flame |
| ORANGE | Warning | `#CD853F` at 20% | `#CD853F` | ⚠ AlertTriangle |
| YELLOW | Info | `#A87B44` at 20% | `#A87B44` | ℹ Info |
| GREEN | Info | `#A87B44` at 20% | `#A87B44` | ℹ Info |

**Note:** YELLOW and GREEN intentionally collapse into the same "Info" tier. This is a product-layer decision — the underlying `Severity` enum is unchanged.

Issues count badge on Issues tab = `snapshot.redCount` (count of files with RED severity).

---

## Files to Create/Modify/Delete

| Action | File | Notes |
|---|---|---|
| Modify | `plugin.xml` | Change `<toolWindow>` `displayName` to "SMI Detector" |
| Modify | `ComplexityRadarToolWindowFactory.kt` | Instantiate `SmiDashboardPanel` instead of old panel |
| Create | `SmiDashboardPanel.kt` | Main container, implements `RefreshableComplexityView` |
| Create | `SmiHeaderPanel.kt` | |
| Create | `SmiToolbarPanel.kt` | |
| Create | `SmiNavPanel.kt` | |
| Create | `SmiOverviewPanel.kt` | |
| Create | `SmiIssuesPanel.kt` | |
| Create | `SmiFooterPanel.kt` | |
| Modify | `UiThemeTokens.kt` | Add 6 new tokens: `severityCritical`, `severityWarning`, `severityGreen`, `gradientCritical`, `gradientClean`, `tabUnderline` |
| Modify | `RadarChartPanel.kt` | Rename 5 axis label strings |
| Delete | `ComplexityRadarDashboardPanel.kt` | |
| Delete | `RadarHeaderPanel.kt` | |
| Delete | `RadarToolbarPanel.kt` | |
| Delete | `RadarNavPanel.kt` | |
| Delete | `RadarOverviewPanel.kt` | |
| Delete | `RadarIssuesPanel.kt` | |
| Delete | `RadarFooterPanel.kt` | |

---

## Success Criteria

1. ToolWindow shows "SMI Detector" branding (title in tab + header label) with bug icon
2. Score card displays large score (36pt) + gradient bar + severity pill badge
3. Overview tab shows Scope toggle at top → Score card → Metrics header + Radar chart (no file list)
4. Issues tab shows severity filter badges (multi-select) + file tree with hotspot rows for all analyzed files
5. Issues tab badge shows count of RED-severity files; hidden when count is 0
6. Scan button is pill-shaped single button; shows "Scanning..." when analysis is running
7. `revealFile()` correctly switches to Overview + Current File scope and re-renders
8. All existing analysis functionality (scan, cache, navigation, settings) works unchanged
9. Light and dark theme both render correctly using `JBColor` tokens
