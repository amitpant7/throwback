package app.throwback.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Text styles used by the ui screens, derived from the theme's typography (Nunito) so sizes
 * match the mockups. Colours are applied at the call site from ThrowbackTheme.colors.
 */
internal object UiText {
    /** First-launch headline: 32sp bold, tight. */
    val hero: TextStyle
        @Composable get() = MaterialTheme.typography.displaySmall.copy(
            fontWeight = FontWeight.Bold, lineHeight = 35.sp, letterSpacing = (-0.02).em,
        )

    /** "Instagram app found" headline: 28sp extra bold. */
    val bigTitle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.01).em)

    /** Wizard step headline: 26sp bold. */
    val stepTitle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineSmall.copy(
            fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em,
        )

    /** Wordmark next to the logo. */
    val wordmark: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.copy(
            fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em,
        )

    /** Top-bar title: 20sp semibold. */
    val screenTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

    /** Intro paragraph under a headline: 15sp. */
    val lead: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)

    /** Settings row title: 16sp. */
    val row: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge

    /** 16sp semibold (card titles). */
    val rowStrong: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)

    /** 15sp (wizard rows). */
    val row15: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp)

    /** 15sp semibold (permission titles). */
    val row15Strong: TextStyle
        @Composable get() = row15.copy(fontWeight = FontWeight.SemiBold)

    /** 14sp body. */
    val body14: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium

    /** 14sp semibold (pills, values). */
    val body14Strong: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    /** 13sp secondary text. */
    val sub: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall

    /** 13sp semibold (status labels). */
    val subStrong: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)

    /** 12sp fine print. */
    val fine: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp)

    /** Section label above a card: 13sp semibold. */
    val section: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.02.em)

    /** Big buttons: 16sp semibold. */
    val button: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

    /** Mindful pause line: 30sp regular. */
    val pauseLine: TextStyle
        @Composable get() = MaterialTheme.typography.headlineMedium.copy(
            fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.01).em,
        )
}
