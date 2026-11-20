package app.throwback.timelimit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbButtonStyle
import app.throwback.core.ui.IconBullet
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.UiText
import app.throwback.timelimit.DefaultTimeLimitController
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.TimeLimitView
import kotlinx.coroutines.launch

/**
 * "Forgot PIN?" (mockup 12): start the 3-day reset, watch it count down, cancel it, and once it's
 * over set a new PIN. Used from the lock screen and from settings.
 */
@Composable
internal fun ForgotPinScreen(
    controller: DefaultTimeLimitController,
    view: TimeLimitView,
    onBack: () -> Unit,
    onNewPinSaved: () -> Unit = onBack,
) {
    val c = ThrowbackTheme.colors
    val scope = rememberCoroutineScope()
    val reset = view.pinReset
    var settingNewPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    BackHandler { if (settingNewPin) settingNewPin = false else onBack() }

    if (settingNewPin && reset?.ready == true) {
        CreatePinFlow(
            headerTitle = stringResource(R.string.tl_reset_title),
            firstTitle = stringResource(R.string.tl_new_pin_title),
            onBack = { settingNewPin = false },
            busy = busy,
            message = if (failed) stringResource(R.string.tl_error_generic) else null,
            onCreated = { pin ->
                scope.launch {
                    busy = true
                    val result = controller.completePinReset(pin)
                    busy = false
                    if (result == ActionResult.Done) {
                        settingNewPin = false
                        onNewPinSaved()
                    } else {
                        failed = true
                    }
                }
            },
        )
        return
    }

    SettingsScaffold(
        stringResource(R.string.tl_reset_title),
        onBack = onBack,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        fillViewport = true,
    ) {
        TbCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp)) {
            val label: String
            val big: String
            val sub: String
            when {
                reset == null -> {
                    label = stringResource(R.string.tl_reset_label_start)
                    big = stringResource(R.string.tl_reset_big_start)
                    sub = stringResource(R.string.tl_reset_until)
                }
                reset.ready -> {
                    label = stringResource(R.string.tl_reset_label_ready)
                    big = stringResource(R.string.tl_reset_ready_big)
                    sub = stringResource(R.string.tl_reset_ready_sub)
                }
                else -> {
                    label = stringResource(R.string.tl_reset_label_requested)
                    big = longDurationText(reset.remainingMillis)
                    sub = stringResource(R.string.tl_reset_until)
                }
            }
            Column(Modifier.clearAndSetSemantics { contentDescription = "$label. $big $sub" }) {
                Eyebrow(label)
                Spacer(Modifier.height(8.dp))
                Text(big, style = UiText.countdown(38), color = c.text)
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            if (reset != null && !reset.ready) {
                val done = 1f - reset.remainingMillis.toFloat() / reset.totalMillis
                val percent = (done * 100).toInt()
                val progressLabel = pluralStringResource(R.plurals.tl_reset_progress_cd, percent, percent)
                Box(
                    Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.surface2)
                        .clearAndSetSemantics { contentDescription = progressLabel },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(done.coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(c.accent),
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBullet(R.drawable.ic_shield, stringResource(R.string.tl_reset_step_wait))
            IconBullet(R.drawable.ic_clock, stringResource(R.string.tl_reset_step_limit))
            IconBullet(
                R.drawable.ic_check,
                stringResource(if (reset == null) R.string.tl_reset_step_cancel_start else R.string.tl_reset_step_cancel),
            )
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                reset == null -> TbButton(stringResource(R.string.tl_reset_start), onClick = { controller.requestPinReset() })
                reset.ready -> {
                    TbButton(stringResource(R.string.tl_reset_set_new), onClick = {
                        failed = false
                        settingNewPin = true
                    })
                    TbButton(stringResource(R.string.tl_reset_cancel), onClick = controller::cancelPinReset, style = TbButtonStyle.Secondary)
                }
                else -> TbButton(stringResource(R.string.tl_reset_cancel), onClick = controller::cancelPinReset, style = TbButtonStyle.Secondary)
            }
        }
    }
}
