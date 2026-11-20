package app.throwback.timelimit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.ScreenColumn
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.TbTopBar
import app.throwback.core.ui.minSecText
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.Rules

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
                            TbIcon(R.drawable.ic_backspace, size = 26.dp, tint = if (canDelete) c.text else c.muted)
                        }
                        '>' -> PadKey(
                            confirmLabel, canConfirm, onConfirm, Modifier.weight(1f),
                            background = if (canConfirm) c.accent else c.surface,
                        ) {
                            TbIcon(R.drawable.ic_check, size = 26.dp, tint = if (canConfirm) c.onAccent else c.muted)
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
        TbTopBar(headerTitle, onBack)
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
