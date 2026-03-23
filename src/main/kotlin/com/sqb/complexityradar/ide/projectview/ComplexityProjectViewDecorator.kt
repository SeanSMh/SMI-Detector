package com.bril.code_radar.ide.projectview

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ide.projectView.PresentationData
import com.intellij.util.ui.JBUI
import com.bril.code_radar.ide.services.ComplexityRadarProjectService
import com.bril.code_radar.ide.ui.poopAccentColor
import com.bril.code_radar.ide.ui.poopScoreCount
import com.bril.code_radar.ide.ui.poopTooltipLabel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

class ComplexityProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(
        node: ProjectViewNode<*>,
        data: PresentationData,
    ) {
        val project = node.project
        val file = node.virtualFile ?: return
        val service = ComplexityRadarProjectService.getInstance(project)
        val settings = service.uiSettings()
        if (!settings.showProjectViewDecoration) {
            return
        }
        val digest = service.getDigest(file) ?: return
        val poopCount = poopScoreCount(digest.score)
        val baseIcon = data.getIcon(false)
        if (poopCount > 1 && baseIcon != null) {
            data.setIcon(
                ProjectViewPoopIcon(
                    poopCount = poopCount,
                    accent = poopAccentColor(digest.severity),
                    delegate = baseIcon,
                ),
            )
        }
        data.tooltip =
            buildString {
                appendLine(poopTooltipLabel(digest.score))
                appendLine("Complexity ${digest.score} (${digest.severity.label})")
                appendLine("Top: ${digest.topContributions.joinToString()}")
                appendLine("LOC ${digest.effectiveLoc}, depth ${digest.maxDepth}, domains ${digest.domainCount}, hotspots ${digest.hotspotCount}")
            }
    }
}

private class ProjectViewPoopIcon(
    private val poopCount: Int,
    private val accent: Color,
    private val delegate: Icon,
) : Icon {
    private val poopSize = JBUI.scale(15)
    private val poopGap = JBUI.scale(1)
    private val iconGap = JBUI.scale(4)

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val poopStripWidth = poopCount * poopSize + (poopCount - 1).coerceAtLeast(0) * poopGap
        val poopY = y + ((iconHeight - poopSize) / 2).coerceAtLeast(0)
        repeat(poopCount) { index ->
            val iconX = x + index * (poopSize + poopGap)
            drawProjectViewPoop(g2, iconX, poopY, poopSize, accent)
        }

        val delegateX = x + poopStripWidth + iconGap
        val delegateY = y + ((iconHeight - delegate.iconHeight) / 2).coerceAtLeast(0)
        delegate.paintIcon(c, g2, delegateX, delegateY)
        g2.dispose()
    }

    override fun getIconWidth(): Int {
        val poopStripWidth = poopCount * poopSize + (poopCount - 1).coerceAtLeast(0) * poopGap
        return poopStripWidth + iconGap + delegate.iconWidth
    }

    override fun getIconHeight(): Int = maxOf(delegate.iconHeight, poopSize)
}

private fun drawProjectViewPoop(
    graphics: Graphics2D,
    x: Int,
    y: Int,
    size: Int,
    accent: Color,
) {
    val outline = accent.darker()
    val baseWidth = (size * 0.76).toInt().coerceAtLeast(4)
    val baseHeight = (size * 0.30).toInt().coerceAtLeast(2)
    val middleWidth = (size * 0.58).toInt().coerceAtLeast(3)
    val middleHeight = (size * 0.24).toInt().coerceAtLeast(2)
    val topWidth = (size * 0.40).toInt().coerceAtLeast(2)
    val topHeight = (size * 0.18).toInt().coerceAtLeast(2)

    val baseX = x + (size - baseWidth) / 2
    val baseY = y + (size * 0.54).toInt()
    val middleX = x + (size - middleWidth) / 2
    val middleY = y + (size * 0.32).toInt()
    val topX = x + (size - topWidth) / 2 + JBUI.scale(1)
    val topY = y + (size * 0.14).toInt()
    val tipX = topX + topWidth / 2

    graphics.color = accent
    graphics.fillRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
    graphics.fillRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
    graphics.fillRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
    graphics.fillPolygon(
        intArrayOf(tipX - 1, tipX, tipX + 1),
        intArrayOf(topY + 1, y, topY + 1),
        3,
    )

    graphics.color = Color(255, 255, 255, 28)
    graphics.fillOval(topX + 1, topY + 1, 2, 2)

    graphics.color = outline
    graphics.stroke = BasicStroke(1f)
    graphics.drawRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
    graphics.drawRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
    graphics.drawRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
    graphics.drawPolyline(
        intArrayOf(tipX - 1, tipX, tipX + 1),
        intArrayOf(topY + 1, y, topY + 1),
        3,
    )
}
