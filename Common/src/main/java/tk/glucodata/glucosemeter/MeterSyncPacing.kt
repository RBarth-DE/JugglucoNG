package tk.glucodata.glucosemeter

import android.content.Context
import tk.glucodata.Applic

/**
 * How soon a Bluetooth meter is dialled again after a sync that brought nothing new.
 *
 * A meter that sleeps after a transfer only advertises again once someone measures, so the
 * reconnect after a sync with new readings stays immediate: holding it back could miss that
 * short window. A meter that never sleeps (Satellite, #798) ends every idle link and is back
 * within a second, and redialling it at once re-read it twice a minute for as long as it was
 * in range. The wait is also how much later a fingerstick on such a meter can arrive.
 */
object MeterSyncPacing {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_MINUTES = "glucose_meter_recheck_minutes"

    const val DEFAULT_MINUTES = 5

    /** 0 reconnects at once, as before. */
    @JvmField
    val CHOICES_MINUTES: List<Int> = listOf(0, 5, 15, 30)

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

    /** Delay before reconnecting after a link that just ended. */
    @JvmStatic
    fun reconnectDelayMillis(synced: Boolean, broughtNew: Boolean, minutes: Int): Long =
        if (synced && !broughtNew && minutes > 0) minutes * 60_000L else 0L
}
