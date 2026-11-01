package tk.glucodata.glucosemeter

import android.content.Context
import tk.glucodata.Applic

/**
 * How soon a Bluetooth meter is dialled again after a link that brought nothing new.
 *
 * A meter that sleeps between measurements (Contour and most others) advertises only briefly
 * after one, so it is always redialled at once: a closed transport during that short window
 * would leave the reading on the meter until the next measurement. A meter that never sleeps
 * (Satellite, #798) is back within a second of every idle link, and redialling it at once
 * re-read it twice a minute for as long as it was in range. The two are told apart by what the
 * meter does, the same rule for every meter: only once it has kept answering straight away with
 * nothing new for [AWAKE_BEFORE_PACING_MS] does the chosen interval apply. The interval is also
 * how much later a fingerstick on such a meter can arrive.
 */
object MeterSyncPacing {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_MINUTES = "glucose_meter_recheck_minutes"

    const val DEFAULT_MINUTES = 5

    /** 0 reconnects at once, as before. */
    @JvmField
    val CHOICES_MINUTES: List<Int> = listOf(0, 5, 15, 30)

    /** A link that came up this soon after we started dialling found the meter already awake. */
    const val QUICK_CONNECT_MS = 60_000L

    /** How long a meter must stay awake with nothing new before reconnects are spaced out. */
    const val AWAKE_BEFORE_PACING_MS = 2 * 60_000L

    @JvmStatic
    fun minutes(): Int {
        val stored = Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MINUTES, DEFAULT_MINUTES)
        return if (stored in CHOICES_MINUTES) stored else DEFAULT_MINUTES
    }

    @JvmStatic
    fun setMinutes(minutes: Int) {
        if (minutes !in CHOICES_MINUTES) return
        Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MINUTES, minutes)
            .apply()
    }

    /**
     * Whether a Record Access Control Point indication closes a Report Stored Records request
     * successfully: records sent (`06 00 01 01`) or none stored (`06 00 01 06`). Every request
     * GlucoseMeterGatt writes is that op code, so anything else is a failed transfer.
     */
    @JvmStatic
    fun racpReportSucceeded(value: ByteArray?): Boolean =
        value != null && value.size >= 4 &&
            value[0] == 0x06.toByte() && value[1] == 0x00.toByte() && value[2] == 0x01.toByte() &&
            (value[3] == 0x01.toByte() || value[3] == 0x06.toByte())
}

/** How a link to a meter ended. */
enum class MeterLinkOutcome {
    /** Dropped before the meter answered the record request. */
    UNANSWERED,
    /** Stored at least one reading we did not have. */
    NEW_READINGS,
    /** The meter answered and had nothing we did not have. */
    NOTHING_NEW,
    /** The meter answered with an error, or something we could not read. */
    FAILED,
}

/** One per meter. Not thread-safe: the caller holds the meter's lock. */
class MeterLinkPacer {
    /** Start of the current run of links that found the meter awake with nothing new. */
    private var awakeSinceMs = NOT_AWAKE

    /**
     * Records a link that ended and returns how long to wait before dialling again.
     * A failed link counts like an empty one, so it never delays on its own, while a meter that
     * fails the same way on every link (a wrong Satellite code) is not redialled twice a minute.
     */
    fun linkEnded(
        outcome: MeterLinkOutcome,
        connectedAtMs: Long,
        connectedQuickly: Boolean,
        endedAtMs: Long,
        minutes: Int,
    ): Long {
        if (outcome == MeterLinkOutcome.UNANSWERED || outcome == MeterLinkOutcome.NEW_READINGS) {
            awakeSinceMs = NOT_AWAKE
            return 0L
        }
        // A slow link means the meter was asleep until now: a run can only start here.
        if (!connectedQuickly || awakeSinceMs == NOT_AWAKE) awakeSinceMs = connectedAtMs
        val awake = endedAtMs - awakeSinceMs >= MeterSyncPacing.AWAKE_BEFORE_PACING_MS
        return if (awake && minutes > 0) minutes * 60_000L else 0L
    }

    private companion object {
        const val NOT_AWAKE = Long.MIN_VALUE
    }
}
