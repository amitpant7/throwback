package app.throwback.ui.settings


/** Where Settings opens: its home, Connect › Instagram, or Support. */
enum class SettingsStart { Home, ConnectInstagram, Support, TimeLimit }

/** Screens reachable inside SettingsRoot. */
internal enum class SettingsRoute {
    Home,
    Today,
    TimeLimit,

    /** Connect › Instagram: the setup until it is finished, then the settings screen. */
    Messages,

    /** The wizard opened explicitly from the Connect › Instagram settings ("Finish setup"). */
    MessagesWizard,

    /** PIN before the Instagram app is disconnected (only while a daily limit is on). */
    MessagesOffPin,

    /** Right after a daily limit is turned on, when the Instagram app is installed but not connected. */
    LimitConnectOffer,
    Shortcut,
    Support,
    About,
}

/** Back-stack rules for SettingsRoot, kept pure so they can be unit-tested. */
internal object SettingsNav {

    fun initial(start: SettingsStart): List<SettingsRoute> = when (start) {
        SettingsStart.Home -> listOf(SettingsRoute.Home)
        // Opened from "Instagram app found": back from the wizard returns to Throwback, not to Settings.
        SettingsStart.ConnectInstagram -> listOf(SettingsRoute.Messages)
        // "Say thanks" in the feed: Support, with Back going to Settings.
        SettingsStart.Support -> listOf(SettingsRoute.Home, SettingsRoute.Support)
        // "Set a daily limit now" on the welcome pages: Back returns to them.
        SettingsStart.TimeLimit -> listOf(SettingsRoute.TimeLimit)
    }

    fun push(stack: List<SettingsRoute>, route: SettingsRoute): List<SettingsRoute> =
        if (stack.lastOrNull() == route) stack else stack + route

    /** The stack with its top entry swapped for [route] (one screen leads to the next; Back skips it). */
    fun replaceTop(stack: List<SettingsRoute>, route: SettingsRoute): List<SettingsRoute> = stack.dropLast(1) + route

    /** The stack after going back, or null when already at the root (close Settings). */
    fun pop(stack: List<SettingsRoute>): List<SettingsRoute>? =
        if (stack.size <= 1) null else stack.dropLast(1)

    /** Stable key for an entry's saved UI state (scroll position, wizard step). */
    fun key(stack: List<SettingsRoute>): String = "${stack.size}:${stack.last().name}"

    fun save(stack: List<SettingsRoute>): List<String> = stack.map { it.name }

    fun restore(saved: List<String>): List<SettingsRoute> =
        saved.mapNotNull { name -> SettingsRoute.entries.firstOrNull { it.name == name } }
            .ifEmpty { listOf(SettingsRoute.Home) }
}
