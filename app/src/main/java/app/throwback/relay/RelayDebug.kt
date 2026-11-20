package app.throwback.relay

import app.throwback.BuildConfig
import app.throwback.web.rules.NotificationMatch

/**
 * Test-build hooks for the emulator (no Instagram app there). Only the debug source set
 * (app/src/debug, never in release) posts fake notifications or sets [guardTestPackage]; every
 * check here is also gated on BuildConfig.DEBUG, so release builds ignore them.
 */
internal object RelayDebug {
    /** Fake "Instagram" notifications are posted by Throwback itself with a tag starting with this. */
    const val FAKE_TAG_PREFIX = "tb-fake:"

    /** An extra app the guard treats like Instagram (debug builds only), e.g. the emulator's Settings. */
    @Volatile
    var guardTestPackage: String? = null

    fun isFake(packageName: String, ownPackage: String, tag: String?): Boolean =
        BuildConfig.DEBUG && packageName == ownPackage && tag?.startsWith(FAKE_TAG_PREFIX) == true

    /**
     * Fake notifications use channels named like this; debug builds classify them by name, as if
     * the rules had entries for them (Instagram never uses these names).
     */
    const val FAKE_CHANNEL_PREFIX = "tb_fake_"

    fun extraMatches(): List<NotificationMatch> =
        if (!BuildConfig.DEBUG) {
            emptyList()
        } else {
            listOf("likes", "comments", "follows", "mentions", "live", "other").map {
                NotificationMatch(kind = it, channel = "^$FAKE_CHANNEL_PREFIX$it$")
            }
        }

    fun watchedPackages(): Set<String> {
        val extra = guardTestPackage?.takeIf { BuildConfig.DEBUG }
        return if (extra == null) InstagramPackages.ALL else InstagramPackages.ALL + extra
    }
}
