package com.sqb.complexityradar.core.model

import com.intellij.openapi.vfs.VirtualFile
import kotlin.math.roundToInt

enum class AnalyzeMode {
    FAST,
    ACCURATE,
}

enum class Severity(val label: String) {
    GREEN("Low"),
    YELLOW("Moderate"),
    ORANGE("High"),
    RED("Critical"),
}

enum class DomainTag {
    UI,
    NETWORK,
    STORAGE,
    SERIALIZATION,
    DI,
    CONCURRENCY,
    SYSTEM,
}

enum class FactorType(val displayName: String, val defaultWeight: Double) {
    SIZE("Size", 0.20),
    CONTROL_FLOW("Control Flow", 0.25),
    NESTING("Nesting", 0.30),
    DOMAIN_COUPLING("Domain Coupling", 0.15),
    READABILITY("Readability", 0.10),
}

data class ScalePoint(
    val x: Double,
    val y: Double,
)

data class FactorContribution(
    val type: FactorType,
    val normalized: Double,
    val weight: Double,
    val weightedScore: Double,
    val rawValue: Double,
    val explanation: String,
)

data class RuleEvidence(
    val rule: String,
    val kind: String,
    val value: String,
)

data class Hotspot(
    val methodName: String,
    val line: Int,
    val score: Int,
    val severity: Severity,
    val contributions: List<FactorContribution>,
    val recommendation: String,
    val snippet: String? = null,
)

data class FileAstSummary(
    val fileName: String,
    val effectiveLoc: Int,
    val statementCount: Int,
    val functionCount: Int,
    val typeCount: Int,
    val branchCount: Double,
    val simpleWhenBranchCount: Int,
    val loopCount: Int,
    val tryCatchCount: Int,
    val ternaryCount: Int,
    val logicalOpCount: Int,
    val maxBlockDepth: Int,
    val maxLambdaDepth: Int,
    val nestingPenalty: Int,
    val domainTagsHit: Set<DomainTag>,
    val evidences: List<RuleEvidence>,
    val maxFunctionLoc: Int,
    val maxParamCount: Int,
    val todoCount: Int,
    val emptyCatchCount: Int,
    val bangBangCount: Int,
    val magicNumberCount: Int,
    val androidKind: String?,
    val annotations: Set<String>,
    val superTypes: Set<String>,
    val classNames: Set<String>,
)

data class SeverityRange(
    val min: Int,
    val max: Int,
) {
    fun contains(score: Int): Boolean = score in min..max
}

data class ModeConfig(
    val default: AnalyzeMode = AnalyzeMode.FAST,
    val accurateOnOpenFile: Boolean = true,
    val accurateOnTopRedFiles: Int = 20,
)

data class NormalizationConfig(
    val locPoints: List<ScalePoint>,
    val statementPoints: List<ScalePoint>,
    val functionPoints: List<ScalePoint>,
    val typePoints: List<ScalePoint>,
    val controlFlowPoints: List<ScalePoint>,
    val nestingPenaltyPoints: List<ScalePoint>,
    val domainCountPoints: List<ScalePoint>,
    val maxFunctionLocPoints: List<ScalePoint>,
    val maxParamPoints: List<ScalePoint>,
    val smellPoints: List<ScalePoint>,
)

data class RulesConfig(
    val kotlinWhenSimpleWeight: Double = 0.25,
)

data class MultiplierRule(
    val match: String,
    val value: Double,
    val onFactors: Set<FactorType> = emptySet(),
)

data class HotspotConfig(
    val gutterThreshold: Int = 75,
    val maxGutterPerFile: Int = 3,
    val maxHotspotsPerFile: Int = 5,
)

data class RadarConfig(
    val version: String,
    val thresholds: Map<Severity, SeverityRange>,
    val mode: ModeConfig,
    val weights: Map<FactorType, Double>,
    val normalization: NormalizationConfig,
    val rules: RulesConfig,
    val multipliers: List<MultiplierRule>,
    val exclusions: List<String>,
    val hotspot: HotspotConfig,
) {
    fun severityFor(score: Int): Severity =
        thresholds.entries.firstOrNull { it.value.contains(score) }?.key ?: Severity.RED

    private val compiledExclusions: List<Regex> by lazy {
        exclusions.map { pattern -> globToRegex(pattern) }
    }

    fun isExcluded(file: VirtualFile): Boolean {
        val path = file.path.replace('\\', '/')
        return compiledExclusions.any { regex -> regex.matches(path) }
    }

    fun merge(other: RadarConfig): RadarConfig =
        copy(
            version = other.version,
            thresholds = thresholds + other.thresholds,
            mode = other.mode,
            weights = weights + other.weights,
            normalization = other.normalization,
            rules = other.rules,
            multipliers = other.multipliers.ifEmpty { multipliers },
            exclusions = if (other.exclusions.isEmpty()) exclusions else exclusions + other.exclusions,
            hotspot = other.hotspot,
        )

    companion object {
        fun globToRegex(pattern: String): Regex {
            val regexStr = buildString {
                append('^')
                var index = 0
                while (index < pattern.length) {
                    val current = pattern[index]
                    when {
                        current == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                            append(".*")
                            index += 2
                        }

                        current == '*' -> {
                            append("[^/]*")
                            index += 1
                        }

                        current == '?' -> {
                            append('.')
                            index += 1
                        }

                        current in setOf('.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\') -> {
                            append('\\').append(current)
                            index += 1
                        }

                        else -> {
                            append(current)
                            index += 1
                        }
                    }
                }
                append('$')
            }
            return regexStr.toRegex()
        }
    }
}

data class ComplexityResult(
    val fileUrl: String,
    val filePath: String,
    val mode: AnalyzeMode,
    val score: Int,
    val severity: Severity,
    val contributions: List<FactorContribution>,
    val hotspots: List<Hotspot>,
    val evidences: List<RuleEvidence>,
    val computedAt: Long,
    val contentHash: String,
    val configHash: String,
    val maxBlockDepth: Int,
    val maxLambdaDepth: Int,
    val effectiveLoc: Int,
    val statementCount: Int,
    val domainCount: Int,
    val priority: Double,
)

data class ScoreDigest(
    val score: Int,
    val severity: Severity,
    val mode: AnalyzeMode,
    val topContributions: List<String>,
    val effectiveLoc: Int,
    val maxDepth: Int,
    val domainCount: Int,
    val hotspotCount: Int,
)

fun ComplexityResult.toDigest(): ScoreDigest =
    ScoreDigest(
        score = score,
        severity = severity,
        mode = mode,
        topContributions = contributions.take(3).map { "${it.type.displayName} ${(it.weightedScore * 100).roundToInt()}" },
        effectiveLoc = effectiveLoc,
        maxDepth = maxOf(maxBlockDepth, maxLambdaDepth),
        domainCount = domainCount,
        hotspotCount = hotspots.size,
    )
data class UiSettingsState(
    var showProjectViewDecoration: Boolean = true,
    var showEditorBanner: Boolean = true,
    var showGutterIcons: Boolean = true,
    var defaultSort: String = "score",
    var externalCommand: String = "",
)

object RadarConfigDefaults {
    val defaultConfig: RadarConfig =
        RadarConfig(
            version = "2.1",
            thresholds =
                mapOf(
                    Severity.GREEN to SeverityRange(0, 25),
                    Severity.YELLOW to SeverityRange(26, 50),
                    Severity.ORANGE to SeverityRange(51, 75),
                    Severity.RED to SeverityRange(76, 100),
                ),
            mode = ModeConfig(),
            weights =
                FactorType.entries.associateWith { it.defaultWeight },
            normalization =
                NormalizationConfig(
                    locPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(200.0, 0.15), ScalePoint(400.0, 0.35), ScalePoint(800.0, 0.70), ScalePoint(1400.0, 1.0)),
                    statementPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(80.0, 0.2), ScalePoint(200.0, 0.55), ScalePoint(400.0, 0.85), ScalePoint(700.0, 1.0)),
                    functionPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(10.0, 0.1), ScalePoint(25.0, 0.45), ScalePoint(45.0, 0.8), ScalePoint(70.0, 1.0)),
                    typePoints = listOf(ScalePoint(1.0, 0.0), ScalePoint(2.0, 0.2), ScalePoint(4.0, 0.6), ScalePoint(6.0, 1.0)),
                    controlFlowPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(20.0, 0.2), ScalePoint(60.0, 0.6), ScalePoint(120.0, 0.9), ScalePoint(200.0, 1.0)),
                    nestingPenaltyPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(1.0, 0.15), ScalePoint(3.0, 0.35), ScalePoint(7.0, 0.65), ScalePoint(15.0, 0.9), ScalePoint(31.0, 1.0)),
                    domainCountPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(1.0, 0.05), ScalePoint(2.0, 0.25), ScalePoint(3.0, 0.55), ScalePoint(4.0, 0.8), ScalePoint(5.0, 1.0)),
                    maxFunctionLocPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(40.0, 0.15), ScalePoint(80.0, 0.45), ScalePoint(140.0, 0.8), ScalePoint(220.0, 1.0)),
                    maxParamPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(4.0, 0.1), ScalePoint(7.0, 0.45), ScalePoint(10.0, 0.8), ScalePoint(14.0, 1.0)),
                    smellPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(0.5, 0.2), ScalePoint(1.5, 0.55), ScalePoint(3.0, 0.85), ScalePoint(5.0, 1.0)),
                ),
            rules = RulesConfig(),
            multipliers =
                listOf(
                    MultiplierRule("extends:android.app.Activity", 1.15),
                    MultiplierRule("extends:androidx.fragment.app.Fragment", 1.15),
                    MultiplierRule("extends:android.app.Application", 1.25),
                    MultiplierRule("extends:android.content.ContentProvider", 1.25),
                    MultiplierRule("extends:androidx.lifecycle.ViewModel", 1.10),
                    MultiplierRule("extends:android.arch.lifecycle.ViewModel", 1.10),
                    MultiplierRule("annotation:Composable", 0.80, setOf(FactorType.NESTING)),
                    MultiplierRule("name:*Dto*|*Entity*|*Model*|*VO*|*PO*", 0.30),
                    MultiplierRule("name:*Constants*|*Keys*|*Const*", 0.40),
                ),
            exclusions =
                listOf(
                    "**/build/**",
                    "**/generated/**",
                    "**/kapt/**",
                    "**/ksp/**",
                    "**/*Test.*",
                    "**/*AndroidTest.*",
                ),
            hotspot = HotspotConfig(),
        )
}
