package com.sqb.complexityradar.ide.ui

import com.intellij.ui.JBColor
import java.awt.Color

object UiThemeTokens {
    // Background layers
    val bgCard = JBColor(Color(0xFFFFFF), Color(0x2B2D30))
    val bgHover = JBColor(Color(0xE8E8E8), Color(0x383A3F))

    // Borders
    val borderDefault = JBColor(Color(0xD0D0D0), Color(0x393B40))

    // Text
    val textPrimary = JBColor(Color(0x1C1C1E), Color(0xDFE1E5))
    val textSecondary = JBColor(Color(0x6B6B70), Color(0xA9B7C6))

    // Accent (warm amber/brown from reference)
    val accentPrimary = JBColor(Color(0x9A7340), Color(0xA87B44))
    val accentBg = JBColor(Color(0xFBF4E8), Color(0x2A2018))

    // Primary button
    val btnPrimaryBg = JBColor(Color(0xA87B44), Color(0xA87B44))
    val btnPrimaryFg = JBColor(Color(0xFFFFFF), Color(0xFFFFFF))
    val btnPrimaryHover = JBColor(Color(0x8B6432), Color(0xC09050))
    val btnPrimaryBorder = JBColor(Color(0x7A5528), Color(0xB88840))

    // Secondary button
    val btnSecondaryBg = JBColor(Color(0xFFFFFF), Color(0x2B2D30))
    val btnSecondaryFg = JBColor(Color(0x3C3C3C), Color(0xDFE1E5))
    val btnSecondaryHover = JBColor(Color(0xEBEBEB), Color(0x383A3F))
    val btnSecondaryBorder = JBColor(Color(0xC8C8C8), Color(0x393B40))

    // Icon button
    val btnIconHover = JBColor(Color(0xE8E8E8), Color(0x383A3F))

    // Segmented toggle
    val toggleBg = JBColor(Color(0xEAEAEA), Color(0x3A3C40))
    val toggleSelectedBg = JBColor(Color(0xFFFFFF), Color(0x2B2D30))
    val toggleSelectedFg = JBColor(Color(0x1C1C1E), Color(0xDFE1E5))
    val toggleUnselectedFg = JBColor(Color(0x6B6B70), Color(0xA9B7C6))

    // Footer
    val footerBg = JBColor(Color(0xF5F5F5), Color(0x1E1F22))
    val footerBorder = JBColor(Color(0xD6D6D6), Color(0x393B40))
}
