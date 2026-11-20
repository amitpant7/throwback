package app.throwback.web

import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user is logged in to Instagram inside Throwback.
 *
 * Instagram keeps the login in its `sessionid` cookie. It is HttpOnly, so page scripts can't see it,
 * but Android's CookieManager can. [refresh] is called on every page change, so [loggedIn]
 * follows logins and logouts.
 */
object InstagramSession {
    const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
    private const val SESSION_COOKIE = "sessionid"

    private val state = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = state.asStateFlow()

    /** Re-reads the cookie; call on the main thread. Returns the new value. */
    fun refresh(): Boolean {
        val now = hasSessionCookie()
        state.value = now
        return now
    }

    private fun hasSessionCookie(): Boolean = try {
        CookieManager.getInstance().getCookie("https://www.instagram.com")
            ?.split(';')
            ?.any { part ->
                val (name, value) = part.trim().split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                name == SESSION_COOKIE && value.isNotBlank() && value != "\"\""
            } == true
    } catch (e: RuntimeException) {
        // No WebView available (being updated or disabled): treat as logged out.
        false
    }
}
