package app.throwback.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme

/** Card radius used across settings screens. */
internal val CardShape = RoundedCornerShape(18.dp)
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

/** Line icon from the ui_ic_* set, tinted. Decorative unless [contentDescription] is given. */
@Composable
internal fun LineIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = ThrowbackTheme.colors.text,
    contentDescription: String? = null,
) {
    Icon(painterResource(icon), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** 60dp header with a back arrow and the screen title. */
@Composable
internal fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            LineIcon(R.drawable.ui_ic_back, contentDescription = stringResource(R.string.ui_back))
        }
        Text(
            text = title,
            style = UiText.screenTitle,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Rounded surface card (18dp radius) holding rows or free content. */
@Composable
internal fun TbCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(ThrowbackTheme.colors.surface),
        content = content,
    )
}

/** Hairline between rows inside a card. */
@Composable
internal fun CardDivider() {
    HorizontalDivider(thickness = 1.dp, color = ThrowbackTheme.colors.line)
}

/** Small muted label above a card ("Focus", "App", ...). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = UiText.section,
        color = ThrowbackTheme.colors.muted,
        modifier = modifier
            .padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** Full-width 52dp accent button. */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = ThrowbackTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.surface2,
            disabledContentColor = colors.muted,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(text, style = UiText.button, textAlign = TextAlign.Center)
    }
}

/** Full-width 52dp neutral button (surface2). */
@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    Button(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.surface2, contentColor = colors.text),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(text, style = UiText.button, textAlign = TextAlign.Center)
    }
}

/** Text-only 52dp button ("Not now", "Decide later"). */
@Composable
internal fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = ThrowbackTheme.colors.muted,
    style: androidx.compose.ui.text.TextStyle = UiText.button,
) {
    TextButton(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(text, style = style, textAlign = TextAlign.Center)
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
        LineIcon(R.drawable.ui_ic_check, size = 16.dp, tint = colors.ok)
        Text(text, style = UiText.subStrong, color = colors.ok)
    }
}

/** Settings row that opens another screen: icon, title, optional subtitle and value, chevron. */
@Composable
internal fun NavRow(
    title: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    value: String? = null,
    minHeight: Dp = 56.dp,
) {
    val colors = ThrowbackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) LineIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = UiText.row, color = colors.text)
            if (subtitle != null) {
                Text(subtitle, style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (value != null) Text(value, style = UiText.body14, color = colors.muted)
        LineIcon(R.drawable.ui_ic_chevron, size = 18.dp, tint = colors.muted)
    }
}

/** Settings row with a switch; the whole row toggles and reads as one switch to TalkBack. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    minHeight: Dp = 56.dp,
    titleStyle: androidx.compose.ui.text.TextStyle = UiText.row,
) {
    val colors = ThrowbackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) LineIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, color = colors.text)
            if (subtitle != null) {
                Text(subtitle, style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(checked = checked, onCheckedChange = null, colors = throwbackSwitchColors())
    }
}

@Composable
internal fun throwbackSwitchColors(): SwitchColors {
    val colors = ThrowbackTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onAccent,
        checkedTrackColor = colors.accent,
        checkedBorderColor = colors.accent,
        uncheckedThumbColor = colors.muted,
        uncheckedTrackColor = colors.surface2,
        uncheckedBorderColor = colors.surface2,
    )
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
