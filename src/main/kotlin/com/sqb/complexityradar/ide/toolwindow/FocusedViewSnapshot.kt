package com.sqb.complexityradar.ide.toolwindow

import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.Severity

internal data class FocusedViewSnapshot(
    val currentResult: ComplexityResult?,
    val projectResults: List<ComplexityResult>,
    val topFiles: List<ComplexityResult>,
    val averageScore: Double,
    val redCount: Int,
    val aggregateValues: Map<FactorType, Double>,
    val aggregateSeverity: Severity,
    val projectSummary: String,
    val targetFileUrl: String?,
    val targetFilePath: String?,
)
