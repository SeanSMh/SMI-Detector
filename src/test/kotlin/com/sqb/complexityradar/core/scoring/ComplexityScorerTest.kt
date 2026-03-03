package com.sqb.complexityradar.core.scoring

import com.sqb.complexityradar.core.model.AnalyzeMode
import com.sqb.complexityradar.core.model.DomainTag
import com.sqb.complexityradar.core.model.FileAstSummary
import com.sqb.complexityradar.core.model.RadarConfigDefaults
import com.sqb.complexityradar.core.model.Severity
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
        )
}
