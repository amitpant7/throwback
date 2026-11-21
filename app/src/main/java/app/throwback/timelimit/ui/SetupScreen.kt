package app.throwback.timelimit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.IconBullet
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbCard
import app.throwback.timelimit.DefaultTimeLimitController
import app.throwback.timelimit.engine.ActionResult
import kotlinx.coroutines.launch

// ---- Mockup 7: set up -----------------------------------------------------------------------

@Composable
internal fun SetupScreen(controller: DefaultTimeLimitController, onClose: () -> Unit, onLimitSet: () -> Unit = onClose) {
    val c = ThrowbackTheme.colors
    val scope = rememberCoroutineScope()
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    // The PIN is created only after "Turn on" (owner: an empty PIN box first was confusing), and it
    // lives only in memory, never in saved instance state.
    var creatingPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun turnOn(p: String) {
        scope.launch {
            busy = true
            val result = controller.setup(minutes, p)
            busy = false
            if (result == ActionResult.Done) onLimitSet() else failed = true
        }
    }

    BackHandler { if (creatingPin) creatingPin = false else onClose() }

    if (creatingPin) {
        CreatePinFlow(
            headerTitle = stringResource(R.string.tl_title),
            onBack = { creatingPin = false },
            onCreated = { p ->
                creatingPin = false
                turnOn(p)
            },
        )
        return
    }

    SettingsScaffold(
        stringResource(R.string.tl_title),
        onBack = onClose,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        fillViewport = true,
    ) {
        SectionLabel(stringResource(R.string.tl_setup_step_minutes), topPadding = 0.dp)
        LimitChoices(selected = minutes, onPick = { minutes = it })

        Spacer(Modifier.height(18.dp))
        TbCard(contentPadding = CardPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBullet(R.drawable.ic_lock, pluralStringResource(R.plurals.tl_rule_locks, minutes, minutes))
            IconBullet(R.drawable.ic_clock, stringResource(R.string.tl_rule_extend))
            IconBullet(R.drawable.ic_chat, stringResource(R.string.tl_rule_messages))
            IconBullet(R.drawable.ic_shield, stringResource(R.string.tl_setup_pin_next))
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        if (failed) {
            AnnouncedText(stringResource(R.string.tl_error_generic), Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        Text(
            stringResource(R.string.tl_speed_bump),
            style = MaterialTheme.typography.bodyLarge,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        )
        TbButton(
            stringResource(R.string.tl_turn_on, minutes),
            enabled = !busy,
            onClick = { creatingPin = true },
        )
    }
}
