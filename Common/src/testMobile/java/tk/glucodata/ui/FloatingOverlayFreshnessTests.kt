package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayDataState
import tk.glucodata.ui.overlay.nextOverlayFreshnessCheckDelay

/**
 * The floating overlay only recomposes on new data, so its own clock is the only
 * thing that can blank a reading once the sensor goes quiet (#554). Pin that the
 * clock keeps running until the reading is stale by the same rule the widget and
 * dashboard use, and stops after that.
 */
class FloatingOverlayFreshnessTests {
    private val now = 1_700_000_000_000L
    private val window = 330_000L

    @Test
    fun noReadingMeansNothingToWatch() {
        assertNull(nextOverlayFreshnessCheckDelay(0L, now, window))
    }

    @Test
    fun aFreshReadingIsRecheckedAtMostEveryFifteenSeconds() {
        assertEquals(15_000L, nextOverlayFreshnessCheckDelay(now - 10_000L, now, window))
    }

    @Test
    fun theLastCheckLandsJustPastTheTimeout() {
        val latest = now - window + 4_000L
        val wait = nextOverlayFreshnessCheckDelay(latest, now, window)!!
        assertEquals(4_001L, wait)
        assertTrue(isFresh(latest, now))
        assertFalse(isFresh(latest, now + wait))
        assertNull(nextOverlayFreshnessCheckDelay(latest, now + wait, window))
    }

    @Test
    fun aReadingExactlyAtTheTimeoutIsStillFreshAndStillWatched() {
        val latest = now - window
        assertTrue(isFresh(latest, now))
        assertEquals(1L, nextOverlayFreshnessCheckDelay(latest, now, window))
    }

    @Test
    fun aStaleReadingStopsTheClock() {
        val latest = now - window - 60_000L
        assertFalse(isFresh(latest, now))
        assertNull(nextOverlayFreshnessCheckDelay(latest, now, window))
    }

    private fun isFresh(latest: Long, nowMillis: Long) = DisplayDataState.resolve(
        sensorPresent = true,
        currentTimestampMillis = 0L,
        latestHistoryTimestampMillis = latest,
        freshnessWindowMillis = window,
        nowMillis = nowMillis
    ).isFresh
}
