package app.throwback.connect.guard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.throwback.connect.Permissions
import app.throwback.connect.RelayDebug
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_DESTROYED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_PAUSED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_RESUMED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_STOPPED
import app.throwback.core.LockState
import app.throwback.timelimit.TimeLimitModule
import app.throwback.web.rules.RulesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** One activity event from UsageStatsManager ([time] in the phone's wall ms), copied out so the sum is plain Kotlin. */
internal data class ActivityEvent(val time: Long, val type: Int, val packageName: String, val className: String?)

/** Time spent in an app, from usage events (pure, for tests). */
internal object AppTime {
    /**
     * How long any of [watched] was in front between [from] and [to]: each activity from its RESUMED
     * to its PAUSED, STOPPED or DESTROYED, overlapping ones (split screen, Instagram and Lite) once.
     * Screens matching [notCounted] (Instagram's call screens: calls never count, DECISIONS.md #20)
     * are left out. [events] are in time order and may start before [from], to know what was in front then.
     */
    fun inFront(events: List<ActivityEvent>, watched: Set<String>, from: Long, to: Long, notCounted: Regex? = null): Long {
        val open = HashSet<String>()
        var since = from
        var total = 0L
        for (e in events) {
            if (e.packageName !in watched) continue
            if (notCounted != null && e.className?.let(notCounted::containsMatchIn) == true) continue
            val key = e.packageName + "/" + e.className.orEmpty()
            val t = e.time.coerceIn(from, to)
            when (e.type) {
                ACTIVITY_RESUMED -> if (open.add(key) && open.size == 1) since = t
                ACTIVITY_PAUSED, ACTIVITY_STOPPED, ACTIVITY_DESTROYED -> if (open.remove(key) && open.isEmpty()) total += t - since
            }
        }
        if (open.isNotEmpty()) total += to - since
        return total
    }
}

/**
 * While the guard runs (with the screen on) it notes the time every [BEAT_MS]. If Throwback comes to
 * the front and the last beat is old, the guard was stopped: its permissions were revoked, or
 * Android (or "Stop" in Android's active apps) ended it. The real Instagram app's time since then
 * counts against today's limit (DECISIONS.md #27b: "count the real app's time against the limit
 * when Throwback next runs"), read from Android's usage events.
 */
internal object GuardHeartbeat {
    const val BEAT_MS = 30_000L

    /** An older beat means the guard wasn't running (with the screen off nothing is on screen to count). */
    private const val STALE_MS = 2 * 60_000L

    /** Read a little before the last beat, to know whether Instagram was already in front then. */
    private const val READ_BEFORE_MS = 10 * 60_000L
    private const val FILE = "guard_heartbeat"
    private const val KEY = "beat"
    private const val TAG = "ThrowbackGuard"

    fun beat(context: Context, wallNow: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY, wallNow) }
    }

    /**
     * The guard was turned off on purpose (Instagram disconnected), or turned on again after that:
     * nothing before now counts. A catch-up running at the same moment doesn't write its beat back.
     */
    fun clear(context: Context) {
        clears++
        prefs(context).edit { remove(KEY) }
    }

    @Volatile
    private var clears = 0

    /** One catch-up at a time: Throwback coming to the front and the guard starting can race. */
    private val catchingUp = Mutex()

    /**
     * Throwback came to the front, or the guard (re)starts: counts the Instagram app's time since a
     * stale beat, then notes "counted up to now". Off the main thread.
     */
    suspend fun catchUp(context: Context) {
        catchingUp.withLock { countSinceLastBeat(context) }
    }

    private suspend fun countSinceLastBeat(context: Context) {
        val clearsAtStart = clears
        val p = prefs(context)
        if (!p.contains(KEY)) return
        val last = p.getLong(KEY, 0L)
        val now = System.currentTimeMillis()
        if (now - last in 0 until STALE_MS) return
        // Without usage access Android returns no events (not an error): keep the beat, count later.
        if (!Permissions.hasUsageAccess(context)) return
        val limit = TimeLimitModule.create(context)
        val lockState = limit.awaitLockState() ?: return
        if (lockState != LockState.Disabled) {
            val today = limit.trustedTime() ?: return
            // Only today's time counts: from midnight of the trusted day, on the phone's clock (the
            // events'). Taken as is, or moved by the phone's clock's offset from the trusted time,
            // whichever is earlier: the offset is wrong while the trusted time lags (after a reboot,
            // before network time), the plain value while the phone's clock is set wrong.
            val midnight = LocalDate.ofEpochDay(today.epochDay).atStartOfDay(today.zone).toInstant().toEpochMilli()
            val from = max(last, min(midnight, midnight + (now - today.wallMillis)))
            if (from < now) {
                val events = read(context, from - READ_BEFORE_MS, now) ?: return
                // Loaded first: at a cold start the rules aren't in memory yet, and calls never count.
                val rules = runCatching { RulesRepository.get(context).load() }.getOrNull()
                val callScreens = PassRules.screensPattern(rules?.appCall?.screens)
                val millis = AppTime.inFront(events, RelayDebug.watchedPackages(), from, now, notCounted = callScreens)
                if (millis > 0) {
                    Log.i(TAG, "the guard was off: ${millis / 1000} s of the Instagram app count against today's limit")
                    limit.addOutsideUsage(millis)
                }
            }
        }
        if (clears == clearsAtStart) beat(context, now) // counted up to now (unless turned off meanwhile)
    }

    /** The watched apps' activity events, or null if Android won't give them. */
    private fun read(context: Context, begin: Long, end: Long): List<ActivityEvent>? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = try {
            usm.queryEvents(begin, end)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val watched = RelayDebug.watchedPackages()
        val out = ArrayList<ActivityEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg in watched) out += ActivityEvent(e.timeStamp, e.eventType, pkg, e.className)
        }
        return out
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
