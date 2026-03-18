# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Complexity Radar** is a JetBrains IntelliJ IDEA / Android Studio plugin (IntelliJ Platform Plugin) that performs static code complexity analysis on Java and Kotlin files. It visualizes complexity across 5 dimensions and displays results in the Project View, editor banners, gutter icons, and a dedicated ToolWindow.

- Plugin ID: `com.sqb.complexityradar`
- Build system: Gradle with IntelliJ Platform Gradle Plugin 2.7.0
- Target: IntelliJ IDEA 2024.2.1+ and Android Studio
- Language: Kotlin (min JDK 17)

## Commands

```bash
./gradlew runIde        # Launch IDE sandbox with plugin loaded for manual testing
./gradlew test          # Run unit tests (JUnit 5)
./gradlew buildPlugin   # Build distributable ZIP for plugin marketplace
./gradlew verifyPlugin  # Validate binary compatibility with target IDEs
./gradlew build         # Full build including tests
```

Run a single test class:
```bash
./gradlew test --tests "com.sqb.complexityradar.core.scoring.ComplexityScorerTest"
```

## Architecture

The plugin is organized into 5 layers under `src/main/kotlin/com/sqb/complexityradar/`:

### `core/` — IDE-agnostic business logic
- **`scoring/ComplexityScorer`** — Orchestrates the full scoring pipeline: raw metrics → normalize → weight → multipliers → severity. Entry point for all analysis.
- **`scoring/Normalization`** — Piecewise linear mapping of raw values to [0..1] per factor.
- **`model/Models.kt`** — Domain types: `ComplexityResult`, `FileAstSummary`, `HotspotMethod`, `ScoreDigest`, `SeverityLevel`.

### `adapters/` — Language-specific PSI traversal
- **`LanguageAdapter`** interface — abstraction for producing `FileAstSummary` from a PSI file.
- **`kotlin/KotlinLanguageAdapter`** and **`java/JavaLanguageAdapter`** — walk PSI trees to extract control flow, nesting depth, domain coupling via imports, and code smells.

### `ide/services/` — IntelliJ Platform service layer
- **`ComplexityRadarProjectService`** — Main orchestrator. Manages a `MergingUpdateQueue` (800ms debounce) for analysis requests, coordinates cache reads/writes, and fires UI refresh events.
- **`ComplexityResultStore`** — Three-level cache: L1 in-memory map → L2 `VirtualFile` UserData → L3 disk (`.idea/complexity-radar/cache-v*.json`).
- **`RadarUiRefreshService`** — Delegates refresh calls to Project View decorator, editor banner, and ToolWindow.

### `settings/` — Configuration management
- **`RadarConfigService`** — Parses project-level `radar.yaml` and optional per-module `radar.yaml` overrides. Hot-reloads on file change.
- **`ComplexityUiSettingsService`** — IDE-persisted settings (persisted via `@State`).

### `ide/` — UI and IDE extension points
- **`toolwindow/`** — ToolWindow panel with three tabs (Top Files, By Module, Changed Files) and a radar chart.
- **`projectview/ComplexityProjectViewDecorator`** — Adds 💩 badge + score + color to Project View nodes.
- **`editor/ComplexityEditorNotificationProvider`** — Banner at top of editor showing score, top 3 factors, and action buttons.
- **`editor/ComplexityLineMarkerProvider`** — Gutter icons for top 3 hotspot methods per file.
- **`actions/`** — Open ToolWindow, Copy Refactor Prompt, Toggle Accurate Mode.
- **`startup/ComplexityRadarStartupActivity`** — Plugin initialization at project open.

## Scoring Model

**5 dimensions with default weights:**
| Factor | Weight | Measures |
|---|---|---|
| Size | 20% | LOC, statement count, function count |
| Control Flow | 25% | Branches, loops, try-catch, logical operators |
| Nesting | 30% | Block depth and lambda depth (exponential penalty) |
| Domain Coupling | 15% | UI/Network/Storage/DI/Concurrency import hits |
| Readability | 10% | Long methods, parameter count, code smells (`!!`, empty catch, TODOs) |

Severity thresholds (configurable in `radar.yaml`): GREEN 0-25, YELLOW 26-50, ORANGE 51-75, RED 76-100.

## Analysis Flow

```
File open/edit → ComplexityRadarProjectService (debounced queue)
  → LanguageAdapter.analyze(PsiFile) → FileAstSummary
  → ComplexityScorer.score(summary, config) → ComplexityResult
  → ComplexityResultStore (cache L1/L2/L3)
  → RadarUiRefreshService → [ProjectView, EditorBanner, Gutter, ToolWindow]
```

Analysis runs via `ReadAction.nonBlocking` to avoid blocking the EDT. Two modes: FAST (debounced, triggered on edit) and ACCURATE (full analysis, triggered on demand or file open).

## Configuration

Projects can customize behavior via `radar.yaml` at the project root (team baseline) or per-module. Configuration covers weights, normalization breakpoints, exclusion patterns, severity thresholds, and multiplier rules (e.g., ViewModel/DTO class type boosts).

## Key Extension Points (plugin.xml)

- `postStartupActivity` → `ComplexityRadarStartupActivity`
- `toolWindow` → `ComplexityRadarToolWindowFactory`
- `projectViewNodeDecorator` → `ComplexityProjectViewDecorator`
- `editorNotificationProvider` → `ComplexityEditorNotificationProvider`
- `lineMarkerProvider` (Java + Kotlin) → `ComplexityLineMarkerProvider`
- `projectConfigurable` → `ComplexityRadarConfigurable` (Settings → Tools)
- `checkinHandlerFactory` → `ComplexityRadarCheckinHandlerFactory` (pre-commit)

## Dependencies

- `snakeyaml 2.3` — YAML config parsing
- `jackson-module-kotlin 2.18.2` — JSON serialization for disk cache and export
- `junit-jupiter 5.11.4` — Unit tests
- IntelliJ Platform SDK, Kotlin plugin, Java plugin (all provided by Gradle plugin)
