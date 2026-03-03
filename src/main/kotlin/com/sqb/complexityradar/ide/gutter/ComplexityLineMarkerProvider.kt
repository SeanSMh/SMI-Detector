package com.sqb.complexityradar.ide.gutter

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.sqb.complexityradar.adapters.common.AnalysisSupport
import com.sqb.complexityradar.core.model.Severity
import com.sqb.complexityradar.ide.services.ComplexityRadarProjectService
import com.sqb.complexityradar.ide.ui.poopAccentColor
import com.sqb.complexityradar.ide.ui.poopScoreCount
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

class ComplexityLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = "Complexity Radar Hotspots"

    override fun getIcon(): Icon = PoopGutterIcon(Severity.ORANGE)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        elements.forEach { element ->
            val file = element.containingFile?.virtualFile ?: return@forEach
            val service = ComplexityRadarProjectService.getInstance(element.project)
            if (!service.uiSettings().showGutterIcons) {
                return@forEach
            }
            val analysisResult = service.getResult(file) ?: return@forEach
            val hotspotConfig = service.configFor(file).hotspot
            val hotspots =
                analysisResult.hotspots
                    .filter { it.score >= hotspotConfig.gutterThreshold }
                    .take(hotspotConfig.maxGutterPerFile)
            if (hotspots.isEmpty()) {
                return@forEach
            }
            if (!isMethodAnchor(element)) {
                return@forEach
            }
            val line = AnalysisSupport.lineNumber(element.containingFile, element)
            val hotspot = hotspots.firstOrNull { it.line == line } ?: return@forEach
            result +=
                LineMarkerInfo(
                    element,
                    element.textRange,
                    PoopGutterIcon(hotspot.severity),
                    { "Hotspot ${poopScoreCount(hotspot.score)}/5: ${hotspot.methodName}" },
                    GutterIconNavigationHandler<PsiElement> { _, clickedElement ->
                        Messages.showInfoMessage(
                            clickedElement.project,
                            buildString {
                                appendLine("${hotspot.methodName} (poop ${poopScoreCount(hotspot.score)}/5, raw ${hotspot.score})")
                                hotspot.contributions.forEach {
                                    appendLine("${it.type.displayName}: ${(it.weightedScore * 100).toInt()} - ${it.explanation}")
                                }
                                appendLine()
                                appendLine(hotspot.recommendation)
                            },
                            "Complexity Hotspot",
                        )
                    },
                    GutterIconRenderer.Alignment.LEFT,
                )
        }
    }

    private fun isMethodAnchor(element: PsiElement): Boolean {
        if (element is PsiIdentifier && element.parent is PsiMethod) {
            return true
        }
        val parent = element.parent
        return parent is KtNamedFunction && parent.nameIdentifier == element
    }
}

private class PoopGutterIcon(
    private val severity: Severity,
) : Icon {
    override fun getIconWidth(): Int = 14

    override fun getIconHeight(): Int = 14

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val accent = poopAccentColor(severity)
        val fill = java.awt.Color(accent.red, accent.green, accent.blue, 235)
        val outline = accent.darker()

        val baseX = x + 2
        val baseY = y + 8
        val baseWidth = 10
        val baseHeight = 4
        val middleX = x + 3
        val middleY = y + 5
        val middleWidth = 8
        val middleHeight = 4
        val topX = x + 5
        val topY = y + 2
        val topWidth = 5
        val topHeight = 3
        val dripX = x + 9
        val dripY = y + 10

        g2.color = fill
        g2.fillRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
        g2.fillRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
        g2.fillRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
        g2.fillOval(dripX, dripY, 2, 3)
        g2.fillPolygon(intArrayOf(x + 7, x + 8, x + 9), intArrayOf(y + 2, y + 0, y + 2), 3)

        g2.color = outline
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
        g2.drawRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
        g2.drawRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
        g2.drawOval(dripX, dripY, 2, 3)
        g2.drawPolyline(intArrayOf(x + 7, x + 8, x + 9), intArrayOf(y + 2, y + 0, y + 2), 3)

        g2.dispose()
    }
}
