package com.sqb.complexityradar.core.scoring

import com.sqb.complexityradar.core.model.AnalyzeMode
import com.sqb.complexityradar.core.model.DomainTag
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.FileAstSummary
import com.sqb.complexityradar.core.model.RadarConfigDefaults
import com.sqb.complexityradar.core.model.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComplexityScorerTest {
    private val scorer = ComplexityScorer()

    @Test
    fun `scores a complex file above low severity`() {
        val summary =
            baseSummary(
                superTypes = setOf("androidx.lifecycle.ViewModel"),
                androidKind = "ViewModel",
            )

        val result =
            score(summary)

        val lowSeverityMax = RadarConfigDefaults.defaultConfig.thresholds.getValue(Severity.GREEN).max
        assertTrue(result.score > lowSeverityMax, "Expected score above low-severity threshold $lowSeverityMax but was ${result.score}")
        assertTrue(result.contributions.isNotEmpty())
    }

    @Test
    fun `applies ViewModel multiplier`() {
        val baseline = score(baseSummary())
        val boosted =
            score(
                baseSummary(
                    superTypes = setOf("androidx.lifecycle.ViewModel"),
                    androidKind = "ViewModel",
                ),
            )

        assertTrue(boosted.score > baseline.score, "Expected ViewModel score ${boosted.score} to be higher than baseline ${baseline.score}")
    }

    @Test
    fun `applies stronger bonus when domain count reaches four`() {
        val threeDomains = score(baseSummary(domainTagsHit = setOf(DomainTag.UI, DomainTag.NETWORK, DomainTag.STORAGE)))
        val fourDomains = score(baseSummary(domainTagsHit = setOf(DomainTag.UI, DomainTag.NETWORK, DomainTag.STORAGE, DomainTag.DI)))

        assertTrue(fourDomains.score > threeDomains.score, "Expected four-domain score ${fourDomains.score} to exceed three-domain score ${threeDomains.score}")
        assertNotEquals(threeDomains.score, fourDomains.score)
    }

    private fun score(summary: FileAstSummary) =
        scorer.score(
            summary = summary,
            fileUrl = "file:///ExampleFeatureController.kt",
            filePath = "/tmp/ExampleFeatureController.kt",
            mode = AnalyzeMode.ACCURATE,
            config = RadarConfigDefaults.defaultConfig,
            hotspots = emptyList(),
        )

    @Test
    fun `three empty catches push readability normalized above 0 point 6`() {
        val result = score(
            baseSummary().copy(
                emptyCatchCount = 3,
                todoCount = 0,
                bangBangCount = 0,
                magicNumberCount = 0,
                maxFunctionLoc = 0,
                maxParamCount = 0,
            )
        )
        val readability = result.contributions.first { it.type == FactorType.READABILITY }
        assertTrue(
            readability.normalized > 0.60,
            "Expected readability.normalized > 0.60 for 3 empty catches, was ${readability.normalized}"
        )
    }

    @Test
    fun `ten bang bang operators push readability normalized above 0 point 3`() {
        val result = score(
            baseSummary().copy(
                bangBangCount = 10,
                emptyCatchCount = 0,
                todoCount = 0,
                magicNumberCount = 0,
                maxFunctionLoc = 0,
                maxParamCount = 0,
            )
        )
        val readability = result.contributions.first { it.type == FactorType.READABILITY }
        // 10 * 0.08 = 0.80 smellsRaw → smellNorm ≈ 0.507 via piecewise; threshold 0.45 gives ~11% margin
        assertTrue(
            readability.normalized > 0.45,
            "Expected readability.normalized > 0.45 for 10 !! operators, was ${readability.normalized}"
        )
    }

    @Test
    fun `cc zero produces zero control flow normalized`() {
        val result = score(baseSummary().copy(cognitiveComplexity = 0))
        val cf = result.contributions.first { it.type == FactorType.CONTROL_FLOW }
        assertEquals(0.0, cf.normalized, 0.001)
    }

    @Test
    fun `cc at sonarqube A boundary normalizes to about 0 point 2`() {
        // CC=15 → controlFlowPoints maps to normalized 0.20
        val result = score(baseSummary().copy(cognitiveComplexity = 15))
        val cf = result.contributions.first { it.type == FactorType.CONTROL_FLOW }
        assertTrue(cf.normalized in 0.18..0.22,
            "Expected normalized ~0.20 for CC=15, was ${cf.normalized}")
    }

    @Test
    fun `high cc 80 normalizes above 0 point 8`() {
        val result = score(baseSummary().copy(cognitiveComplexity = 80))
        val cf = result.contributions.first { it.type == FactorType.CONTROL_FLOW }
        assertTrue(cf.normalized > 0.80,
            "Expected normalized > 0.80 for CC=80, was ${cf.normalized}")
    }

    private fun baseSummary(
        domainTagsHit: Set<DomainTag> = setOf(DomainTag.UI, DomainTag.NETWORK, DomainTag.STORAGE),
        superTypes: Set<String> = emptySet(),
        androidKind: String? = null,
    ): FileAstSummary =
        FileAstSummary(
            fileName = "ExampleFeatureController.kt",
            effectiveLoc = 420,
            statementCount = 180,
            functionCount = 12,
            typeCount = 2,
            branchCount = 18.0,
            simpleWhenBranchCount = 4,
            loopCount = 3,
            tryCatchCount = 2,
            ternaryCount = 2,
            logicalOpCount = 8,
            maxBlockDepth = 5,
            maxLambdaDepth = 2,
            nestingPenalty = 9,
            domainTagsHit = domainTagsHit,
            evidences = emptyList(),
            maxFunctionLoc = 96,
            maxParamCount = 7,
            todoCount = 2,
            emptyCatchCount = 1,
            bangBangCount = 3,
            magicNumberCount = 5,
            androidKind = androidKind,
            annotations = emptySet(),
            superTypes = superTypes,
            classNames = setOf("ExampleFeatureController"),
            cognitiveComplexity = 35,
        )
}
