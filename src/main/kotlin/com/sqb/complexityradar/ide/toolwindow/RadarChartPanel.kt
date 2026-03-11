package com.sqb.complexityradar.ide.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.core.model.FactorType
import com.sqb.complexityradar.core.model.Severity
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import javax.swing.JPanel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal class RadarChartPanel : JPanel() {
    private var result: ComplexityResult? = null
    private var aggregateValues: Map<FactorType, Double>? = null
    private var aggregateSeverity: Severity = Severity.GREEN

    init {
        preferredSize = Dimension(JBUI.scale(240), JBUI.scale(220))
        minimumSize = preferredSize
        isOpaque = false
    }

    fun setResult(value: ComplexityResult?) {
        result = value
        aggregateValues = null
        repaint()
    }

    fun setAggregate(
        values: Map<FactorType, Double>,
        severity: Severity,
    ) {
        result = null
        aggregateValues = values
        aggregateSeverity = severity
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val centerX = width / 2
        val centerY = height / 2 + JBUI.scale(8)
        val radius = min(width, height) / 2 - JBUI.scale(34)
        if (radius <= 0) {
            g2.dispose()
            return
        }

        val factors = FactorType.entries
        val levels = listOf(0.25, 0.5, 0.75, 1.0)

        g2.color = JBColor(Color(0xDFE5ED), Color(0x3B4250))
        levels.forEach { level ->
            val polygon = buildPolygon(centerX, centerY, radius * level, factors.size) { level }
            g2.drawPolygon(polygon)
        }

        factors.forEachIndexed { index, factor ->
            val angle = angleFor(index, factors.size)
            val endX = centerX + (cos(angle) * radius).toInt()
            val endY = centerY + (sin(angle) * radius).toInt()
            g2.drawLine(centerX, centerY, endX, endY)

            val labelX = centerX + (cos(angle) * (radius + JBUI.scale(18))).toInt()
            val labelY = centerY + (sin(angle) * (radius + JBUI.scale(18))).toInt()
            g2.color = JBColor(Color(0x55606D), Color(0xA5ADB8))
            val label = labelFor(factor)
            val labelWidth = g2.fontMetrics.stringWidth(label)
            g2.drawString(label, labelX - labelWidth / 2, labelY)
            g2.color = JBColor(Color(0xDFE5ED), Color(0x3B4250))
        }

        val polygonValues =
            result?.contributions?.associate { it.type to it.normalized }
                ?: aggregateValues
        val polygonSeverity =
            result?.severity
                ?: aggregateSeverity
        polygonValues?.let { values ->
            val polygon =
                buildPolygon(centerX, centerY, radius.toDouble(), factors.size) { factorIndex ->
                    values[factors[factorIndex]] ?: 0.0
                }
            val fill = severityColor(polygonSeverity, 80)
            val stroke = severityColor(polygonSeverity)
            g2.color = fill
            g2.fillPolygon(polygon)
            g2.color = stroke
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            g2.drawPolygon(polygon)
        }

        g2.dispose()
    }

    private fun buildPolygon(
        centerX: Int,
        centerY: Int,
        radius: Double,
        size: Int,
        valueProvider: (Int) -> Double,
    ): Polygon {
        val polygon = Polygon()
        repeat(size) { index ->
            val angle = angleFor(index, size)
            val scale = valueProvider(index).coerceIn(0.0, 1.0)
            val pointRadius = radius * scale
            polygon.addPoint(
                centerX + (cos(angle) * pointRadius).toInt(),
                centerY + (sin(angle) * pointRadius).toInt(),
            )
        }
        return polygon
    }

    private fun angleFor(
        index: Int,
        size: Int,
    ): Double = -Math.PI / 2 + 2.0 * Math.PI * index / size

    private fun labelFor(factor: FactorType): String =
        when (factor) {
            FactorType.SIZE -> "Size"
            FactorType.CONTROL_FLOW -> "Flow"
            FactorType.NESTING -> "Nest"
            FactorType.DOMAIN_COUPLING -> "Domain"
            FactorType.READABILITY -> "Read"
        }
}
