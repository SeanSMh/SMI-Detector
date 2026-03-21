package com.sqb.complexityradar.core.scoring

import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.toDigest
import com.sqb.complexityradar.core.model.DomainTag
import com.sqb.complexityradar.core.model.FactorContribution
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.FileAstSummary
import com.sqb.complexityradar.core.model.Hotspot
import com.sqb.complexityradar.core.model.MultiplierRule
import com.sqb.complexityradar.core.model.RadarConfig
import com.sqb.complexityradar.core.model.ScoreDigest
import com.sqb.complexityradar.core.model.Severity
import java.security.MessageDigest
import kotlin.math.roundToInt

class ComplexityScorer {
    fun score(
        summary: FileAstSummary,
        fileUrl: String,
        filePath: String,
        mode: com.sqb.complexityradar.core.model.AnalyzeMode,
        config: RadarConfig,
        hotspots: List<Hotspot>,
        churnNormalized: Double = 0.0,
    ): ComplexityResult {
        val size =
            Normalization.average(
                listOf(
                    Normalization.piecewise(summary.effectiveLoc.toDouble(), config.normalization.locPoints),
                    Normalization.piecewise(summary.statementCount.toDouble(), config.normalization.statementPoints),
                    Normalization.piecewise(summary.functionCount.toDouble(), config.normalization.functionPoints),
                    Normalization.piecewise(summary.typeCount.toDouble(), config.normalization.typePoints),
                ),
            )

        val controlFlow = Normalization.piecewise(
            summary.cognitiveComplexity.toDouble(),
            config.normalization.controlFlowPoints,
        )

        val nesting = Normalization.piecewise(summary.nestingPenalty.toDouble(), config.normalization.nestingPenaltyPoints)

        val domainBase = Normalization.piecewise(summary.domainTagsHit.size.toDouble(), config.normalization.domainCountPoints)
        val domainBonus =
            when {
                summary.domainTagsHit.size >= 4 -> 0.18
                summary.domainTagsHit.size >= 3 -> 0.08
                else -> 0.0
            }
        val domain =
            (domainBase + domainBonus)
                .coerceIn(0.0, 1.0)

        val smellsRaw =
            summary.todoCount        * 0.03 +
                summary.emptyCatchCount  * 0.40 +
                summary.bangBangCount    * 0.08 +
                summary.magicNumberCount * 0.01
        val smellNorm = Normalization.piecewise(smellsRaw, config.normalization.smellPoints)
        // Use max rather than average so that smells alone can push readability to critical
        // when the file has no structural issues (short functions, few params).
        val readability =
            maxOf(
                smellNorm,
                Normalization.average(
                    listOf(
                        Normalization.piecewise(summary.maxFunctionLoc.toDouble(), config.normalization.maxFunctionLocPoints),
                        Normalization.piecewise(summary.maxParamCount.toDouble(), config.normalization.maxParamPoints),
                    ),
                ),
            )

        val normalizedMap =
            mutableMapOf(
                FactorType.SIZE to size,
                FactorType.CONTROL_FLOW to controlFlow,
                FactorType.NESTING to nesting,
                FactorType.DOMAIN_COUPLING to domain,
                FactorType.READABILITY to readability,
            )
        applyFactorLevelMultipliers(summary, config, normalizedMap)

        val contributions =
            FactorType.entries.map { factor ->
                val normalized = normalizedMap.getValue(factor)
                val weight = config.weights[factor] ?: factor.defaultWeight
                FactorContribution(
                    type = factor,
                    normalized = normalized,
                    weight = weight,
                    weightedScore = normalized * weight,
                    rawValue =
                        when (factor) {
                            FactorType.SIZE -> summary.effectiveLoc.toDouble()
                            FactorType.CONTROL_FLOW -> summary.cognitiveComplexity.toDouble()
                            FactorType.NESTING -> summary.nestingPenalty.toDouble()
                            FactorType.DOMAIN_COUPLING -> summary.domainTagsHit.size.toDouble()
                            FactorType.READABILITY -> smellsRaw
                        },
                    explanation = explanationFor(factor, summary),
                )
            }.sortedByDescending { it.weightedScore }

        val baseScore = (100.0 * contributions.sumOf { it.weightedScore }).coerceIn(0.0, 100.0)
        val globalMultiplier = resolveGlobalMultiplier(summary, config.multipliers)
        val score = (baseScore * globalMultiplier).coerceIn(0.0, 100.0).roundToInt()
        val severity = config.severityFor(score)
        val priority = (score * (1.0 + 0.5 * churnNormalized)).coerceAtMost(150.0)
        val contentHash = hash("${filePath}:${summary.effectiveLoc}:${summary.statementCount}:${summary.maxBlockDepth}:${summary.domainTagsHit.sorted()}")
        val configHash = hash(config.toString())

        return ComplexityResult(
            fileUrl = fileUrl,
            filePath = filePath,
            mode = mode,
            score = score,
            severity = severity,
            contributions = contributions,
            hotspots = hotspots.sortedByDescending { it.score }.take(config.hotspot.maxHotspotsPerFile),
            evidences = summary.evidences,
            computedAt = System.currentTimeMillis(),
            contentHash = contentHash,
            configHash = configHash,
            maxBlockDepth = summary.maxBlockDepth,
            maxLambdaDepth = summary.maxLambdaDepth,
            effectiveLoc = summary.effectiveLoc,
            statementCount = summary.statementCount,
            domainCount = summary.domainTagsHit.size,
            priority = priority,
        )
    }

    fun digest(result: ComplexityResult): ScoreDigest = result.toDigest()

    fun scoreHotspot(
        methodName: String,
        line: Int,
        length: Int,
        cognitiveComplexity: Int,
        nestingPenalty: Int,
        snippet: String?,
        config: RadarConfig,
    ): Hotspot {
        val nesting      = Normalization.piecewise(nestingPenalty.toDouble(),      config.normalization.nestingPenaltyPoints)
        val control      = Normalization.piecewise(cognitiveComplexity.toDouble(), config.normalization.controlFlowPoints)
        val methodLength = Normalization.piecewise(length.toDouble(),              config.normalization.maxFunctionLocPoints)
        val score = (100.0 * (0.45 * nesting + 0.35 * control + 0.20 * methodLength)).roundToInt().coerceIn(0, 100)
        val severity = config.severityFor(score)
        val contributions =
            listOf(
                FactorContribution(FactorType.NESTING, nesting, 0.45, nesting * 0.45, nestingPenalty.toDouble(), "Nested blocks are concentrated in this method."),
                FactorContribution(FactorType.CONTROL_FLOW, control, 0.35, control * 0.35, cognitiveComplexity.toDouble(), "Cognitive complexity is concentrated in this method."),
                FactorContribution(FactorType.SIZE, methodLength, 0.20, methodLength * 0.20, length.toDouble(), "Method length is adding local comprehension cost."),
            ).sortedByDescending { it.weightedScore }
        return Hotspot(
            methodName = methodName,
            line = line,
            score = score,
            severity = severity,
            contributions = contributions,
            recommendation = "Reduce nesting, split responsibilities, and extract branches into named helpers.",
            snippet = snippet,
        )
    }

    private fun applyFactorLevelMultipliers(
        summary: FileAstSummary,
        config: RadarConfig,
        normalizedMap: MutableMap<FactorType, Double>,
    ) {
        config.multipliers
            .filter { it.onFactors.isNotEmpty() && matchesRule(summary, it) }
            .forEach { rule ->
                rule.onFactors.forEach { factor ->
                    normalizedMap[factor] = (normalizedMap.getValue(factor) * rule.value).coerceIn(0.0, 1.0)
                }
            }
    }

    private fun resolveGlobalMultiplier(
        summary: FileAstSummary,
        rules: List<MultiplierRule>,
    ): Double =
        rules
            .filter { it.onFactors.isEmpty() && matchesRule(summary, it) }
            .fold(1.0) { acc, rule -> acc * rule.value }

    private fun matchesRule(
        summary: FileAstSummary,
        rule: MultiplierRule,
    ): Boolean {
        val match = rule.match
        return when {
            match.startsWith("extends:") -> {
                val expected = match.removePrefix("extends:")
                summary.superTypes.any { it == expected || it.endsWith(".$expected") }
            }

            match.startsWith("annotation:") -> {
                val expected = match.removePrefix("annotation:")
                summary.annotations.any { it == expected || it.endsWith(".$expected") }
            }

            match.startsWith("name:") -> {
                val pattern = match.removePrefix("name:")
                summary.classNames.any { className ->
                    pattern.split("|").any { glob ->
                        globToRegex(glob).matches(className)
                    }
                }
            }

            else -> false
        }
    }

    private fun globToRegex(glob: String): Regex =
        glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .toRegex()

    private fun explanationFor(
        factor: FactorType,
        summary: FileAstSummary,
    ): String =
        when (factor) {
            FactorType.SIZE -> "Effective LOC ${summary.effectiveLoc}, statements ${summary.statementCount}, functions ${summary.functionCount}."
            FactorType.CONTROL_FLOW -> "Cognitive complexity ${summary.cognitiveComplexity} (branches ${summary.branchCount.toInt()}, loops ${summary.loopCount}, catches ${summary.tryCatchCount})."
            FactorType.NESTING -> "Max block depth ${summary.maxBlockDepth}, max lambda depth ${summary.maxLambdaDepth}, nesting penalty ${summary.nestingPenalty}."
            FactorType.DOMAIN_COUPLING -> "Domains hit ${summary.domainTagsHit.joinToString()}."
            FactorType.READABILITY -> "Longest function ${summary.maxFunctionLoc} LOC, max params ${summary.maxParamCount}, empty catch ${summary.emptyCatchCount}, !! ops ${summary.bangBangCount}, TODO ${summary.todoCount}."
        }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
