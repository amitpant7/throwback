package app.throwback.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.throwback.core.UiPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * STUBS owned by the ui agent: replace the bodies, keep the signatures.
 */

object UiModule {
    /** Called once by ThrowbackApp. */
    fun create(context: Context): UiPreferences = object : UiPreferences {
        private val done = MutableStateFlow(false)
        override val onboardingDone: Flow<Boolean> = done
        override val mindfulPause: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setOnboardingDone() { done.value = true }
    }
}

/** First launch, then (if the Instagram app is installed) the "Instagram app found" choice. */
@Composable
fun OnboardingFlow(onFinished: () -> Unit, onOpenMessagesSetup: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onFinished) { Text("Log in to Instagram") }
    }
}

enum class SettingsStart { Home, MessagesAndCalls }

/**
 * Settings and everything reachable from it: Messages & calls wizard and settings, home-screen shortcut,
 * About & support. The time-limit screens come from the timelimit package via [timeLimitScreen].
 */
@Composable
fun SettingsRoot(
    onClose: () -> Unit,
    timeLimitScreen: @Composable (onClose: () -> Unit) -> Unit,
    startAt: SettingsStart = SettingsStart.Home,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onClose) { Text("Settings (stub) – close") }
    }
}

/** Optional mindful pause before Instagram loads. */
@Composable
fun MindfulPauseScreen(onContinue: () -> Unit, onNotNow: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onContinue) { Text("Continue") }
    }
}
