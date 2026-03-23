package com.bril.code_radar.core.scoring

import com.bril.code_radar.core.model.ScalePoint

object Normalization {
    fun piecewise(
        value: Double,
        points: List<ScalePoint>,
    ): Double {
        if (points.isEmpty()) {
            return 0.0
        }
        if (value <= points.first().x) {
            return points.first().y
        }
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            if (value <= current.x) {
                val ratio = (value - previous.x) / (current.x - previous.x).coerceAtLeast(1e-9)
                return (previous.y + (current.y - previous.y) * ratio).coerceIn(0.0, 1.0)
            }
        }
        return points.last().y.coerceIn(0.0, 1.0)
    }

    fun average(values: Iterable<Double>): Double {
        val list = values.toList()
        if (list.isEmpty()) {
            return 0.0
        }
        return list.sum() / list.size
    }
}
