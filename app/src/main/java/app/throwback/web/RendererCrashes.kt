package app.throwback.web

/**
 * What to do after the WebView's renderer went away, so a page that crashes it on every load can't
 * loop forever behind the loading cover. Pure, for tests; main thread only.
 *
 * A renderer the system killed in the background (reclaiming memory) is just replaced, on the same
 * page. A crash is too, the first time, and so is a kill while the app is in front (a page that eats
 * memory would loop just the same); one less than [windowMillis] after the previous one starts over
 * at home instead; a third such one gives up and shows the error screen.
 */
internal class RendererCrashes(private val now: () -> Long, private val windowMillis: Long = 60_000) {

    enum class Next { RESTORE, HOME, GIVE_UP }

    private var lastCrashAt: Long? = null
    private var inARow = 0

    /** @param inFront the app was in front (started) when it went. */
    fun onGone(crashed: Boolean, inFront: Boolean): Next {
        if (!crashed && !inFront) return Next.RESTORE
        val t = now()
        inARow = if (lastCrashAt?.let { t - it < windowMillis } == true) inARow + 1 else 1
        lastCrashAt = t
        return when (inARow) {
            1 -> Next.RESTORE
            2 -> Next.HOME
            else -> Next.GIVE_UP
        }
    }

    /** "Try again" on the error screen: start counting afresh. */
    fun reset() {
        lastCrashAt = null
        inARow = 0
    }
}
