package app.throwback.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.throwback.core.WebMode
import app.throwback.web.rules.FilterRules
import kotlinx.serialization.json.JsonObject

/**
 * Keeps the page's configuration (rules, mode, the app's features and the trusted clock) up to date,
 * for the documents loaded from now on (the injected script) and for the page already open (live).
 * Part of [InstagramWebController]; main thread only.
 *
 * @param pageOpen whether the WebView has started loading, so there may be a page to update live.
 */
internal class PageConfigSync(
    private val context: Context,
    private val assets: PageAssets,
    private val injector: ScriptInjector,
    initialMode: WebMode,
    initialFeatures: PageFeatures,
    private val pageOpen: () -> Boolean,
) {
    var mode: WebMode = initialMode
        private set
    var rules: FilterRules? = null
        private set
    private var features: PageFeatures = initialFeatures

    /** Between [onStart] and [onStop]. */
    var inFront = false
        private set
    private var wasStopped = false

    /** Whether the installed script carries a trusted clock (none while the time limit was still loading). */
    private var scriptHasClock = false
    private var released = false
    private val handler = Handler(Looper.getMainLooper())

    /** The phone's clock, date or time zone changed: the page's day must follow the trusted clock. */
    private val timeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshClock()
    }

    init {
        val timeActions = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context.applicationContext, timeChanges, timeActions, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** New or updated rules: future pages get them at document start, the open page live. */
    fun setRules(newRules: FilterRules) {
        val hadRules = rules != null
        rules = newRules
        installScript()
        if (hadRules && pageOpen()) injector.configureLive(PageScript.config(rules = newRules)) {}
    }

    /** Switches FULL / MESSAGES_ONLY without a reload; [onNotHandled] when the open page has no script to do it. */
    fun setMode(newMode: WebMode, onNotHandled: () -> Unit) {
        if (newMode == mode) return
        mode = newMode
        if (rules == null) return
        installScript()
        if (!pageOpen()) return
        injector.configureLive(PageScript.config(mode = newMode)) { handled ->
            if (!handled) onNotHandled()
        }
    }

    /** The app's features changed (e.g. the Instagram app got connected): tell the page. */
    fun setFeatures(value: PageFeatures) {
        if (value == features) return
        features = value
        // Pages loaded from now on (the script was built before the app knew), and the page on screen.
        installScript()
        if (pageOpen()) injector.configureLive(PageScript.config(features = value)) {}
    }

    /**
     * The page is ready: fresh trusted time for its daily counters (the phone's clock may have moved),
     * and, once it is known, for the documents after this one too.
     */
    fun onPageReady() {
        PageClock.json()?.let { clock ->
            if (!scriptHasClock) installScript()
            injector.configureLive(PageScript.config(clock = clock)) {}
        }
    }

    /**
     * In front. Back from the background, the page also gets a fresh trusted clock (time passed
     * meanwhile); the first start needs none, the script was just built with one.
     */
    fun onStart() {
        inFront = true
        if (wasStopped) refreshClock()
        handler.removeCallbacks(clockTick)
        handler.postDelayed(clockTick, if (scriptHasClock) CLOCK_REFRESH_MS else CLOCK_RETRY_MS)
    }

    fun onStop() {
        inFront = false
        wasStopped = true
        handler.removeCallbacks(clockTick)
    }

    /** Stops the clock and the time broadcasts; the controller is being destroyed. */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        context.applicationContext.unregisterReceiver(timeChanges)
    }

    /**
     * The script for documents loaded from now on: rules, mode and features as they are now, and the
     * trusted clock read now, which it returns (null while the time limit is still loading). Rebuilt
     * only when one of them changes, and for a fresh clock at [onStart] and when the phone's time
     * changes: never per navigation (it is ~180 KB).
     */
    private fun installScript(): JsonObject? {
        val rules = rules ?: return null
        val clock = PageClock.json()
        injector.install(PageScript.build(assets.template, assets.css, rules, mode, clock, features))
        scriptHasClock = clock != null
        return clock
    }

    /**
     * A fresh trusted clock for the open page and for documents loaded from now on. Not in the
     * background: rebuilding the script is not free, and [onStart] brings a fresh clock anyway.
     */
    private fun refreshClock() {
        if (released || !inFront) return
        val clock = installScript() ?: return
        if (pageOpen()) injector.configureLive(PageScript.config(clock = clock)) {}
    }

    /**
     * While in front, the page gets a fresh clock now and then: the trusted time can move without any
     * system broadcast (network time arriving after a reboot), and the time limit may finish loading
     * after the page was ready (then every 2 s until it has, and the script is updated once).
     */
    private val clockTick = object : Runnable {
        override fun run() {
            if (released) return
            val clock = if (pageOpen()) PageClock.json() else null
            if (clock != null) {
                if (!scriptHasClock) installScript() // new pages start with it too
                injector.configureLive(PageScript.config(clock = clock)) {}
            }
            handler.postDelayed(this, if (scriptHasClock) CLOCK_REFRESH_MS else CLOCK_RETRY_MS)
        }
    }

    private companion object {
        /** How often the page's clock is refreshed while in front, and retried until the first one. */
        const val CLOCK_REFRESH_MS = 5 * 60_000L
        const val CLOCK_RETRY_MS = 2_000L
    }
}
