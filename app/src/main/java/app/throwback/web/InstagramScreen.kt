package app.throwback.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.throwback.core.WebMode

/**
 * STUB owned by the web agent: replace the body, keep the signature.
 *
 * Shows instagram.com in a WebView with Throwback's filters applied.
 * @param mode FULL or MESSAGES_ONLY (only /direct/ pages allowed).
 * @param startUrl an instagram.com URL to open (deep link / notification), or null for the home feed.
 * @param onLocationChanged called with the current path (e.g. "/", "/direct/inbox/") after every navigation,
 *        including in-page (pushState) navigation. Integration shows its top-start controls only on the home feed.
 * @param onExitRequest back pressed with no web history left; integration decides (normally finishes the activity).
 */
@Composable
fun InstagramScreen(
    mode: WebMode,
    onLocationChanged: (path: String) -> Unit,
    onExitRequest: () -> Unit,
    modifier: Modifier = Modifier,
    startUrl: String? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Instagram WebView goes here ($mode)")
    }
}
