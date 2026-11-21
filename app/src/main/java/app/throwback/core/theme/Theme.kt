package app.throwback.core.theme

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.throwback.R

/**
 * Throwback's "Sage" theme (decided 2026-11-20, see DECISIONS.md).
 * Owned by integration. Feature code reads colours via [ThrowbackTheme.colors] and
 * type via MaterialTheme.typography; it never hard-codes hex values.
 */
@Immutable
data class ThrowbackColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val ok: Color,
)

val SageDark = ThrowbackColors(
    bg = Color(0xFF101513),
    surface = Color(0xFF18201C),
    surface2 = Color(0xFF26322B),
    text = Color(0xFFE9F0EC),
    muted = Color(0xFF9FB0A7),
    line = Color(0xFF26322C),
    accent = Color(0xFF8FD1AD),
    onAccent = Color(0xFF0C1F15),
    accentText = Color(0xFF8FD1AD),
    ok = Color(0xFF5CC98F),
)

val SageLight = ThrowbackColors(
    bg = Color(0xFFF3F6F3),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE2EAE4),
    text = Color(0xFF14201A),
    muted = Color(0xFF56655C),
    line = Color(0xFFDFE7E1),
    accent = Color(0xFF2F7D57),
    onAccent = Color(0xFFFFFFFF),
    accentText = Color(0xFF2F7D57),
    ok = Color(0xFF1D7A4C),
)

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    resId = R.font.nunito,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Nunito = FontFamily(nunito(400), nunito(600), nunito(700), nunito(800))

private val ThrowbackTypography = Typography(
    displaySmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
)

private val LocalThrowbackColors = staticCompositionLocalOf { SageDark }

object ThrowbackTheme {
    val colors: ThrowbackColors
        @Composable get() = LocalThrowbackColors.current
}

@Composable
fun ThrowbackTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) SageDark else SageLight
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            outline = c.line,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            outline = c.line,
        )
    }
    SystemBarsFollowDarkMode(darkTheme)
    CompositionLocalProvider(LocalThrowbackColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = ThrowbackTypography, content = content)
    }
}

/**
 * Status and navigation bar icons (and, before Android 10, the navigation bar scrim) for dark mode.
 * MainActivity sets the bars up once with enableEdgeToEdge(), but the activity handles dark-mode
 * changes itself (configChanges uiMode), so a switch while the app runs kept the old icon colours.
 * Setting up again reads the new configuration. The bars stay transparent: Throwback's screens and
 * the Instagram page (which paints its own background behind them) show through. Nothing to do
 * outside an activity (the guard's overlay window).
 */
@Composable
private fun SystemBarsFollowDarkMode(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context.findActivity() ?: return
    LaunchedEffect(activity, darkTheme) { activity.enableEdgeToEdge() }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
