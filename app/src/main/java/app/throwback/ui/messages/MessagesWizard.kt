package app.throwback.ui.messages

import android.Manifest
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.guard.GuardPass
import app.throwback.ui.common.CardDivider
import app.throwback.ui.common.DoneLabel
import app.throwback.ui.common.LineIcon
import app.throwback.ui.common.PillButton
import app.throwback.ui.common.PrimaryButton
import app.throwback.ui.common.QuietButton
import app.throwback.ui.common.SecondaryButton
import app.throwback.ui.common.SectionLabel
import app.throwback.ui.common.StepBars
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.common.TbCard
import app.throwback.ui.common.TopBar
import app.throwback.ui.common.UiText
import app.throwback.ui.common.rememberCheckedOnResume

/** The three steps of the Messages & calls setup (DECISIONS.md #28; the hide-icon step was dropped). */
internal enum class WizardStep {
    Install, Quiet, Permissions;

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
        TopBar(stringResource(R.string.ui_messages_title), onBack = goBack)
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
                WizardStep.Quiet -> QuietStep(onNext = goNext, showMessage = showMessage)
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
            // Decorative: the "Step n of 4" text right below says the same thing to TalkBack.
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
            PrimaryButton(stringResource(R.string.ui_next), onClick = onNext)
            QuietButton(stringResource(R.string.ui_not_now), onClick = onNotNow, modifier = Modifier.padding(top = 6.dp))
        },
    ) {
        Text(stringResource(R.string.ui_install_body), style = UiText.lead, color = colors.muted)
        Spacer(Modifier.height(20.dp))
        TbCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BulletPoint(R.drawable.ui_ic_chat, R.string.ui_install_point_messages)
                BulletPoint(R.drawable.ui_ic_phone, R.string.ui_install_point_calls)
                BulletPoint(R.drawable.ui_ic_shield, R.string.ui_install_point_guard)
            }
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
            ) { LineIcon(R.drawable.ui_ic_app_square, size = 20.dp) }
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
    }
}

@Composable
private fun BulletPoint(@DrawableRes icon: Int, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LineIcon(icon, size = 20.dp, tint = ThrowbackTheme.colors.accentText)
        Text(stringResource(text), style = UiText.body14, color = ThrowbackTheme.colors.text)
    }
}

// ---------------------------------------------------------------------------------- step 2

private class QuietRow(@StringRes val label: Int, val keepOn: Boolean)

private val QUIET_ROWS = listOf(
    QuietRow(R.string.ui_quiet_row_messages, keepOn = true),
    QuietRow(R.string.ui_quiet_row_calls, keepOn = true),
    QuietRow(R.string.ui_quiet_row_posts, keepOn = false),
    QuietRow(R.string.ui_quiet_row_following, keepOn = false),
    QuietRow(R.string.ui_quiet_row_live, keepOn = false),
    QuietRow(R.string.ui_quiet_row_fundraisers, keepOn = false),
    QuietRow(R.string.ui_quiet_row_from_instagram, keepOn = false),
)

@Composable
private fun QuietStep(onNext: () -> Unit, showMessage: (String) -> Unit) {
    val colors = ThrowbackTheme.colors
    val context = LocalContext.current
    val notInstalled = stringResource(R.string.ui_instagram_not_installed_message)
    val openFailed = stringResource(R.string.ui_instagram_open_failed)

    StepLayout(
        step = WizardStep.Quiet,
        title = stringResource(R.string.ui_quiet_title),
        buttons = {
            SecondaryButton(
                stringResource(R.string.ui_quiet_open_instagram),
                onClick = {
                    val pkg = InstagramApp.installedPackage(context)
                    // The guard (if already running) lets Throwback's own "open Instagram" through for a while.
                    if (pkg != null) GuardPass.grant(SystemClock.elapsedRealtime())
                    when {
                        pkg == null -> showMessage(notInstalled)
                        !SystemScreens.launchApp(context, pkg) -> showMessage(openFailed)
                    }
                },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            PrimaryButton(stringResource(R.string.ui_done_next), onClick = onNext)
        },
    ) {
        val path = stringResource(R.string.ui_quiet_path)
        val body = stringResource(R.string.ui_quiet_body, path)
        val start = body.indexOf(path)
        Text(
            buildAnnotatedString {
                append(body)
                if (start >= 0) {
                    addStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.SemiBold), start, start + path.length)
                }
            },
            style = UiText.lead,
            color = colors.muted,
        )
        Spacer(Modifier.height(16.dp))
        TbCard {
            QUIET_ROWS.forEachIndexed { index, row ->
                if (index > 0) CardDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(row.label), style = UiText.row15, color = colors.text, modifier = Modifier.weight(1f))
                    val tagBg = if (row.keepOn) colors.accent else colors.surface2
                    val tagFg = if (row.keepOn) colors.onAccent else colors.text
                    Text(
                        stringResource(if (row.keepOn) R.string.ui_quiet_keep_on else R.string.ui_quiet_turn_off),
                        style = UiText.fine.copy(fontWeight = FontWeight.SemiBold),
                        color = tagFg,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(tagBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.ui_quiet_footer),
            style = UiText.sub,
            color = colors.muted,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
        )
    }
}

// ---------------------------------------------------------------------------------- step 3

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
        buttons = { PrimaryButton(stringResource(R.string.ui_finish), onClick = finish) },
    ) {
        Text(stringResource(R.string.ui_perm_body), style = UiText.lead, color = colors.muted)
        Spacer(Modifier.height(16.dp))
        TbCard {
            PermissionRow(
                icon = R.drawable.ui_ic_bell,
                title = R.string.ui_perm_notifications_title,
                body = R.string.ui_perm_notifications_body,
                granted = state.notificationAccess,
                onAllow = { if (!SystemScreens.openNotificationAccess(context)) showMessage(failed) },
            )
            CardDivider()
            PermissionRow(
                icon = R.drawable.ui_ic_usage,
                title = R.string.ui_perm_usage_title,
                body = R.string.ui_perm_usage_body,
                granted = state.usageAccess,
                onAllow = { if (!SystemScreens.openUsageAccess(context)) showMessage(failed) },
            )
            CardDivider()
            PermissionRow(
                icon = R.drawable.ui_ic_overlay,
                title = R.string.ui_perm_overlay_title,
                body = R.string.ui_perm_overlay_body,
                granted = state.drawOverlays,
                onAllow = { if (!SystemScreens.openOverlaySettings(context)) showMessage(failed) },
            )
        }
        Row(
            Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LineIcon(R.drawable.ui_ic_shield, size = 18.dp, tint = colors.muted)
            Text(stringResource(R.string.ui_perm_privacy), style = UiText.sub, color = colors.muted)
        }
        // Android 13+ blocks notification access for apps installed outside an app store until the
        // user allows "restricted settings" in App info (APKs from GitHub Releases).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.notificationAccess) {
            Text(
                stringResource(R.string.ui_perm_restricted_hint),
                style = UiText.sub,
                color = colors.muted,
                modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
            )
            QuietButton(
                stringResource(R.string.ui_perm_app_info),
                onClick = { if (!SystemScreens.openOwnAppDetails(context)) showMessage(failed) },
                modifier = Modifier.padding(top = 2.dp),
            )
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
        LineIcon(icon, tint = colors.accentText)
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
