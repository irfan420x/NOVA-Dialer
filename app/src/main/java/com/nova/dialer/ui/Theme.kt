package com.nova.dialer.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// NOVA Nebula — Dark Premium design tokens, shared across all NOVA apps.
// Exact values from ~/workspace/nova-design/NOVA-NEBULA-DESIGN.md; never
// change a token value per app.
val NovaBg = Color(0xFF0A0C1C)
val NovaSurface = Color(0xFF14172B)
val NovaCard = Color(0xFF1B1F38)
val NovaOutline = Color(0x14FFFFFF) // white 8% — 1dp card borders
val NovaAccent = Color(0xFF8B5CF6)
val NovaGradientStart = Color(0xFF7C3AED)
val NovaGradientEnd = Color(0xFF3B82F6)
val NovaAccent2 = Color(0xFF22D3EE)
val NovaTertiary = Color(0xFFEC4899)
val NovaText = Color(0xFFEDEFF7)
val NovaMuted = Color(0xFF9AA3C7)
val NovaMissed = Color(0xFFF87171)
val NovaGood = Color(0xFF34D399)

// Gradient for FABs, the active-tab pill, the call button and hero accents.
val NovaGradientBrush = Brush.horizontalGradient(listOf(NovaGradientStart, NovaGradientEnd))

// Shapes per the spec: cards 20dp, buttons 14dp, chips 12dp, FAB 16dp
// (chips and FAB get their radius set explicitly where used).
private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

private val NovaColors = darkColorScheme(
    primary = NovaAccent,
    primaryContainer = NovaGradientStart,
    onPrimary = Color.White,
    onPrimaryContainer = Color.White,
    secondary = NovaAccent2,
    secondaryContainer = Color(0xFF0E7490),
    onSecondary = Color(0xFF06202A),
    onSecondaryContainer = Color.White,
    tertiary = NovaTertiary,
    tertiaryContainer = Color(0xFF9D174D),
    onTertiary = Color.White,
    onTertiaryContainer = Color.White,
    background = NovaBg,
    surface = NovaSurface,
    surfaceVariant = NovaCard,
    surfaceTint = NovaAccent,
    onBackground = NovaText,
    onSurface = NovaText,
    onSurfaceVariant = NovaMuted,
    error = NovaMissed,
    errorContainer = Color(0xFF7F1D1D),
    onError = Color.White,
    onErrorContainer = Color(0xFFFECACA),
    outline = NovaOutline,
    outlineVariant = Color(0x0AFFFFFF),
    scrim = Color.Black
)

private val NovaLightColors = lightColorScheme(
    primary = NovaAccent,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimary = Color.White,
    onPrimaryContainer = Color(0xFF4C1D95),
    secondary = Color(0xFF0891B2),
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondary = Color.White,
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFFDB2777),
    tertiaryContainer = Color(0xFFFBCFE8),
    onTertiary = Color.White,
    onTertiaryContainer = Color(0xFF500724),
    background = Color(0xFFF4F5FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EBF5),
    surfaceTint = NovaAccent,
    onBackground = Color(0xFF12141F),
    onSurface = Color(0xFF12141F),
    onSurfaceVariant = Color(0xFF5A6076),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onError = Color.White,
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0x1F12141F),
    outlineVariant = Color(0x1412141F),
    scrim = Color.Black
)

@Composable
fun NovaTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NovaColors else NovaLightColors,
        shapes = NovaShapes,
        content = content
    )
}
