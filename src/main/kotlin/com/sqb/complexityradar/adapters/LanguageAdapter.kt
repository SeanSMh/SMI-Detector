package com.bril.code_radar.adapters

import com.intellij.psi.PsiFile
import com.bril.code_radar.core.model.AnalyzeMode
import com.bril.code_radar.core.model.FileAstSummary
import com.bril.code_radar.core.model.Hotspot
import com.bril.code_radar.core.model.RadarConfig

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
