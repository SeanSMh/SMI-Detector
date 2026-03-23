package com.bril.code_radar.ide.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.bril.code_radar.core.model.Severity
import com.bril.code_radar.ide.ui.poopAccentColor
import com.bril.code_radar.ide.ui.poopMutedColor
import com.bril.code_radar.ide.ui.poopScoreCount
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

internal class PoopRatingStrip(
    iconSize: Int,
    gap: Int,
) : JComponent() {
    private val scaledIconSize = JBUI.scale(iconSize)
    private val scaledGap = JBUI.scale(gap)

    private var activeCount = 0
    private var severity = Severity.GREEN
    private var selected = false

    init {
        isOpaque = false
        preferredSize = Dimension((scaledIconSize * 5) + (scaledGap * 4), scaledIconSize + JBUI.scale(6))
        minimumSize = preferredSize
    }

    fun setScore(
        score: Int,
        severity: Severity,
        selected: Boolean = false,
    ) {
        activeCount = poopScoreCount(score)
        this.severity = severity
        this.selected = selected
        toolTipText = buildPoopTooltip(score)
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val iconHeight = scaledIconSize + JBUI.scale(2)
        var x = 0
        val y = ((height - iconHeight) / 2).coerceAtLeast(0)
        repeat(5) { index ->
            val active = index < activeCount
            val accent = poopAccentColor(severity)
            val fill =
                if (active) {
                    Color(accent.red, accent.green, accent.blue, if (selected) 220 else 255)
                } else {
                    poopMutedColor()
                }
            val outline =
                if (active) {
                    accent.darker()
                } else {
                    poopMutedColor().darker()
                }
            drawPoopIcon(g2, x, y, scaledIconSize, fill, outline)
            x += scaledIconSize + scaledGap
        }
        g2.dispose()
    }
}

internal fun buildPoopTooltip(score: Int): String = "Poop ${poopScoreCount(score)}/5 | Raw $score/100"

internal fun severityColor(
    severity: Severity,
    alpha: Int = 255,
): Color {
    val base =
        when (severity) {
            Severity.GREEN -> JBColor(Color(0x49A55E), Color(0x78C27A))
            Severity.YELLOW -> JBColor(Color(0xBF8B14), Color(0xE0B84B))
            Severity.ORANGE -> JBColor(Color(0xD96E10), Color(0xF08A24))
            Severity.RED -> JBColor(Color(0xCC4747), Color(0xF26D6D))
        }
    return Color(base.red, base.green, base.blue, alpha.coerceIn(0, 255))
}

private fun drawPoopIcon(
    graphics: Graphics2D,
    x: Int,
    y: Int,
    size: Int,
    fill: Color,
    outline: Color,
) {
    val baseWidth = (size * 0.78).toInt()
    val baseHeight = (size * 0.28).toInt()
    val middleWidth = (size * 0.62).toInt()
    val middleHeight = (size * 0.24).toInt()
    val topWidth = (size * 0.44).toInt()
    val topHeight = (size * 0.19).toInt()
    val dripWidth = (size * 0.15).toInt().coerceAtLeast(2)
    val dripHeight = (size * 0.23).toInt().coerceAtLeast(3)
    val tipHalfWidth = (size * 0.08).toInt().coerceAtLeast(1)

    val baseX = x + (size - baseWidth) / 2
    val baseY = y + (size * 0.58).toInt()
    val middleX = x + (size - middleWidth) / 2 - JBUI.scale(1)
    val middleY = y + (size * 0.36).toInt()
    val topX = x + (size - topWidth) / 2 + JBUI.scale(1)
    val topY = y + (size * 0.18).toInt()
    val tipX = topX + topWidth / 2 + JBUI.scale(1)
    val tipTop = y + JBUI.scale(1)
    val dripX = baseX + (baseWidth * 0.64).toInt()
    val dripY = baseY + baseHeight - JBUI.scale(2)

    fun fillBody() {
        graphics.fillRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
        graphics.fillOval(baseX - JBUI.scale(2), baseY + baseHeight / 3, baseHeight / 2, baseHeight / 2)
        graphics.fillOval(baseX + baseWidth - baseHeight / 3, baseY + baseHeight / 4, baseHeight / 2, baseHeight / 2)
        graphics.fillRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
        graphics.fillRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
        graphics.fillOval(dripX, dripY, dripWidth, dripHeight)
        graphics.fillPolygon(
            intArrayOf(tipX - tipHalfWidth, tipX, tipX + tipHalfWidth),
            intArrayOf(topY + topHeight / 3, tipTop, topY + topHeight / 3 + JBUI.scale(1)),
            3,
        )
    }

    graphics.color = fill
    fillBody()

    graphics.color = Color(255, 255, 255, 30)
    graphics.fillOval(topX + JBUI.scale(2), topY + JBUI.scale(2), (topWidth * 0.28).toInt().coerceAtLeast(2), (topHeight * 0.35).toInt().coerceAtLeast(2))

    graphics.color = outline
    graphics.stroke = BasicStroke(JBUI.scale(1).toFloat())
    graphics.drawRoundRect(baseX, baseY, baseWidth, baseHeight, baseHeight, baseHeight)
    graphics.drawOval(baseX - JBUI.scale(2), baseY + baseHeight / 3, baseHeight / 2, baseHeight / 2)
    graphics.drawOval(baseX + baseWidth - baseHeight / 3, baseY + baseHeight / 4, baseHeight / 2, baseHeight / 2)
    graphics.drawRoundRect(middleX, middleY, middleWidth, middleHeight, middleHeight, middleHeight)
    graphics.drawRoundRect(topX, topY, topWidth, topHeight, topHeight, topHeight)
    graphics.drawOval(dripX, dripY, dripWidth, dripHeight)
    graphics.drawPolyline(
        intArrayOf(tipX - tipHalfWidth, tipX, tipX + tipHalfWidth),
        intArrayOf(topY + topHeight / 3, tipTop, topY + topHeight / 3 + JBUI.scale(1)),
        3,
    )
}
