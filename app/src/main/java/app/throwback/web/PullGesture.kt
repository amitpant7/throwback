package app.throwback.web

import kotlin.math.abs

/**
 * Pull-to-refresh gesture tracking for the WebView.
 *
 * Compose's PullToRefreshBox can't be used: it needs nested scrolling, which WebView doesn't
 * report. Instead the WebView's touch events are observed (never consumed) and a pull only counts
 * when all of these agree:
 *  - the app allows pull-to-refresh on this page (rules: paths.pullToRefresh),
 *  - the WebView's document is scrolled to the top (WebView.scrollY == 0) when the finger goes down,
 *  - the page script reports that the touched element is not inside an inner scroller that is
 *    scrolled down, or inside a dialog/sheet (a downward drag there means "close" or "scroll").
 *    Until that report arrives the pull stays disarmed, so missing JS fails safe (no refresh).
 * The drag must be mostly vertical and downward; any upward movement or page scroll cancels it.
 *
 * Distances are in pixels. [progress] is 0 at rest and reaches 1 at the refresh threshold.
 */
internal class PullGesture(
    private val thresholdPx: Float,
    private val touchSlopPx: Float,
    /** Drag distance is divided by this, so the indicator moves slower than the finger. */
    private val resistance: Float = 2f,
) {
    var progress: Float = 0f
        private set

    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var scriptSaysTop: Boolean? = null
    private var pulling = false

    /** Finger down. */
    fun down(x: Float, y: Float, documentAtTop: Boolean, pageAllowsPull: Boolean) {
        startX = x
        startY = y
        tracking = documentAtTop && pageAllowsPull
        scriptSaysTop = null
        pulling = false
        progress = 0f
    }

    /** The page script's report for the current touch. */
    fun scriptReport(atTop: Boolean) {
        if (scriptSaysTop == null) scriptSaysTop = atTop
        if (!atTop) cancel()
    }

    /** Finger moved; returns the new [progress]. */
    fun move(x: Float, y: Float, documentAtTop: Boolean): Float {
        if (!tracking) return progress
        val dy = y - startY
        val dx = x - startX
        if (!documentAtTop || dy < -touchSlopPx) {
            cancel()
            return progress
        }
        if (!pulling) {
            // Wait for the script to confirm, then for a clear, mostly vertical pull.
            if (scriptSaysTop != true) return progress
            if (dy > touchSlopPx && abs(dy) > abs(dx) * 1.5f) {
                pulling = true
                startY += touchSlopPx // start counting from the slop so the indicator doesn't jump
            } else {
                if (abs(dx) > touchSlopPx && abs(dx) > abs(dy)) cancel() // horizontal swipe (stories tray)
                return progress
            }
        }
        val distance = (y - startY).coerceAtLeast(0f) / resistance
        progress = (distance / thresholdPx).coerceAtMost(MAX_PROGRESS)
        return progress
    }

    /** Finger up: true if the pull went far enough to refresh. Resets either way. */
    fun up(): Boolean {
        val refresh = tracking && pulling && progress >= 1f
        cancel()
        return refresh
    }

    /** A second finger, a cancel event, or the page scrolled. */
    fun cancel() {
        tracking = false
        pulling = false
        progress = 0f
    }

    companion object {
        const val MAX_PROGRESS = 1.5f
    }
}
