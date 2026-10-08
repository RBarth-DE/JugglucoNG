package tk.glucodata.ui

import android.os.Build
import android.view.View

/**
 * Keep the system from repainting this view tree in "force dark".
 *
 * Compose draws its own light and dark schemes, but on Android 10+ a device-wide force-dark
 * setting (MIUI's "dark mode for apps", or the developer option "Override force-dark") still
 * inverts what it draws: dark text on a light container becomes light text on that same light
 * container. That is how the selected Raw/Auto segment on the sensor card lost its label on a
 * Mi 9T. Every window the app fills with Compose opts out here.
 */
internal fun View.optOutOfForceDark() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isForceDarkAllowed = false
    }
}
