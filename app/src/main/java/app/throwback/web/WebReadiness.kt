package app.throwback.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets integration keep the splash screen up until Instagram is on screen with Throwback's
 * filters already applied, so the user never sees unfiltered content or a blank WebView.
 *
 * [firstPageShown] starts false, is reset to false whenever InstagramScreen creates a WebView, and
 * turns true once that WebView's first page is filtered and drawn. It also turns true when the
 * page fails to load (the offline screen is showing) or after a safety timeout, so it can never hold
 * the splash forever. Nothing sets it while InstagramScreen is not composed, so only wait on it when
 * Instagram is the next thing on screen (not during onboarding or the mindful pause).
 */
object WebReadiness {
    private val shown = MutableStateFlow(false)

    val firstPageShown: StateFlow<Boolean> = shown.asStateFlow()

    internal fun reset() {
        shown.value = false
    }

    internal fun markShown() {
        shown.value = true
    }
}
