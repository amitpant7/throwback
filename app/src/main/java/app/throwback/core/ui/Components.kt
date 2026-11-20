package app.throwback.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme

/*
 * Throwback's UI kit, shared by the ui and timelimit screens: icons, the top bar, buttons and labels
 * here; layout pieces (scaffold, cards) in Layout.kt; settings rows in Rows.kt. Colours come from
 * ThrowbackTheme.colors, type from UiText.
 */

private val ButtonShape = RoundedCornerShape(14.dp)

/** The Throwback logo: the rewind mark on an accent-coloured rounded square (placeholder art). */
@Composable
internal fun ThrowbackLogo(size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    // The final logo (owner, 2026-11-20): the rounded mint tile with the portal, arrow and figure.
    Image(
        painter = painterResource(R.drawable.tb_logo),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/**
 * Line icon from the ic_* set, tinted. Decorative unless [contentDescription] is given. The default
 * tint is the text colour rather than LocalContentColor: screens draw on a plain background, where
 * LocalContentColor is black.
 */
@Composable
internal fun TbIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = ThrowbackTheme.colors.text,
    contentDescription: String? = null,
) {
    Icon(painterResource(icon), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** 48dp back arrow ("Back" for TalkBack). */
@Composable
internal fun TbBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.size(48.dp)) {
        TbIcon(R.drawable.ic_back, contentDescription = stringResource(R.string.back))
    }
}

/** Header with a back arrow and the screen title: 60dp, taller when a large font size wraps the title. */
@Composable
internal fun TbTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TbBackButton(onBack)
        Text(
            text = title,
            style = UiText.screenTitle,
            color = ThrowbackTheme.colors.text,
            modifier = Modifier.semantics { heading() },
        )
    }
}

internal enum class TbButtonStyle {
    /** Accent fill: the main action. */
    Primary,

    /** Neutral fill. */
    Secondary,

    /** No fill, in the text colour ("Close Throwback", "Decide later"). */
    Plain,

    /** No fill, muted ("Not now"). */
    Quiet,
}

/**
 * Full-width button, at least 52dp tall (mockup `.btn`). Disabled, it looks like the mockup's waiting
 * button. [icon] goes before the text, in the content colour so it greys out with it.
 */
@Composable
internal fun TbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TbButtonStyle = TbButtonStyle.Primary,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    textStyle: TextStyle = UiText.button,
) {
    val colors = ThrowbackTheme.colors
    val (container, content) = when (style) {
        TbButtonStyle.Primary -> colors.accent to colors.onAccent
        TbButtonStyle.Secondary -> colors.surface2 to colors.text
        TbButtonStyle.Plain -> Color.Transparent to colors.text
        TbButtonStyle.Quiet -> Color.Transparent to colors.muted
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = if (container == Color.Transparent) Color.Transparent else colors.surface2,
            disabledContentColor = colors.muted,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        if (icon != null) {
            TbIcon(icon, size = 20.dp, tint = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = textStyle, textAlign = TextAlign.Center)
    }
}

/**
 * Compact 36dp accent pill ("Install", "Allow"); the touch target is still 48dp.
 * [accessibilityLabel] replaces the short visible text for TalkBack ("Allow usage access").
 */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null,
) {
    val colors = ThrowbackTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = colors.accent,
        contentColor = colors.onAccent,
        modifier = modifier,
    ) {
        Box(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = UiText.body14Strong,
                modifier = if (accessibilityLabel != null) {
                    Modifier.semantics { contentDescription = accessibilityLabel }
                } else {
                    Modifier
                },
            )
        }
    }
}

/** Green check + label, e.g. "Done" next to a granted permission. */
@Composable
internal fun DoneLabel(text: String, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TbIcon(R.drawable.ic_check, size = 16.dp, tint = colors.ok)
        Text(text, style = UiText.subStrong, color = colors.ok)
    }
}

/** Four-segment progress indicator used at the top of each wizard step. */
@Composable
internal fun StepBars(current: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < current) colors.accent else colors.surface2),
            )
        }
    }
}

/** A line of text after a small accent icon (the rules of the time limit, what connecting does). */
@Composable
internal fun IconBullet(@DrawableRes icon: Int, text: String) {
    val colors = ThrowbackTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TbIcon(icon, size = 20.dp, tint = colors.accentText)
        Text(text, style = UiText.body14, color = colors.text)
    }
}
