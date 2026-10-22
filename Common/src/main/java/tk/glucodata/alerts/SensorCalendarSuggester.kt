package tk.glucodata.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.R

/**
 * Once per sensor, as soon as its end date is known, offers to put that date in the
 * calendar app. A background app may not start the calendar activity itself, so the
 * offer is a notification whose tap opens the calendar's "new event" screen.
 * Switchable via [PREF_ENABLED]; each serial is offered at most once.
 */
internal object SensorCalendarSuggester {
    const val PREF_ENABLED = "sensor_calendar_suggest"
    private const val PREF_OFFERED = "sensor_calendar_offered"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val CHANNEL_ID = "SENSOR_CALENDAR"
    private const val EVENT_LEAD_MS = 60L * 60L * 1000L
    private const val LOG_ID = "SensorCalendarSuggester"

    fun evaluate(nowMs: Long) {
        try {
            val prefs = Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_ENABLED, true)) return
            val candidates = sensorExpiryCandidates() ?: return
            for ((serial, endMs) in candidates) {
                if (endMs <= nowMs) continue
                val offered = prefs.getStringSet(PREF_OFFERED, emptySet()) ?: emptySet()
                if (serial in offered) continue
                // Only remember the offer once it was really posted; without the
                // notification permission a later tick may still succeed.
                if (notifyOffer(Applic.app, serial, endMs)) {
                    prefs.edit().putStringSet(PREF_OFFERED, offered + serial).apply()
                }
            }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "evaluate", th)
        }
    }

    private fun notifyOffer(context: Context, serial: String, endMs: Long): Boolean {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.sensor_calendar_notification_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { setShowBadge(false) },
            )
        }
        val insert = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, context.getString(R.string.enddatesensor) + serial)
            .putExtra(
                CalendarContract.Events.DESCRIPTION,
                context.getString(R.string.sensor) + " " + serial + context.getString(R.string.endstime),
            )
            // The hour before the sensor ends: enough time to start the next one.
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, endMs - EVENT_LEAD_MS)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = 0x5343 + (serial.hashCode() and 0xFFF)
        val pending = PendingIntent.getActivity(
            context, id, insert, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.sensor_calendar_notification_text, serial)
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle(context.getString(R.string.sensor_calendar_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
        return true
    }
}
