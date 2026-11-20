package app.throwback.timelimit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.timelimit.DefaultTimeLimitController
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.Rules
import app.throwback.timelimit.engine.TimeLimitView
import kotlinx.coroutines.delay

/*
 * Building blocks shared by the lock screens and the settings screens, following the mockups
 * (Sage theme: colours from ThrowbackTheme.colors, type from MaterialTheme.typography).
 */

// ---- Live state -----------------------------------------------------------------------------

/**
 * The controller's view for a time-limit screen, refreshed every second while the screen is resumed
 * so countdowns and the wrong-PIN pause keep moving (the controller's own tick runs only while
 * Instagram is in front). Stops when the app goes to the background: each refresh may save.
 */
@Composable
internal fun rememberLiveView(controller: DefaultTimeLimitController): State<TimeLimitView> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                controller.refresh()
                delay(1_000)
            }
        }
    }
    return controller.view.collectAsStateWithLifecycle()
}

// ---- Text styles derived from the theme's typography ----------------------------------------

/** Big screen titles ("Time's up for today"): 30sp in the mockups. */
@Composable
internal fun screenTitleStyle(): TextStyle =
    MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em)

/** Large countdown numbers ("3:28"). Tabular figures so they don't jiggle as they tick. */
@Composable
internal fun countdownStyle(size: Int): TextStyle =
    MaterialTheme.typography.displaySmall.copy(
        fontSize = size.sp,
        lineHeight = (size + 6).sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )

/** Muted body copy under titles: 15sp in the mockups. */
@Composable
internal fun leadStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)

// ---- Durations as text ----------------------------------------------------------------------

@Composable
internal fun minSecText(millis: Long): String {
    val t = Durations.minSec(millis)
    return stringResource(R.string.tl_dur_min_sec, t.minutes, t.seconds)
}

@Composable
internal fun hoursMinutesText(millis: Long): String {
    val t = Durations.hoursMinutes(millis)
    return if (t.hours > 0) stringResource(R.string.tl_dur_hours_minutes, t.hours, t.minutes)
    else stringResource(R.string.tl_dur_minutes, t.minutes)
}

/** "2d 23h" for a day or more, else "6h 42m" / "42m". */
@Composable
internal fun longDurationText(millis: Long): String =
    Durations.daysHours(millis)?.let { stringResource(R.string.tl_dur_days_hours, it.days, it.hours) }
        ?: hoursMinutesText(millis)

// ---- Layout ---------------------------------------------------------------------------------

/**
 * A full-height column that scrolls on small screens. Children can use `Spacer(Modifier.weight(1f))`
 * to push content to the bottom, as in the mockups.
 */
@Composable
internal fun ScreenColumn(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(contentPadding),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

@Composable
internal fun TlIcon(
    @DrawableRes res: Int,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(painterResource(res), contentDescription, modifier.size(size), tint = tint)
}

/** 60dp top bar with a 48dp back button, as in the settings mockups. */
@Composable
internal fun TlHeader(title: String, onBack: () -> Unit) {
    val c = ThrowbackTheme.colors
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            TlIcon(R.drawable.tl_ic_back, size = 22.dp, tint = c.text, contentDescription = stringResource(R.string.tl_back))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = c.text,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Small capitals label ("EXTENSION 1 OF 2", "RESET REQUESTED"). */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.04.em),
        color = ThrowbackTheme.colors.accentText,
        modifier = modifier,
    )
}

/** Muted section title ("1 · How long per day?", "PIN"). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.02.em),
        color = ThrowbackTheme.colors.muted,
        modifier = modifier.padding(horizontal = 4.dp).padding(bottom = 10.dp).semantics { heading() },
    )
}

@Composable
internal fun TlCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ThrowbackTheme.colors.surface)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Icon + text line used for the rules on the setup screen and the steps on the reset screen. */
@Composable
internal fun IconLine(@DrawableRes icon: Int, text: String) {
    val c = ThrowbackTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TlIcon(icon, tint = c.accentText)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text)
    }
}

internal enum class TlButtonStyle { Primary, Secondary, Plain }

/** Full-width 52dp button (mockup `.btn`). Disabled buttons look like the mockup's waiting button. */
@Composable
internal fun TlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TlButtonStyle = TlButtonStyle.Primary,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val c = ThrowbackTheme.colors
    val (bg, fg) = when (style) {
        TlButtonStyle.Primary -> c.accent to c.onAccent
        TlButtonStyle.Secondary -> c.surface2 to c.text
        TlButtonStyle.Plain -> Color.Transparent to c.text
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = if (style == TlButtonStyle.Plain) Color.Transparent else c.surface2,
            disabledContentColor = c.muted,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            TlIcon(icon)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
        )
    }
}

/** A line that screen readers announce when it changes (errors, confirmations). */
@Composable
internal fun AnnouncedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = ThrowbackTheme.colors.text,
) {
    Text(
        text,
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

// ---- PIN entry ------------------------------------------------------------------------------

/** Four dots, filled for each digit typed. */
@Composable
internal fun PinDots(filled: Int, modifier: Modifier = Modifier, gap: Dp = 18.dp) {
    val c = ThrowbackTheme.colors
    val description = stringResource(R.string.tl_pin_progress_cd, filled)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(Rules.PIN_LENGTH) { i ->
            Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < filled) c.text else c.surface2))
        }
    }
}

/**
 * 3x4 number pad (mockup 9). Its own keys, so the PIN never passes through a keyboard app. Bottom row
 * ⌫ 0 ✓, the layout people know from Android's lock screen and payment apps (owner: no empty key).
 */
@Composable
internal fun PinPad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    canType: Boolean,
    canDelete: Boolean,
    canConfirm: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = ThrowbackTheme.colors
    val digitStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.Medium)
    val deleteLabel = stringResource(R.string.tl_key_delete)
    val confirmLabel = stringResource(R.string.tl_key_confirm)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("123", "456", "789", "<0>").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { key ->
                    when (key) {
                        '<' -> PadKey(deleteLabel, canDelete, onDelete, Modifier.weight(1f)) {
                            TlIcon(R.drawable.tl_ic_backspace, size = 26.dp, tint = if (canDelete) c.text else c.muted)
                        }
                        '>' -> PadKey(
                            confirmLabel, canConfirm, onConfirm, Modifier.weight(1f),
                            background = if (canConfirm) c.accent else c.surface,
                        ) {
                            TlIcon(R.drawable.tl_ic_check, size = 26.dp, tint = if (canConfirm) c.onAccent else c.muted)
                        }
                        else -> PadKey(key.toString(), canType, { onDigit(key) }, Modifier.weight(1f)) {
                            Text(key.toString(), style = digitStyle, color = c.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    background: androidx.compose.ui.graphics.Color = ThrowbackTheme.colors.surface,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(background)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick { onClick(); true } else disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Title, dots and number pad. Calls [onPin] when ✓ is pressed with four digits in; the digits stay on
 * screen until [clearKey] changes (the caller bumps it after a wrong PIN).
 */
@Composable
internal fun PinEntry(
    title: String,
    onPin: (String) -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    message: String? = null,
    enabled: Boolean = true,
    clearKey: Any = Unit,
    topPadding: Dp = 64.dp,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val c = ThrowbackTheme.colors
    var digits by remember(clearKey) { mutableStateOf("") }
    ScreenColumn(PaddingValues(start = 28.dp, end = 28.dp, top = topPadding, bottom = 28.dp), modifier) {
        if (eyebrow != null) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(10.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.02).em),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(36.dp))
        PinDots(digits.length)
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            if (message != null) AnnouncedText(message)
        }
        PinPad(
            onDigit = { d -> if (digits.length < Rules.PIN_LENGTH) digits += d },
            onDelete = { digits = digits.dropLast(1) },
            onConfirm = { if (digits.length == Rules.PIN_LENGTH) onPin(digits) },
            canType = enabled && digits.length < Rules.PIN_LENGTH,
            canDelete = enabled && digits.isNotEmpty(),
            canConfirm = enabled && digits.length == Rules.PIN_LENGTH,
        )
        Spacer(Modifier.weight(1f))
        footer()
    }
}

/** Create-then-confirm flow for a new PIN, with the "write it down" advice. */
@Composable
internal fun CreatePinFlow(
    headerTitle: String,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    firstTitle: String = stringResource(R.string.tl_create_pin_title),
    busy: Boolean = false,
    message: String? = null,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TlHeader(headerTitle, onBack)
        PinEntry(
            title = if (first == null) firstTitle else stringResource(R.string.tl_confirm_pin_title),
            subtitle = stringResource(R.string.tl_write_it_down),
            message = message ?: if (mismatch) stringResource(R.string.tl_pin_mismatch) else null,
            enabled = !busy,
            clearKey = round,
            topPadding = 16.dp,
            onPin = { pin ->
                val firstPin = first
                when {
                    firstPin == null -> {
                        first = pin
                        mismatch = false
                        round++
                    }
                    firstPin == pin -> onCreated(pin)
                    else -> {
                        first = null
                        mismatch = true
                        round++
                    }
                }
            },
        )
    }
}

/** Message for a failed PIN action, or null. [blockedMillis] comes from the live view so it counts down. */
@Composable
internal fun pinProblemText(result: ActionResult?, blockedMillis: Long): String? = when {
    blockedMillis > 0 -> stringResource(R.string.tl_pin_blocked, minSecText(blockedMillis))
    result is ActionResult.WrongPin ->
        pluralStringResource(R.plurals.tl_wrong_pin, result.attemptsLeft, result.attemptsLeft)
    result is ActionResult.PinResetPending -> stringResource(R.string.tl_pin_reset_pending)
    result is ActionResult.PinBlocked -> null // Block over.
    result is ActionResult.NotAllowed || result is ActionResult.PinRequired -> stringResource(R.string.tl_error_generic)
    else -> null
}
