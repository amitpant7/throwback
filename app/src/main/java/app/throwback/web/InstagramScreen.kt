package app.throwback.web

import android.app.Activity
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.core.StartTrace
import app.throwback.core.WebMode
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.web.rules.FilterRules
import app.throwback.web.rules.RulesRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Shows instagram.com in a WebView with Throwback's filters applied.
 *
 * One long-lived WebView: it survives recomposition and the configuration changes the activity
 * handles itself (rotation, dark mode), and is only replaced if its renderer process dies.
 * The WebView stays hidden behind a cover until the first filtered page is drawn (see [WebReadiness]).
 *
 * @param mode FULL or MESSAGES_ONLY (only /direct/ pages allowed). Switching does not reload the page
 *        when it can be avoided.
 * @param startUrl an instagram.com URL to open (deep link / notification), or null for the home feed.
 *        Each new value is opened once.
 * @param onLocationChanged called with the current path (e.g. "/", "/direct/inbox/") after every navigation,
 *        including in-page (pushState) navigation. Integration shows the time-left chip only on the home feed.
 *        Outside instagram.com (Facebook login) the full URL is passed instead, so it never looks like "/".
 * @param onExitRequest back pressed with no web history left; integration decides (normally finishes the activity).
 * @param onOpenSettings the page asked for Throwback's settings: the icon on the user's own profile,
 *        Throwback's section on Instagram's settings page, or a long-press on Home.
 */
@Composable
fun InstagramScreen(
    mode: WebMode,
    onLocationChanged: (path: String) -> Unit,
    onExitRequest: () -> Unit,
    modifier: Modifier = Modifier,
    startUrl: String? = null,
    onOpenSettings: () -> Unit = {},
    /** The chat's call button was tapped (chat id and the other person's username; either may be null). */
    onCallInApp: (String?, String?) -> Unit = { _, _ -> },
    /** False while a Throwback screen covers the page (Settings, the pause, the reel poster): back must not reach the page. */
    backEnabled: Boolean = true,
    /** True while Throwback's own screens (Settings, the lock, the pause, the reel poster) cover the page. */
    covered: Boolean = false,
    /** The reel allowance in Instagram's swipe viewer is used up: show the poster. */
    onReelLimit: () -> Unit = {},
    /** The feed reached "You're all caught up". */
    onCaughtUp: () -> Unit = {},
    /** "+" / "Your story" with the Instagram app connected. */
    onCreateInApp: (story: Boolean) -> Unit = {},
    /** "Open settings in the Instagram app". */
    onSettingsInApp: () -> Unit = {},
    /** "Your story" went to the website's upload (the app route is waiting). */
    onStoryWeb: () -> Unit = {},
    /** The support line was shown / "Say thanks" was tapped. */
    onSupportAskShown: () -> Unit = {},
    onSupportThanks: () -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { RulesRepository.get(context) }
    val rules by repository.rules.collectAsStateWithLifecycle()
    // Usually already in memory (warmed up at app start): then the WebView is created and starts
    // loading in this first composition instead of a frame later (~300 ms sooner on a cold start).
    var assets by remember { mutableStateOf(PageAssets.loadedOrNull()) }
    LaunchedEffect(Unit) {
        StartTrace.mark("screen")
        // Both read files from disk; in parallel, the first page starts sooner on a cold start.
        coroutineScope {
            launch { assets = PageAssets.load(context).also { StartTrace.mark("assets") } }
            launch { repository.load().also { StartTrace.mark("rules") } }
        }
    }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        scope.launch { repository.refreshIfDue() }
    }

    // If the activity is recreated (e.g. after a font size change), reopen the page the user was on.
    var lastUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var handledStartUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped when the WebView's renderer dies, which replaces the WebView.
    var generation by remember { mutableIntStateOf(0) }
    val crashes = remember { RendererCrashes(SystemClock::elapsedRealtime) }
    var crashLoop by remember { mutableStateOf(false) }

    val loadedRules = rules
    val loadedAssets = assets
    if (loadedRules == null || loadedAssets == null) {
        LoadingCover(modifier)
        return
    }
    if (crashLoop) {
        // The renderer crashed three times in a row, soon after each other: stop and say so.
        LaunchedEffect(Unit) { WebReadiness.markShown() }
        WebErrorScreen(
            LoadError(offline = false, url = null),
            onRetry = {
                crashes.reset()
                crashLoop = false
                generation++
            },
            modifier = modifier,
        )
        return
    }
    // What the page asks of the app, to this composition's callbacks: one object for the controller.
    val host = InstagramWebController.Host(
        onLocationChanged = { location, url ->
            onLocationChanged(location)
            if (location.startsWith("/")) lastUrl = url // Instagram pages only
        },
        onStartUrlHandled = { handledStartUrl = it },
        onOpenSettings = onOpenSettings,
        onCallInApp = onCallInApp,
        onReelLimit = onReelLimit,
        onCaughtUp = onCaughtUp,
        onCreateInApp = onCreateInApp,
        onSettingsInApp = onSettingsInApp,
        onStoryWeb = onStoryWeb,
        onSupportAskShown = onSupportAskShown,
        onSupportThanks = onSupportThanks,
    )
    key(generation) {
        InstagramWebHost(
            rules = loadedRules,
            assets = loadedAssets,
            mode = mode,
            startUrl = startUrl,
            restoreUrl = lastUrl,
            handledStartUrl = handledStartUrl,
            host = host,
            onExitRequest = onExitRequest,
            onRecreate = { generation++ },
            onRendererGone = { crashed ->
                when (crashes.onGone(crashed)) {
                    RendererCrashes.Next.RESTORE -> generation++
                    RendererCrashes.Next.HOME -> {
                        lastUrl = null // the page it was on may be what crashes it
                        generation++
                    }
                    RendererCrashes.Next.GIVE_UP -> crashLoop = true
                }
            },
            backEnabled = backEnabled,
            covered = covered,
            modifier = modifier,
        )
    }
}

@Composable
private fun InstagramWebHost(
    rules: FilterRules,
    assets: PageAssets,
    mode: WebMode,
    startUrl: String?,
    restoreUrl: String?,
    handledStartUrl: String?,
    host: InstagramWebController.Host,
    onExitRequest: () -> Unit,
    onRecreate: () -> Unit,
    onRendererGone: (crashed: Boolean) -> Unit,
    backEnabled: Boolean,
    covered: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val colors = ThrowbackTheme.colors
    // The app's features for the page; followed only while the activity is started.
    val features by PageFeatures.current.collectAsStateWithLifecycle()
    val controller = remember {
        WebReadiness.reset()
        try {
            StartTrace.mark("webview-create")
            InstagramWebController(context, assets, mode, handledStartUrl, features).also {
                StartTrace.mark("webview-ready")
                // Cold start without a deep link: start loading now rather than after the first frame
                // (the LaunchedEffect below then finds it started). A deep link waits for the host
                // (set after this composition), which records it as handled.
                if (startUrl == null) {
                    it.applyRules(rules)
                    it.openOrStart(null, restoreUrl)
                }
            }
        } catch (e: RuntimeException) {
            // No usable WebView (it is being updated, or disabled on this device).
            Log.e("ThrowbackWeb", "Could not create the WebView", e)
            null
        }
    }
    if (controller == null) {
        LaunchedEffect(Unit) { WebReadiness.markShown() }
        WebErrorScreen(LoadError(offline = false, url = null), onRetry = onRecreate, modifier = modifier)
        return
    }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    SideEffect { controller.host = host } // the latest callbacks, after every composition

    // Photos and videos go through Android's photo picker; anything else through the file chooser.
    val pickOne = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        controller.deliverFiles(uri?.let { arrayOf(it) })
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        controller.deliverFiles(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.deliverFiles(FileChooserKinds.urisFrom(result.resultCode == Activity.RESULT_OK, result.data))
    }

    DisposableEffect(controller) {
        controller.pickFiles = { kind, multiple, documentIntent ->
            val media = when (kind) {
                PickKind.IMAGES -> PickVisualMedia.ImageOnly
                PickKind.VIDEOS -> PickVisualMedia.VideoOnly
                PickKind.IMAGES_AND_VIDEOS -> PickVisualMedia.ImageAndVideo
                PickKind.OTHER -> null
            }
            when {
                media != null && multiple -> pickMany.launch(PickVisualMediaRequest(media))
                media != null -> pickOne.launch(PickVisualMediaRequest(media))
                documentIntent != null -> pickDocument.launch(documentIntent)
            }
            media != null || documentIntent != null
        }
        onDispose { controller.pickFiles = null }
    }

    LaunchedEffect(controller, rules, startUrl) {
        controller.applyRules(rules)
        controller.openOrStart(startUrl, restoreUrl)
    }
    LaunchedEffect(controller, mode) {
        controller.setMode(mode)
    }
    LaunchedEffect(controller, covered) {
        controller.setCovered(covered)
    }
    LaunchedEffect(controller, features) {
        controller.setFeatures(features)
    }
    val night = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK
    LaunchedEffect(controller, night) {
        controller.onNightModeChanged(night)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { controller.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.onStop() }

    // Disabled under covering screens: handlers registered later win, and this one is registered
    // again whenever the page loads after Settings opened, which then closed the app on back.
    BackHandler(enabled = backEnabled) {
        if (!controller.goBack()) onExitRequest()
    }

    // The renderer went away and the controller destroyed its WebView: InstagramScreen replaces it.
    controller.rendererGone?.let { gone ->
        LaunchedEffect(gone) { onRendererGone(gone.crashed) }
    }

    // The system bar areas take the page's own background, so the app looks edge to edge.
    val pageColor = controller.pageBackground?.let { Color(it) } ?: colors.bg
    Box(modifier.fillMaxSize().background(pageColor)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).clipToBounds()) {
            AndroidView(
                factory = {
                    // The same WebView instance is reused if this view is ever recreated.
                    controller.webView.also {
                        (it.parent as? ViewGroup)?.removeView(it)
                        // Without explicit MATCH_PARENT the holder gives the WebView WRAP_CONTENT, and a
                        // wrap-content WebView reports a 0px-tall viewport to the page: percentage-height
                        // layouts (Instagram's login page) then collapse to nothing and render blank.
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.setBackgroundColor(colors.bg.toArgb()) },
            )
            PullIndicator(
                progressOf = { controller.pullProgress },
                refreshingOf = { controller.refreshing },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (!controller.contentShown) LoadingCover()

        controller.error?.let { error ->
            WebErrorScreen(error, onRetry = controller::retry)
            if (error.offline) RetryWhenOnline(controller)
        }

        WebNoticePill(
            toast = controller.toast,
            onDone = { controller.toast = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = NOTICE_BOTTOM_OFFSET_DP.dp), // above Instagram's bottom bar
        )
    }
}

/** Retries automatically when the connection comes back after being lost. */
@Composable
private fun RetryWhenOnline(controller: InstagramWebController) {
    val online by rememberIsOnline()
    var sawOffline by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) {
            sawOffline = true
        } else if (sawOffline) {
            sawOffline = false
            controller.retryIfOffline()
        }
    }
}

@Composable
private fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    return produceState(initialValue = InstagramWebController.isOnline(context)) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@produceState
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                value = true
            }

            override fun onLost(network: Network) {
                value = false
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }
}

private const val NOTICE_BOTTOM_OFFSET_DP = 72
