package app.throwback.ui

import android.content.Context
import androidx.compose.runtime.Composable
import app.throwback.core.UiPreferences
import app.throwback.ui.onboarding.InstagramAppPromptContent
import app.throwback.ui.onboarding.OnboardingFlowContent
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

/** "You still have the Instagram app": keep it for calls & messages, remove it, or decide later. Shown after login. */
@Composable
fun InstagramAppPrompt(onOpenMessagesSetup: () -> Unit, onDone: () -> Unit) {
    InstagramAppPromptContent(onOpenMessagesSetup = onOpenMessagesSetup, onDone = onDone)
}

enum class SettingsStart { Home, MessagesAndCalls }

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
) {
    SettingsRootContent(onClose = onClose, timeLimitScreen = timeLimitScreen, startAt = startAt)
}

/** Optional mindful pause before Instagram loads: a short line and a 3-second countdown. */
@Composable
fun MindfulPauseScreen(onContinue: () -> Unit, onNotNow: () -> Unit) {
    MindfulPauseContent(onContinue = onContinue, onNotNow = onNotNow)
}
