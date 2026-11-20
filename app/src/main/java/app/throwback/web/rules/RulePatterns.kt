package app.throwback.web.rules

/**
 * The one check for every regex in the rules file: paths, pages, blocked URLs, the Instagram app's
 * screens and the notification fields.
 *
 * Path and page patterns run twice: in Kotlin (UrlRouter, on the main thread, on URLs a web page
 * picks) and in the page, which compiles them with JavaScript's `new RegExp(p)` and silently drops
 * any it rejects. So a pattern must compile in Java, mean the same in JavaScript, and stay cheap on
 * any input. The same rules apply to the patterns only Kotlin uses, so there is one set of rules.
 *
 * Cheap: the shapes that backtrack exponentially (a repeated group holding a quantifier or an
 * alternative) are refused by syntax, in every engine. Then each pattern runs on a few long probes
 * with a budget of character reads, which also catches slow polynomial patterns. That budget only
 * works where the regex engine reads through the CharSequence: on the JVM, so in the unit tests that
 * check filters/rules.json before it is published. Android's engine (ICU) copies the input and
 * can't be stopped, so the probes are skipped there: the syntax rules and the router's 2 KB cap on
 * what patterns see are the guard on the phone.
 */
internal object RulePatterns {
    const val MAX_PATTERN = 300

    /** Longest short text (labels, button texts, notification categories). */
    const val MAX_TEXT = 100

    /**
     * Checks [pattern]. Returns it compiled, or null after adding an error (prefixed with [where])
     * to [errors].
     */
    fun check(pattern: String, where: String, errors: MutableList<String>): Regex? {
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN) {
            errors += "$where: pattern length"
            return null
        }
        val regex = try {
            Regex(pattern)
        } catch (e: IllegalArgumentException) {
            errors += "$where: invalid pattern '$pattern'"
            return null
        }
        val problem = syntaxProblem(pattern) ?: costProblem(regex, pattern)
        if (problem != null) {
            errors += "$where: pattern '$pattern' $problem"
            return null
        }
        return regex
    }

    // ------------------------------------------------------------------ syntax

    /** Escapes only Java knows; in JavaScript (no `u` flag) each just matches its letter. */
    private const val JAVA_ONLY_ESCAPES = "AZzGQEpPhHRX"

    private const val NOT_JS = "uses Java-only syntax (the page's JavaScript reads it differently or drops it)"
    private const val NESTED = "repeats a group that holds a quantifier or an alternative (it can backtrack for ages)"
    private const val COSTLY = "takes too long on long input (anchor it with ^, avoid repeats that overlap)"

    /**
     * Java-only syntax, or a repeated group (`*`, `+`, `{n,m}`) whose body holds a quantifier or an
     * alternative (`(a+)+`, `(?:a*b)*`, `(x{2,})+`, `(a|aa)+`, `(a?a)*`): the shapes that backtrack
     * exponentially. Null when fine. Assumes [p] already compiles in Java.
     */
    internal fun syntaxProblem(p: String): String? {
        // For each open group: whether its body holds a quantifier or a '|'.
        val open = ArrayList<Boolean>()
        var inClass = false
        var afterGroup: Boolean? = null // right after ')': whether that group's body holds one
        var i = 0
        while (i < p.length) {
            val c = p[i]
            var closedGroup: Boolean? = null
            if (c == '\\') {
                val next = p.getOrNull(i + 1)
                if (next != null && next in JAVA_ONLY_ESCAPES) return NOT_JS
                if (next == 'x' && p.getOrNull(i + 2) == '{') return NOT_JS
                i += 2
            } else if (inClass) {
                when {
                    c == ']' -> inClass = false
                    // Nested classes and intersections are Java-only.
                    c == '[' || (c == '&' && p.getOrNull(i + 1) == '&') -> return NOT_JS
                }
                i++
            } else {
                when (c) {
                    '[' -> {
                        val first = if (p.getOrNull(i + 1) == '^') i + 2 else i + 1
                        // "[]" and "[^]" mean "nothing" and "anything" in JavaScript only.
                        if (p.getOrNull(first) == ']') return NOT_JS
                        inClass = true
                        i = first
                    }
                    '(' -> {
                        i = if (p.startsWith("(?", i)) {
                            val kind = p.getOrNull(i + 2)
                            when {
                                kind == ':' || kind == '=' || kind == '!' -> i + 3
                                p.startsWith("(?<=", i) || p.startsWith("(?<!", i) -> i + 4
                                kind == '<' -> p.indexOf('>', i).let { if (it < 0) return NOT_JS else it + 1 } // named group
                                // Inline flags such as (?i) or (?-s:...), atomic groups (?>...).
                                else -> return NOT_JS
                            }
                        } else {
                            i + 1
                        }
                        open += false
                    }
                    ')' -> {
                        val inner = if (open.isEmpty()) false else open.removeAt(open.lastIndex)
                        if (inner && open.isNotEmpty()) open[open.lastIndex] = true
                        closedGroup = inner
                        i++
                    }
                    '*', '+', '?', '{' -> {
                        var end = if (c == '{') p.indexOf('}', i).let { if (it < 0) i else it } + 1 else i + 1
                        if (p.getOrNull(end) == '?') end++ // lazy: fine in both
                        else if (p.getOrNull(end) == '+') return NOT_JS // possessive
                        if (c != '?' && afterGroup == true) return NESTED
                        if (open.isNotEmpty()) open[open.lastIndex] = true
                        i = end
                    }
                    '|' -> {
                        if (open.isNotEmpty()) open[open.lastIndex] = true
                        i++
                    }
                    else -> i++
                }
            }
            afterGroup = closedGroup
        }
        return null
    }

    // ------------------------------------------------------------------ cost

    /** Characters a pattern may read per probe before it counts as too slow. */
    private const val READ_BUDGET = 100_000
    private const val PROBE_LENGTH = 600

    /** Long runs of what URL, class-name and channel patterns repeat over, each ending in a mismatch. */
    private val PROBE_BODIES: List<String> = listOf("a", "0", "/", "a/", "a.", "_-").map {
        it.repeat(PROBE_LENGTH / it.length) + "!"
    }

    private class ReadBudgetExceeded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** [text] as a CharSequence that throws once more than the budget's characters were read. */
    private class CountedChars(
        private val text: String,
        private val budget: IntArray,
        private val start: Int = 0,
        private val end: Int = text.length,
    ) : CharSequence {
        override val length: Int get() = end - start

        override fun get(index: Int): Char {
            if (--budget[0] < 0) throw ReadBudgetExceeded()
            return text[start + index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            CountedChars(text, budget, start + startIndex, start + endIndex)

        override fun toString(): String = text.substring(start, end)
    }

    /**
     * Whether this runtime's regex engine reads its input through the CharSequence, so that
     * [CountedChars] can stop it: true for the JVM's java.util.regex, false on Android (ICU).
     */
    internal val canBudgetReads: Boolean by lazy {
        val budget = intArrayOf(Int.MAX_VALUE)
        Regex("b").containsMatchIn(CountedChars("ab", budget))
        budget[0] != Int.MAX_VALUE
    }

    /**
     * Runs [regex] once on each probe (with and without the pattern's literal start, so that
     * `^/explore/(...)` gets past its prefix) and reports it when one probe reads too much.
     * Skipped where reads can't be counted (see [canBudgetReads]): a slow probe there could not be stopped.
     */
    private fun costProblem(regex: Regex, pattern: String): String? {
        if (!canBudgetReads) return null
        val prefix = literalPrefix(pattern)
        for (body in PROBE_BODIES) {
            for (probe in if (prefix.isEmpty()) listOf(body) else listOf(body, prefix + body)) {
                try {
                    regex.containsMatchIn(CountedChars(probe, intArrayOf(READ_BUDGET)))
                } catch (e: ReadBudgetExceeded) {
                    return COSTLY
                } catch (e: StackOverflowError) {
                    // Deep recursion inside java.util.regex: just as unusable on a long URL.
                    return COSTLY
                }
            }
        }
        return null
    }

    /** The plain text a pattern starts with, after `^`: "/explore/" for `^/explore/(a|b)`. */
    internal fun literalPrefix(pattern: String): String {
        val out = StringBuilder()
        var i = if (pattern.startsWith("^")) 1 else 0
        while (i < pattern.length) {
            val c = pattern[i]
            val next = pattern.getOrNull(i + 1)
            when {
                c == '\\' && next != null && !next.isLetterOrDigit() -> {
                    out.append(next)
                    i += 2
                }
                c.isLetterOrDigit() || c in "/:_-=&%~,;@!'\"<># " -> {
                    // A character made optional or repeated by a quantifier ends the plain part.
                    if (next != null && next in "?*+{") break
                    out.append(c)
                    i++
                }
                else -> break
            }
        }
        return out.toString()
    }
}
