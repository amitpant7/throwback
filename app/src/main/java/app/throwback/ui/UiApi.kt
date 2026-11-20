package app.throwback.ui

import android.content.Context
import androidx.compose.runtime.Composable
import app.throwback.core.UiPreferences
import app.throwback.ui.onboarding.InstagramAppPromptContent
import app.throwback.ui.onboarding.OnboardingFlowContent
import app.throwback.ui.onboarding.SettingsHintContent
import androidx.compose.ui.Modifier
import app.throwback.ui.pause.MindfulPauseContent
import app.throwback.ui.settings.SettingsRootContent

/*
 * Public entry points of the ui area, used by core/AppRoot.kt. Signatures are part of the
 * contract in docs/ARCHITECTURE.md; the implementations live in the sub-packages.
 */

object UiModule {
    /** Called once by ThrowbackApp. DataStore-backed (file `ui_prefs`). */
    fun create(context: Context): UiPreferences = UiPrefs.from(context)
}

/** First launch. "Log in to Instagram" calls [onFinished]; the login happens on Instagram's page. */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    OnboardingFlowContent(onFinished = onFinished)
}

/** Whether to offer the "Instagram app found" choice (app installed, choice not seen yet). */
suspend fun shouldOfferInstagramPrompt(context: Context): Boolean =
    app.throwback.ui.onboarding.shouldOfferInstagramPrompt(context)

/*
 * The layers drawn over Instagram (prompt, poster, pause) take system Back while they show.
 * backEnabled = false leaves it to the screen on top: Android gives Back to the handler added last,
 * so a layer that appears while Settings is open would otherwise take Back from Settings.
 */

/** "You still have the Instagram app": keep it for calls & messages, remove it, or decide later. Shown after login. */
@Composable
fun InstagramAppPrompt(onOpenMessagesSetup: () -> Unit, onDone: () -> Unit, backEnabled: Boolean = true) {
    InstagramAppPromptContent(onOpenMessagesSetup = onOpenMessagesSetup, onDone = onDone, backEnabled = backEnabled)
}

enum class SettingsStart { Home, MessagesAndCalls, Support }

/**
 * Settings and everything reachable from it: Messages & calls wizard and settings, home-screen shortcut,
 * About & support. The time-limit screens come from the timelimit package via [timeLimitScreen].
 * System back pops one level; at the root it calls [onClose].
 */
@Composable
fun SettingsRoot(
    onClose: () -> Unit,
    timeLimitScreen: @Composable (onClose: () -> Unit) -> Unit,
    startAt: SettingsStart = SettingsStart.Home,
    todayScreen: @Composable (onClose: () -> Unit) -> Unit = {},
    messagesOff: app.throwback.ui.messages.MessagesOffHooks = app.throwback.ui.messages.MessagesOffHooks(),
) {
    SettingsRootContent(
        onClose = onClose, timeLimitScreen = timeLimitScreen, startAt = startAt,
        todayScreen = todayScreen, messagesOff = messagesOff,
    )
}

/** "Your day": time spent, allowances left, what Throwback removed today. */
@Composable
fun TodayScreen(stats: app.throwback.ui.today.TodayStats, onBack: () -> Unit) {
    app.throwback.ui.today.TodayScreen(stats, onBack)
}

/**
 * Optional mindful pause before Instagram loads: a short line and a 4-second countdown, no buttons.
 * [onContinue] when the time is up; [onBack] is system Back (it leaves the app).
 */
@Composable
fun MindfulPauseScreen(onContinue: () -> Unit, onBack: () -> Unit, backEnabled: Boolean = true) {
    MindfulPauseContent(onContinue = onContinue, onBack = onBack, backEnabled = backEnabled)
}

/** Whether the one-time "long-press Home for Throwback's settings" hint is still to be shown. */
suspend fun shouldShowSettingsHint(context: Context): Boolean =
    app.throwback.ui.onboarding.shouldShowSettingsHint(context)

/** Marks the settings hint as done (also when the user opened settings on their own). */
suspend fun markSettingsHintShown(context: Context) =
    app.throwback.ui.onboarding.markSettingsHintShown(context)

/** The one-time hint card, pointing at Instagram's Home tab; "Got it" marks it shown and calls [onDone]. */
@Composable
fun SettingsHint(onDone: () -> Unit, modifier: Modifier = Modifier) {
    SettingsHintContent(onDone = onDone, modifier = modifier)
}

/** Full-screen poster after the reel allowance in Instagram's swipe viewer; [onBack] returns to the chat. */
@Composable
fun ReelPoster(onBack: () -> Unit, backEnabled: Boolean = true) {
    app.throwback.ui.reels.ReelPosterContent(onBack = onBack, backEnabled = backEnabled)
}
