package com.bril.code_radar.ide.toolwindow

import com.bril.code_radar.core.model.ComplexityResult
import com.bril.code_radar.core.model.FactorType
import com.bril.code_radar.core.model.Severity

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
