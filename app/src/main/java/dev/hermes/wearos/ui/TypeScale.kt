package dev.hermes.wearos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.hermes.wearos.domain.FontScale

/**
 * PINCH FONT-SCALE plumbing (owner ask: pinch scales TYPOGRAPHY, not a visual zoom). MainActivity
 * provides the persisted AppSettings value once at the top of the tree; MdText blocks and the
 * message-bubble styles consume it — image/mermaid card FOOTPRINTS never read it, so cards keep
 * their fixed width while text breathes. The pure clamp/grid math lives in domain/FontScale.
 */
val LocalFontScale = compositionLocalOf { FontScale.DEFAULT }

/** Consume the provided scale (see [scaledBy]). At 1.00× the style passes through untouched. */
@Composable
fun TextStyle.scaled(): TextStyle = scaledBy(LocalFontScale.current)

/**
 * Typeface-size copy of a style: fontSize + lineHeight scaled together (Wear typography defines
 * both in sp). At 1.00× returns the SAME instance (skips the copy work); an unspecified
 * lineHeight stays unspecified.
 */
fun TextStyle.scaledBy(k: Float): TextStyle {
    if (FontScale.isDefault(k)) return this
    val s = FontScale.quantize(k)
    return copy(
        fontSize = if (fontSize != TextUnit.Unspecified) (fontSize.value * s).sp else TextUnit.Unspecified,
        lineHeight = if (lineHeight != TextUnit.Unspecified) (lineHeight.value * s).sp else TextUnit.Unspecified,
    )
}
