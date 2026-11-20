package app.throwback.ui.settings

import app.throwback.ui.SettingsStart

/** Screens reachable inside SettingsRoot. */
internal enum class SettingsRoute {
    Home,
    TimeLimit,

    /** Messages & calls: the wizard until it is finished, then the settings screen. */
    Messages,

    /** The wizard opened explicitly from the Messages & calls settings ("Finish setup"). */
    MessagesWizard,
    Shortcut,
    About,
}

/** Back-stack rules for SettingsRoot, kept pure so they can be unit-tested. */
internal object SettingsNav {

    fun initial(start: SettingsStart): List<SettingsRoute> = when (start) {
        SettingsStart.Home -> listOf(SettingsRoute.Home)
        // Opened from "Instagram app found": back from the wizard returns to Throwback, not to Settings.
        SettingsStart.MessagesAndCalls -> listOf(SettingsRoute.Messages)
    }

    fun push(stack: List<SettingsRoute>, route: SettingsRoute): List<SettingsRoute> =
        if (stack.lastOrNull() == route) stack else stack + route

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
