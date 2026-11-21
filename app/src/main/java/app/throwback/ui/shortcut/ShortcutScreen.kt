package app.throwback.ui.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.R
import app.throwback.core.OpenThrowback
import app.throwback.core.Supporter
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.TbTopBar
import app.throwback.core.ui.UiText
import app.throwback.core.ui.doneHidesKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Preset icons for the pinned shortcut (DECISIONS.md #31): the adaptive [icon] given to the launcher,
 * and its two layers, which the in-app previews draw, so a preview always matches the icon.
 */
internal enum class ShortcutStyle(
    @DrawableRes val icon: Int,
    @DrawableRes val foreground: Int,
    @ColorRes val background: Int,
    @StringRes val label: Int,
) {
    // ui_shortcut_sage keeps its name from before the logo, so shortcuts already pinned update in place.
    App(R.drawable.ui_shortcut_sage, R.drawable.ui_shortcut_mark, R.color.ic_launcher_background, R.string.ui_shortcut_icon_app),
    Dark(R.drawable.ui_shortcut_dark, R.drawable.ui_shortcut_mark, R.color.shortcut_bg_dark, R.string.ui_shortcut_icon_dark),
    Light(R.drawable.ui_shortcut_light, R.drawable.ui_shortcut_mark_light, R.color.shortcut_bg_light, R.string.ui_shortcut_icon_light),
    Photo(R.drawable.ui_shortcut_photo, R.drawable.ui_shortcut_photo_fg, R.color.shortcut_bg_photo, R.string.ui_shortcut_icon_photo),
}

internal object ShortcutLabel {
    /** Launchers truncate long labels anyway; the platform recommends at most 25 characters. */
    const val MAX_LENGTH = 25

    private val WHITESPACE = Regex("\\s+")

    /** What the shortcut will be called: trimmed, never blank, at most [MAX_LENGTH] characters. */
    fun sanitize(input: String, fallback: String): String {
        val cleaned = input.replace(WHITESPACE, " ").trim()
        return cleaned.ifEmpty { fallback }.take(MAX_LENGTH).trim()
    }
}

internal enum class PinResult { Requested, Unsupported }

internal object HomeShortcut {
    fun request(context: Context, name: String, icon: IconCompat): PinResult {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return PinResult.Unsupported
        val intent = OpenThrowback.intent(context, Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val shortcut = ShortcutInfoCompat.Builder(context, "throwback-home-${System.currentTimeMillis()}")
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return if (ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
            PinResult.Requested
        } else {
            PinResult.Unsupported
        }
    }
}

/**
 * Make It Yours (DECISIONS.md #31, #4d): a home-screen shortcut with the user's name and a preset
 * icon or their own picture. Free in builds without Google Play tips (#4e); with them, unlocked by
 * one tip, and until then everything can be tried out and the button leads to Support instead.
 */
@Composable
internal fun ShortcutScreen(onBack: () -> Unit, onOpenSupport: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val unlocked by Supporter.makeItYours(context).collectAsStateWithLifecycle()
    val defaultName = stringResource(R.string.ui_shortcut_default_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var style by rememberSaveable { mutableStateOf(ShortcutStyle.App) }
    // The user's picture: its address survives recreation; the icon made from it is read again.
    var ownUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var useOwn by rememberSaveable { mutableStateOf(false) }
    var own by remember { mutableStateOf<Bitmap?>(null) }
    val label = ShortcutLabel.sanitize(name, defaultName)
    val requested = stringResource(R.string.ui_shortcut_requested)
    val unsupported = stringResource(R.string.ui_shortcut_unsupported)
    val ownFailed = stringResource(R.string.ui_shortcut_own_failed)

    val pick = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) ownUri = uri }
    val pickPicture = { pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
    LaunchedEffect(ownUri) {
        val uri = ownUri ?: return@LaunchedEffect
        val made = withContext(Dispatchers.IO) { ShortcutImage.load(context, uri) }
        own = made
        useOwn = made != null
        if (made == null) {
            ownUri = null
            showMessage(ownFailed)
        }
    }
    val ownPicture = own?.takeIf { useOwn }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        TbTopBar(stringResource(R.string.ui_shortcut_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp),
        ) {
            if (!unlocked) {
                TbCard(
                    radius = 20.dp,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Text(stringResource(R.string.ui_shortcut_locked_title), style = UiText.body14, color = colors.text)
                    Text(stringResource(R.string.ui_shortcut_locked_body), style = UiText.sub, color = colors.muted)
                }
            }
            // Preview
            TbCard(
                radius = 20.dp,
                contentPadding = PaddingValues(vertical = 28.dp, horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (ownPicture != null) PictureIcon(ownPicture, Modifier.size(72.dp)) else PresetIcon(style, Modifier.size(72.dp))
                Text(label, style = UiText.body14, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ui_shortcut_preview), style = UiText.fine, color = colors.muted)
            }

            SectionLabel(stringResource(R.string.ui_shortcut_name_label))
            val nameLabel = stringResource(R.string.ui_shortcut_name_label)
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(ShortcutLabel.MAX_LENGTH) },
                singleLine = true,
                textStyle = UiText.row.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = doneHidesKeyboard(),
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

            SectionLabel(stringResource(R.string.ui_shortcut_icon_label))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val tile = Modifier.weight(1f)
                ShortcutStyle.entries.forEach { option ->
                    IconOption(stringResource(option.label), selected = !useOwn && option == style, modifier = tile, onSelect = {
                        style = option
                        useOwn = false
                    }) { PresetIcon(option, Modifier.fillMaxSize()) }
                }
                // Your own picture: picks one the first time, and again when tapped while chosen.
                IconOption(stringResource(R.string.ui_shortcut_icon_own), selected = useOwn, modifier = tile, onSelect = {
                    if (useOwn || own == null) pickPicture() else useOwn = true
                }) {
                    val picture = own
                    if (picture != null) {
                        PictureIcon(picture, Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_image_add), contentDescription = null, tint = colors.muted)
                        }
                    }
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
        TbButton(
            stringResource(if (unlocked) R.string.ui_shortcut_add else R.string.ui_shortcut_unlock),
            onClick = {
                focus.clearFocus()
                if (!unlocked) {
                    onOpenSupport()
                } else {
                    val icon = ownPicture?.let(IconCompat::createWithAdaptiveBitmap)
                        ?: IconCompat.createWithResource(context, style.icon)
                    when (HomeShortcut.request(context, label, icon)) {
                        PinResult.Requested -> showMessage(requested)
                        PinResult.Unsupported -> showMessage(unsupported)
                    }
                }
            },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        )
    }
}

/** A preset icon as the launcher shows it. */
@Composable
private fun PresetIcon(style: ShortcutStyle, modifier: Modifier) =
    LauncherIcon(painterResource(style.foreground), colorResource(style.background), modifier)

/** The user's picture as the launcher shows it ([ShortcutImage] made it a full layer). */
@Composable
private fun PictureIcon(picture: Bitmap, modifier: Modifier) =
    LauncherIcon(remember(picture) { BitmapPainter(picture.asImageBitmap()) }, Color.White, modifier)

/**
 * An adaptive icon's [layer] as a launcher shows it: the middle 72 of its 108 dp (the rest is room
 * for the launcher's mask and motion), on [background], in a rounded square.
 */
@Composable
private fun LauncherIcon(layer: Painter, background: Color, modifier: Modifier) {
    BoxWithConstraints(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(percent = 28)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Image(layer, contentDescription = null, modifier = Modifier.requiredSize(maxWidth * 1.5f))
    }
}

@Composable
private fun IconOption(
    description: String,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = ThrowbackTheme.colors
    val shape = RoundedCornerShape(percent = 28)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = description },
    ) {
        content()
        // Selected: 2dp ring in the text colour. Unselected: a hairline, so the dark option
        // stays visible on the dark background.
        Box(
            Modifier
                .fillMaxSize()
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.text else colors.line, shape),
        )
    }
}
