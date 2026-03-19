package com.sqb.complexityradar.adapters

import com.intellij.psi.PsiFile
import com.sqb.complexityradar.core.model.AnalyzeMode
import com.sqb.complexityradar.core.model.FileAstSummary
import com.sqb.complexityradar.core.model.Hotspot
import com.sqb.complexityradar.core.model.RadarConfig

data class FileAnalysis(
    val summary: FileAstSummary,
    val hotspots: List<Hotspot>,
)

interface LanguageAdapter {
    fun supports(file: PsiFile): Boolean

    fun summarize(
        file: PsiFile,
        mode: AnalyzeMode,
        config: RadarConfig,
    ): FileAstSummary

    fun hotspots(
        file: PsiFile,
        mode: AnalyzeMode,
        config: RadarConfig,
    ): List<Hotspot>

    fun analyze(
        file: PsiFile,
        mode: AnalyzeMode,
        config: RadarConfig,
    ): FileAnalysis = FileAnalysis(summarize(file, mode, config), hotspots(file, mode, config))
}
