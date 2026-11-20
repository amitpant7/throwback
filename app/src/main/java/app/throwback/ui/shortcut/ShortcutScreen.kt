package app.throwback.ui.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.throwback.MainActivity
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.PrimaryButton
import app.throwback.core.ui.TopBar
import app.throwback.core.ui.UiText

/** Icon choices for the pinned shortcut (DECISIONS.md #31). */
internal enum class ShortcutStyle(
    @DrawableRes val icon: Int,
    @DrawableRes val preview: Int,
    @StringRes val label: Int,
) {
    Sage(R.drawable.ui_shortcut_sage, R.drawable.ui_shortcut_sage_preview, R.string.ui_shortcut_icon_sage),
    Dark(R.drawable.ui_shortcut_dark, R.drawable.ui_shortcut_dark_preview, R.string.ui_shortcut_icon_dark),
    Light(R.drawable.ui_shortcut_light, R.drawable.ui_shortcut_light_preview, R.string.ui_shortcut_icon_light),
    Photo(R.drawable.ui_shortcut_photo, R.drawable.ui_shortcut_photo_preview, R.string.ui_shortcut_icon_photo),
}

internal object ShortcutLabel {
    /** Launchers truncate long labels anyway; the platform recommends at most 25 characters. */
    const val MAX_LENGTH = 25

    /** What the shortcut will be called: trimmed, never blank, at most [MAX_LENGTH] characters. */
    fun sanitize(input: String, fallback: String): String {
        val cleaned = input.replace(Regex("\\s+"), " ").trim()
        return cleaned.ifEmpty { fallback }.take(MAX_LENGTH).trim()
    }
}

internal enum class PinResult { Requested, Unsupported }

internal object HomeShortcut {
    fun request(context: Context, name: String, style: ShortcutStyle): PinResult {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return PinResult.Unsupported
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context, MainActivity::class.java))
        val shortcut = ShortcutInfoCompat.Builder(context, "throwback-home-${System.currentTimeMillis()}")
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(IconCompat.createWithResource(context, style.icon))
            .setIntent(intent)
            .build()
        return if (ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
            PinResult.Requested
        } else {
            PinResult.Unsupported
        }
    }
}

@Composable
internal fun ShortcutScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val defaultName = stringResource(R.string.ui_shortcut_default_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var style by rememberSaveable { mutableStateOf(ShortcutStyle.Sage) }
    val label = ShortcutLabel.sanitize(name, defaultName)
    val requested = stringResource(R.string.ui_shortcut_requested)
    val unsupported = stringResource(R.string.ui_shortcut_unsupported)

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        TopBar(stringResource(R.string.ui_shortcut_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp),
        ) {
            // Preview
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .padding(vertical = 28.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(style.preview),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)),
                )
                Text(label, style = UiText.body14, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ui_shortcut_preview), style = UiText.fine, color = colors.muted)
            }

            FieldLabel(stringResource(R.string.ui_shortcut_name_label))
            val nameLabel = stringResource(R.string.ui_shortcut_name_label)
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(ShortcutLabel.MAX_LENGTH) },
                singleLine = true,
                textStyle = UiText.row.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = nameLabel },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { inner() }
                },
            )

            FieldLabel(stringResource(R.string.ui_shortcut_icon_label))
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShortcutStyle.entries.forEach { option ->
                    IconOption(option, selected = option == style, onSelect = { style = option })
                }
            }
            Text(
                stringResource(R.string.ui_shortcut_note),
                style = UiText.sub,
                color = colors.muted,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
        PrimaryButton(
            stringResource(R.string.ui_shortcut_add),
            onClick = {
                focus.clearFocus()
                when (HomeShortcut.request(context, label, style)) {
                    PinResult.Requested -> showMessage(requested)
                    PinResult.Unsupported -> showMessage(unsupported)
                }
            },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = UiText.section,
        color = ThrowbackTheme.colors.muted,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun IconOption(option: ShortcutStyle, selected: Boolean, onSelect: () -> Unit) {
    val colors = ThrowbackTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val description = stringResource(option.label)
    Box(
        Modifier
            .size(64.dp)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = description },
    ) {
        Image(
            painter = painterResource(option.preview),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        // Selected: 2dp ring in the text colour. Unselected: a hairline, so the dark option
        // stays visible on the dark background.
        Box(
            Modifier
                .fillMaxSize()
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.text else colors.line, shape),
        )
    }
}
