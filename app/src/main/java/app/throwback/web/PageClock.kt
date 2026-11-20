package app.throwback.web

/**
 * Trusted time for the page's daily counters (set by the app from the time limit's clock, which
 * changing the phone's clock or time zone doesn't fool). [offsetMs]: trusted time minus the phone's
 * clock; [zoneOffsetMin]: UTC offset of the zone days are counted in.
 */
internal object PageClock {
    /** Returns offset ms, zone offset minutes, today (epoch day), and when the next day may start (trusted ms). */
    @Volatile
    var provider: (() -> LongArray)? = null

    fun json(): String? = provider?.invoke()?.let { v ->
        """{"offset":${v[0]},"zoneMin":${v[1]},"day":${v[2]},"nextAt":${v[3]}}"""
    }
}
