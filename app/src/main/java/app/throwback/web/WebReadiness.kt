package app.throwback.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Instagram is on screen with Throwback's filters applied. Integration uses it to end the
 * mindful pause as soon as the feed is ready underneath (the pause is drawn over the Instagram
 * screen, which keeps loading). It must not hold the system splash on it: a WebView can't draw, and
 * so never gets ready, while the splash is up.
 *
 * [firstPageShown] starts false, is reset to false whenever InstagramScreen creates a WebView, and
 * turns true once that WebView's first page is filtered and drawn. It also turns true when the page
 * fails to load (the error screen is showing), when no WebView can be made, or after a safety
 * timeout, so a wait on it always ends. Nothing sets it while InstagramScreen is not composed (during
 * onboarding, for instance).
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
