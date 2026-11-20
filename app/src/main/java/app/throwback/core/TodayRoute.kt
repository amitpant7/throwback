package app.throwback.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.throwback.relay.MessagesFeature
import app.throwback.relay.RelayStats
import app.throwback.ui.TodayScreen
import app.throwback.ui.today.TodayStats
import app.throwback.web.InstagramWebController
import app.throwback.web.PageDayStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** "Your day": joins the page's counts, the time limit and the relay's count; refreshed every 2 s. */
@Composable
internal fun TodayRoute(timeLimit: TimeLimitController, onClose: () -> Unit) {
    val context = LocalContext.current
    val lock by timeLimit.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf<PageDayStats?>(null) }
    var alerts by remember { mutableStateOf<Int?>(null) }
    var appDay by remember { mutableStateOf<TodayStats.InstagramAppDay?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            page = readPage() ?: page
            val connected = MessagesFeature.isSetUp(context)
            alerts = if (connected) RelayStats.otherGroupLastDays(context, days = 1) else null
            appDay = if (connected) app.throwback.relay.CallInApp.appDay(context).let {
                TodayStats.InstagramAppDay(it.storiesUsed, it.storiesMax, it.storyWaitMinutes, it.visitUsed, it.appMinutes)
            } else null
            delay(2_000)
        }
    }
    val p = page
    val stats = TodayStats(
        areas = p?.areas,
        limit = when (val l = lock) {
            LockState.Disabled -> TodayStats.Limit.None
            is LockState.Running -> TodayStats.Limit.Left(l.remainingMillis)
            LockState.Locked -> TodayStats.Limit.Reached
        },
        exploreLeftSec = p?.let { (it.exploreAllowanceSec - it.exploreUsedSec).coerceAtLeast(0) },
        exploreAllowanceSec = p?.exploreAllowanceSec ?: 0,
        reelCooldownMs = p?.reelCooldownMs,
        discoverShown = p?.discoverShown,
        discoverMax = p?.discoverMax ?: 0,
        adsRemoved = p?.adsRemoved,
        suggestionsRemoved = p?.suggestionsRemoved,
        alertsRemoved = alerts,
        instagramApp = appDay,
    )
    TodayScreen(stats, onBack = onClose)
}

private suspend fun readPage(): PageDayStats? {
    val controller = InstagramWebController.current?.get() ?: return null
    return suspendCancellableCoroutine { cont ->
        controller.dayStats { text -> if (cont.isActive) cont.resume(PageDayStats.parse(text)) }
    }
}
