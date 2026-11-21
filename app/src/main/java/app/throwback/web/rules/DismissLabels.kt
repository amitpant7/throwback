package app.throwback.web.rules

import java.util.Locale

/**
 * The only buttons remote rules may press in Instagram's dialogs (DECISIONS.md #34d): ones that
 * close, decline or put off, never ones that act (follow, allow, accept, log out). A rules file whose
 * dialog rules name any other label is refused, so a tampered file can't press anything else; the
 * page also presses an element found by a rule's selector only when its label is one of the rule's
 * (checked) labels. Adding a label here needs an app update, by design.
 */
internal object DismissLabels {
    private val LABELS = setOf(
        // English
        "not now", "cancel", "close", "dismiss", "no thanks", "no, thanks", "maybe later", "later",
        "decline", "decline all", "reject all", "decline optional cookies", "reject optional cookies",
        "only allow essential cookies",
        // German, French, Spanish, Portuguese, Italian (Instagram's cookie and "use the app" dialogs)
        "jetzt nicht", "abbrechen", "schließen", "alle ablehnen", "optionale cookies ablehnen",
        "nur erforderliche cookies erlauben",
        "plus tard", "pas maintenant", "annuler", "fermer", "tout refuser", "refuser les cookies optionnels",
        "ahora no", "cancelar", "cerrar", "rechazar todo", "rechazar cookies opcionales",
        "agora não", "fechar", "recusar tudo", "recusar cookies opcionais",
        "non ora", "annulla", "chiudi", "rifiuta tutto", "rifiuta cookie facoltativi",
    )

    private val WHITESPACE = Regex("\\s+")
    private val INVISIBLE = Regex("[\u200B-\u200D\u2060\uFEFF]")

    /** True when [label], normalised as the page normalises texts (10-helpers.js norm), is a dismiss label. */
    fun allows(label: String): Boolean =
        label.replace(INVISIBLE, "").replace(WHITESPACE, " ").trim().lowercase(Locale.ROOT) in LABELS
}
