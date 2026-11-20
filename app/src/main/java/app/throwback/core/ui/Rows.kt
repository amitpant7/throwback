package app.throwback.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme

/*
 * Rows inside a TbCard, separated by CardDivider.
 */

/**
 * Row that opens another screen: icon, title, optional subtitle and value, chevron. Disabled, the
 * title is muted and taps do nothing.
 */
@Composable
internal fun NavRow(
    title: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    value: String? = null,
    minHeight: Dp = 56.dp,
    enabled: Boolean = true,
) {
    val colors = ThrowbackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) TbIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = UiText.row, color = if (enabled) colors.text else colors.muted)
            if (subtitle != null) {
                Text(subtitle, style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (value != null) Text(value, style = UiText.body14, color = colors.muted)
        TbIcon(R.drawable.ic_chevron, size = 18.dp, tint = colors.muted)
    }
}

/**
 * A title with its value on the right ("Ads   12"), one item for TalkBack ("Ads: 12"). A null [value]
 * shows a dash and reads "not known yet". [strong] shows the value as large as the title. [below] goes
 * under both (a progress bar).
 */
@Composable
internal fun ValueRow(
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = ThrowbackTheme.colors
    val description = stringResource(R.string.label_value, title, value ?: stringResource(R.string.value_unknown_description))
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = UiText.row, color = colors.text, modifier = Modifier.weight(1f))
            Text(
                value ?: stringResource(R.string.value_unknown),
                style = if (strong) UiText.rowStrong else UiText.body14,
                color = if (strong) colors.text else colors.muted,
            )
        }
        below()
    }
}

/**
 * Row that shows or hides more below it: icon, title and summary, and a chevron pointing down (up
 * while [expanded]). TalkBack hears whether it is expanded and what a tap does.
 */
@Composable
internal fun ExpandableRow(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    @DrawableRes icon: Int,
) {
    val colors = ThrowbackTheme.colors
    val state = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { stateDescription = state }
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand),
                onClick = onToggle,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TbIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = UiText.row, color = colors.text)
            Text(summary, style = UiText.sub, color = colors.muted)
        }
        ExpandChevron(expanded)
    }
}

/** Chevron pointing down, or up while [expanded], for rows that show more below them. */
@Composable
internal fun ExpandChevron(expanded: Boolean) {
    TbIcon(
        R.drawable.ic_chevron,
        Modifier.rotate(if (expanded) 270f else 90f),
        size = 18.dp,
        tint = ThrowbackTheme.colors.muted,
    )
}

/** Row with a switch; the whole row toggles and reads as one switch to TalkBack. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    minHeight: Dp = 56.dp,
    titleStyle: TextStyle = UiText.row,
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
        if (icon != null) TbIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, color = colors.text)
            if (subtitle != null) {
                Text(subtitle, style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(checked = checked, onCheckedChange = null, colors = switchColors())
    }
}

@Composable
private fun switchColors(): SwitchColors {
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
