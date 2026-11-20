package app.throwback.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.throwback.BuildConfig
import kotlin.math.roundToInt

/** WebView setup for Instagram. Everything here is applied once, when the WebView is created. */
internal object WebViewConfig {

    /** Origins that get the page script and the message bridge. */
    val INSTAGRAM_ORIGINS: Set<String> = setOf("https://www.instagram.com", "https://instagram.com")

    @SuppressLint("SetJavaScriptEnabled") // Instagram is a JavaScript app; the bridge is origin-restricted.
    fun apply(webView: WebView, fontScale: Float) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect for checking selectors

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.allowFileAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.setGeolocationEnabled(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false) // target=_blank / window.open load in this WebView
        // Videos autoplay muted in the feed, as in Chrome and the Instagram app.
        s.mediaPlaybackRequiresUserGesture = false
        // No pinch or double-tap zoom (the page script also pins the viewport).
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        // Chrome follows the system font size; a WebView only does when told.
        s.textZoom = (fontScale * 100).roundToInt().coerceIn(50, 300)
        s.userAgentString = cleanUserAgent(s.userAgentString)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, true)
        }
        // Instagram keeps its own colours (DECISIONS.md #32): never invert pages algorithmically.
        // prefers-color-scheme still follows the app theme (see refreshColorScheme).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }
        // No ad attribution reporting through the WebView (DECISIONS.md #5).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            WebSettingsCompat.setAttributionRegistrationBehavior(s, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED)
        }
        // Smoother scrolling: draw the page a little beyond the screen ahead of time (more memory, so
        // the WebView only keeps it while visible). Pairs with the page script's look-ahead.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(s, true)
        }
        // User-agent client hints name "Android WebView" as a brand; drop it to match the UA string.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val metadata = WebSettingsCompat.getUserAgentMetadata(s)
            val brands = metadata.brandVersionList.filterNot { it.brand.contains("WebView", ignoreCase = true) }
            if (brands.isNotEmpty() && brands.size != metadata.brandVersionList.size) {
                WebSettingsCompat.setUserAgentMetadata(s, UserAgentMetadata.Builder(metadata).setBrandVersionList(brands).build())
            }
        }

        // Stay logged in; third-party cookies are needed for "Continue with Facebook".
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
    }

    /**
     * Sites can tell a WebView from Chrome by its user agent and treat it as an "in-app browser"
     * (app install prompts, login restrictions). A WebView's user agent differs from Chrome's by
     * "; wv", "Version/4.0" and the build id; removing them leaves Chrome's format:
     * "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko)
     * Chrome/131.0.0.0 Mobile Safari/537.36".
     */
    fun cleanUserAgent(userAgent: String): String = userAgent
        .replace(Regex(""";\s*wv(?=\s*[;)])"""), "")
        .replace(Regex("""\s+Build/[^;)]*"""), "")
        .replace(Regex("""\s*Version/\d+(\.\d+)*"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    /**
     * With targetSdk 33+ the WebView sets prefers-color-scheme from the app theme's isLightTheme
     * (dark in values-night, light otherwise), and Instagram follows prefers-color-scheme. But
     * Chromium reads the theme only when it recomputes its web preferences, which a uiMode change
     * handled by the activity does not trigger. Changing any setting does, so this flips a font
     * setting Instagram never uses (cursive) and puts it back. The page then gets a
     * prefers-color-scheme change event without a reload.
     */
    fun refreshColorScheme(settings: WebSettings) {
        val original = settings.cursiveFontFamily ?: "cursive"
        settings.cursiveFontFamily = if (original == "serif") "sans-serif" else "serif"
        settings.cursiveFontFamily = original
    }
}
