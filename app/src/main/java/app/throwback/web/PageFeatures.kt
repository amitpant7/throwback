package app.throwback.web

/** App features the page needs to know about (set by the app). */
internal object PageFeatures {
    /** The Instagram app is connected: "+" and "Your story" open its create screen. */
    @Volatile
    var createInApp: Boolean = false
}
