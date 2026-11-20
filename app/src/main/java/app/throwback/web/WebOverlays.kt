package app.throwback.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import kotlinx.coroutines.delay

/** Swallows touches so nothing underneath (the hidden WebView) reacts. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/** Covers the WebView until the first filtered page is drawn; matches the splash background. */
@Composable
internal fun LoadingCover(modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MS) // usually the splash screen is still up; don't flash a spinner under it
        showSpinner = true
    }
    val label = stringResource(R.string.web_loading)
    Box(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .blockTouches()
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = showSpinner, enter = fadeIn(), exit = fadeOut()) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = colors.accent,
                trackColor = colors.surface2,
                strokeWidth = 3.dp,
            )
        }
    }
}

/** Offline / load error screen for main-frame failures, in Throwback's own look. */
@Composable
internal fun WebErrorScreen(error: LoadError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .blockTouches()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.web_ic_offline),
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(if (error.offline) R.string.web_offline_title else R.string.web_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (error.offline) R.string.web_offline_body else R.string.web_error_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            enabled = !error.retrying,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.surface2,
                disabledContentColor = colors.muted,
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            if (error.retrying) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = colors.muted, strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.web_retry), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The pull-to-refresh indicator: follows the finger, spins while the page reloads.
 * State is passed as lambdas so a drag recomposes only this indicator.
 */
@Composable
internal fun PullIndicator(progressOf: () -> Float, refreshingOf: () -> Boolean, modifier: Modifier = Modifier) {
    val progress = progressOf()
    val refreshing = refreshingOf()
    if (progress <= 0f && !refreshing) return
    val colors = ThrowbackTheme.colors
    val travel = if (refreshing) 1f else progress.coerceAtMost(PullGesture.MAX_PROGRESS)
    val label = stringResource(R.string.web_refreshing)
    Surface(
        shape = CircleShape,
        color = colors.surface,
        shadowElevation = 6.dp,
        modifier = modifier
            .offset(y = (travel * PULL_TRAVEL_DP).dp - INDICATOR_SIZE_DP.dp)
            .size(INDICATOR_SIZE_DP.dp)
            .semantics { if (refreshing) contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.5.dp)
            } else {
                CircularProgressIndicator(
                    progress = { progress.coerceAtMost(1f) },
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = progress * 270f },
                    color = colors.accent.copy(alpha = if (progress >= 1f) 1f else 0.55f),
                    strokeWidth = 2.5.dp,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}

/** A short notice pill ("Reels are hidden in Throwback"), announced to screen readers. */
@Composable
internal fun WebNoticePill(toast: WebToast?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    // Remembers the last notice so its text stays while the pill fades out.
    var last by remember { mutableStateOf<WebToast?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            last = toast
            delay(NOTICE_MS)
            onDone()
        }
    }
    val colors = ThrowbackTheme.colors
    AnimatedVisibility(visible = toast != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val notice = (toast ?: last)?.notice ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(50),
            color = colors.surface2,
            contentColor = colors.text,
            shadowElevation = 2.dp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = stringResource(notice.text()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

private fun WebNotice.text(): Int = when (this) {
    WebNotice.REELS -> R.string.web_notice_reels
    WebNotice.SUGGESTED -> R.string.web_notice_suggested
    WebNotice.MESSAGES_ONLY -> R.string.web_notice_messages_only
    WebNotice.HIDDEN -> R.string.web_notice_hidden
    WebNotice.NO_APP -> R.string.web_notice_no_app
}

private const val SPINNER_DELAY_MS = 700L
private const val NOTICE_MS = 2_500L
private const val PULL_TRAVEL_DP = 72
private const val INDICATOR_SIZE_DP = 40
