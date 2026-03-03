package com.sqb.complexityradar.ide.ui

import com.intellij.ui.JBColor
import com.sqb.complexityradar.core.model.Severity
import java.awt.Color

private const val POOP_GLYPH = "\uD83D\uDCA9"
private const val EMPTY_GLYPH = "\u00B7"
private const val MAX_POOP_COUNT = 5

fun poopScoreCount(score: Int): Int {
    val clamped = score.coerceIn(0, 100)
    return if (clamped == 0) {
        0
    } else {
        ((clamped - 1) / 20) + 1
    }
}

fun poopGlyphStrip(
    score: Int,
    showEmpty: Boolean = true,
): String {
    val count = poopScoreCount(score)
    return buildString {
        repeat(count) {
            append(POOP_GLYPH)
        }
        if (showEmpty) {
            repeat(MAX_POOP_COUNT - count) {
                append(EMPTY_GLYPH)
            }
        }
    }
}

fun poopBadgeLabel(score: Int): String = "${poopGlyphStrip(score)} ${poopScoreCount(score)}/$MAX_POOP_COUNT"

fun poopTooltipLabel(score: Int): String = "Poop ${poopScoreCount(score)}/$MAX_POOP_COUNT | Raw $score/100"

fun poopAccentColor(severity: Severity): JBColor =
    when (severity) {
        Severity.GREEN -> JBColor(Color(0xB58B57), Color(0xD0A873))
        Severity.YELLOW -> JBColor(Color(0x9B6E38), Color(0xBC8646))
        Severity.ORANGE -> JBColor(Color(0x7B4F26), Color(0x986330))
        Severity.RED -> JBColor(Color(0x5B3318), Color(0x784420))
    }

fun poopMutedColor(): JBColor = JBColor(Color(0xC6BAA8), Color(0x4D4338))
