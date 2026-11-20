package app.throwback.timelimit.data

import app.throwback.timelimit.engine.Moment
import app.throwback.timelimit.engine.PendingLimit
import app.throwback.timelimit.engine.PendingPin
import app.throwback.timelimit.engine.PinHash
import app.throwback.timelimit.engine.TimeLimitState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Turns the engine state into the JSON string kept in the `timelimit` DataStore file, and back.
 * That file is in Auto Backup (res/xml/backup_rules.xml, data_extraction_rules.xml), so this is
 * also what a reinstall restores: the limit, PIN hash and salt, today's usage and extensions,
 * pending changes and a pending PIN reset. Pure JVM, so it's tested without Android.
 */
internal object StateCodec {
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(s: TimeLimitState): String = json.encodeToString(Stored.serializer(), s.toStored())

    /** Null if [text] isn't a state this version understands. */
    fun decode(text: String): TimeLimitState? =
        runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull()
            ?.takeIf { it.version == VERSION }
            ?.let { runCatching { it.toState() }.getOrNull() }

    @Serializable
    private data class Stored(
        val version: Int = VERSION,
        val limitMinutes: Int? = null,
        val pin: StoredPin? = null,
        val day: String? = null,
        val usedMillis: Long = 0,
        val extensionsUsed: Int = 0,
        val lockedAt: StoredMoment? = null,
        val pendingLimit: StoredPendingLimit? = null,
        val pendingPin: StoredPendingPin? = null,
        val pinResetRequestedAt: StoredMoment? = null,
        val wrongPinAttempts: Int = 0,
        val pinBlockedAt: StoredMoment? = null,
        val anchor: StoredMoment? = null,
    )

    @Serializable
    private data class StoredPin(val salt: String, val hash: String, val iterations: Int)

    @Serializable
    private data class StoredMoment(val boot: Int, val elapsed: Long, val wall: Long)

    /** [minutes] null means "turn the limit off". */
    @Serializable
    private data class StoredPendingLimit(val minutes: Int? = null, val effective: String)

    @Serializable
    private data class StoredPendingPin(val pin: StoredPin, val effective: String)

    private fun TimeLimitState.toStored() = Stored(
        limitMinutes = limitMinutes,
        pin = pin?.toStored(),
        day = day?.toString(),
        usedMillis = usedMillis,
        extensionsUsed = extensionsUsed,
        lockedAt = lockedAt?.toStored(),
        pendingLimit = pendingLimit?.let { StoredPendingLimit(it.minutes, it.effective.toString()) },
        pendingPin = pendingPin?.let { StoredPendingPin(it.pin.toStored(), it.effective.toString()) },
        pinResetRequestedAt = pinResetRequestedAt?.toStored(),
        wrongPinAttempts = wrongPinAttempts,
        pinBlockedAt = pinBlockedAt?.toStored(),
        anchor = anchor?.toStored(),
    )

    private fun Stored.toState() = TimeLimitState(
        limitMinutes = limitMinutes,
        pin = pin?.toPin(),
        day = day?.let(LocalDate::parse),
        usedMillis = usedMillis,
        extensionsUsed = extensionsUsed,
        lockedAt = lockedAt?.toMoment(),
        pendingLimit = pendingLimit?.let { PendingLimit(it.minutes, LocalDate.parse(it.effective)) },
        pendingPin = pendingPin?.let { PendingPin(it.pin.toPin(), LocalDate.parse(it.effective)) },
        pinResetRequestedAt = pinResetRequestedAt?.toMoment(),
        wrongPinAttempts = wrongPinAttempts,
        pinBlockedAt = pinBlockedAt?.toMoment(),
        anchor = anchor?.toMoment(),
    )

    private fun PinHash.toStored() = StoredPin(salt, hash, iterations)
    private fun StoredPin.toPin() = PinHash(salt, hash, iterations)
    private fun Moment.toStored() = StoredMoment(bootCount, elapsedMillis, wallMillis)
    private fun StoredMoment.toMoment() = Moment(boot, elapsed, wall)
}
