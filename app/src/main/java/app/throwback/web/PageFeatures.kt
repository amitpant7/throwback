package app.throwback.web

/** App features the page needs to know about (set by the app). */
internal object PageFeatures {
    /** The Instagram app is connected: "+" and "Your story" open its create screen. */
    @Volatile
    var createInApp: Boolean = false

    /** "Your story" may open the Instagram app now (else the website's own story upload). */
    @Volatile
    var storyInApp: Boolean = false
}
