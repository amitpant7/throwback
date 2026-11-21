package app.throwback.ui.messages

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.DoneLabel
import app.throwback.core.ui.IconBullet
import app.throwback.core.ui.PillButton
import app.throwback.core.ui.StepBars
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbButtonStyle
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.TbTopBar
import app.throwback.core.ui.UiText
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.common.rememberCheckedOnResume

/**
 * The two steps of the Messages & calls setup (DECISIONS.md #28). The hide-icon step was dropped;
 * "Quiet the Instagram app" became an optional tip on the Messages & calls screen (owner, 2026-11-20).
 */
internal enum class WizardStep {
    Install, Permissions;

    val number: Int get() = ordinal + 1
    fun next(): WizardStep? = entries.getOrNull(ordinal + 1)
    fun previous(): WizardStep? = entries.getOrNull(ordinal - 1)

    companion object {
        val COUNT = entries.size
    }
}

/**
 * Messages & calls setup wizard. [onExit] leaves without finishing (back on step 1 or "Not now");
 * [onFinish] is called from the last step.
 */
@Composable
internal fun MessagesWizard(onExit: () -> Unit, onFinish: () -> Unit, showMessage: (String) -> Unit) {
    var step by rememberSaveable { mutableStateOf(WizardStep.Install) }
    val goBack = { step.previous()?.let { step = it } ?: onExit() }
    val goNext = { step.next()?.let { step = it } ?: onFinish() }
    BackHandler(enabled = step != WizardStep.Install) { goBack() }

    Column(Modifier.fillMaxSize().background(ThrowbackTheme.colors.bg)) {
        TbTopBar(stringResource(R.string.ui_messages_title), onBack = goBack)
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(240)) { w -> dir * w / 6 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(200)) { w -> -dir * w / 6 } + fadeOut(tween(160)))
            },
            label = "wizardStep",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                WizardStep.Install -> InstallStep(onNext = goNext, onNotNow = onExit, showMessage = showMessage)
                WizardStep.Permissions -> PermissionsStep(onNext = goNext, showMessage = showMessage)
            }
        }
    }
}

@Composable
private fun StepLayout(
    step: WizardStep,
    title: String,
    buttons: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ThrowbackTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            val label = if (step == WizardStep.Install) {
                stringResource(R.string.ui_wizard_step_optional, step.number, WizardStep.COUNT)
            } else {
                stringResource(R.string.ui_wizard_step, step.number, WizardStep.COUNT)
            }
            // Decorative: the "Step 1 of 2" text right below says the same thing to TalkBack.
            StepBars(current = step.number, total = WizardStep.COUNT)
            Text(label, style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
            Text(
                title,
                style = UiText.stepTitle,
                color = colors.text,
                modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
            )
            content()
            Spacer(Modifier.height(20.dp))
        }
        Column(Modifier.padding(top = 8.dp, bottom = 28.dp)) { buttons() }
    }
}

// ---------------------------------------------------------------------------------- step 1

@Composable
private fun InstallStep(onNext: () -> Unit, onNotNow: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val installed by rememberCheckedOnResume { InstagramApp.installedPackage(it) }
    val playStoreFailed = stringResource(R.string.ui_play_store_failed)

    StepLayout(
        step = WizardStep.Install,
        title = stringResource(R.string.ui_install_title),
        buttons = {
            TbButton(stringResource(R.string.ui_next), onClick = onNext)
            TbButton(
                stringResource(R.string.ui_not_now),
                onClick = onNotNow,
                modifier = Modifier.padding(top = 6.dp),
                style = TbButtonStyle.Quiet,
            )
        },
    ) {
        Text(stringResource(R.string.ui_install_body), style = UiText.lead, color = colors.muted)
        Spacer(Modifier.height(20.dp))
        TbCard(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBullet(R.drawable.ic_chat, stringResource(R.string.ui_install_point_messages))
            IconBullet(R.drawable.ic_phone, stringResource(R.string.ui_install_point_calls))
            IconBullet(R.drawable.ic_camera_app, stringResource(R.string.ui_install_point_post))
            IconBullet(R.drawable.ic_shield, stringResource(R.string.ui_install_point_guard))
        }
        Row(
            Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .border(1.dp, colors.line, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface2),
                contentAlignment = Alignment.Center,
            ) { TbIcon(R.drawable.ic_app_square, size = 20.dp) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_install_app_name), style = UiText.row15Strong, color = colors.text)
                Text(
                    stringResource(if (installed != null) R.string.ui_install_installed else R.string.ui_install_not_installed),
                    style = UiText.sub,
                    color = colors.muted,
                )
            }
            if (installed != null) {
                DoneLabel(stringResource(R.string.ui_granted))
            } else {
                PillButton(
                    stringResource(R.string.ui_install_button),
                    onClick = {
                        if (!SystemScreens.openPlayStore(context, InstagramApp.PACKAGE)) showMessage(playStoreFailed)
                    },
                    accessibilityLabel = stringResource(R.string.ui_install_button_description),
                )
            }
        }
        // Android shows Throwback only the main profile's apps (DECISIONS.md #34f).
        Text(
            stringResource(R.string.ui_install_clones),
            style = UiText.sub,
            color = colors.muted,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------------- step 2

@Composable
private fun PermissionsStep(onNext: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val state by rememberCheckedOnResume { MessagesSetupState.read(it) }
    val failed = stringResource(R.string.ui_settings_failed)
    val postDenied = stringResource(R.string.ui_perm_post_denied)
    // Android 13+: Throwback's own message notifications need the notification permission. Asked on
    // Finish, the moment Throwback starts posting them; finishing doesn't depend on the answer (the
    // relay keeps Instagram's own notifications while Throwback can't post).
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) showMessage(postDenied)
        onNext()
    }
    val finish = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.canPostNotifications(context)) {
            askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onNext()
        }
    }

    StepLayout(
        step = WizardStep.Permissions,
        title = stringResource(R.string.ui_perm_title),
        buttons = { TbButton(stringResource(R.string.ui_finish), onClick = finish) },
    ) {
        Text(stringResource(R.string.ui_perm_body), style = UiText.lead, color = colors.muted)
        Spacer(Modifier.height(16.dp))
        TbCard {
            PermissionRow(
                icon = R.drawable.ic_bell,
                title = R.string.ui_perm_notifications_title,
                body = R.string.ui_perm_notifications_body,
                granted = state.notificationAccess,
                onAllow = { if (!SystemScreens.openNotificationAccess(context)) showMessage(failed) },
            )
            CardDivider()
            PermissionRow(
                icon = R.drawable.ic_usage,
                title = R.string.ui_perm_usage_title,
                body = R.string.ui_perm_usage_body,
                granted = state.usageAccess,
                onAllow = { if (!SystemScreens.openUsageAccess(context)) showMessage(failed) },
            )
            CardDivider()
            PermissionRow(
                icon = R.drawable.ic_overlay,
                title = R.string.ui_perm_overlay_title,
                body = R.string.ui_perm_overlay_body,
                granted = state.drawOverlays,
                onAllow = { if (!SystemScreens.openOverlaySettings(context)) showMessage(failed) },
            )
        }
        // Android 13+ blocks notification access for apps installed from a downloaded file (not an
        // app store) until the user allows "restricted settings" in App info. Right under the list,
        // so it's seen; never for installs from Google Play, where it doesn't happen.
        val restrictedPossible = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !InstallSource.fromPlay(context) }
        if (restrictedPossible && !state.notificationAccess) {
            TbCard(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.ui_perm_restricted_title), style = UiText.body14, color = colors.text)
                Text(stringResource(R.string.ui_perm_restricted_hint), style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                TbButton(
                    stringResource(R.string.ui_perm_app_info),
                    onClick = { if (!SystemScreens.openOwnAppDetails(context)) showMessage(failed) },
                    modifier = Modifier.padding(top = 2.dp),
                    style = TbButtonStyle.Quiet,
                )
            }
        }
        Row(
            Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TbIcon(R.drawable.ic_shield, size = 18.dp, tint = colors.muted)
            Text(stringResource(R.string.ui_perm_privacy), style = UiText.sub, color = colors.muted)
        }
    }
}

@Composable
private fun PermissionRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    granted: Boolean,
    onAllow: () -> Unit,
) {
    val colors = ThrowbackTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TbIcon(icon, tint = colors.accentText)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = UiText.row15Strong, color = colors.text)
            Text(stringResource(body), style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (granted) {
            DoneLabel(stringResource(R.string.ui_granted), Modifier.padding(top = 2.dp))
        } else {
            // "Allow" alone is ambiguous with three rows, so TalkBack hears which permission.
            PillButton(
                stringResource(R.string.ui_allow),
                onClick = onAllow,
                accessibilityLabel = stringResource(R.string.ui_perm_allow_description, stringResource(title)),
            )
        }
    }
}
