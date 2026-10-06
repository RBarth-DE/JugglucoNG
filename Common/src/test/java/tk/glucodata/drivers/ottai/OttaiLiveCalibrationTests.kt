package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.CalibrationAccess
import tk.glucodata.CalibrationProvider
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.CurrentGlucoseSource
import tk.glucodata.GlucosePoint
import tk.glucodata.LiveReadingLanes
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.StandardGlucoseAlertEvaluator

class OttaiLiveCalibrationTests {
    @Test
    fun calibratedLiveValueDoesNotTriggerStockLowBeforeOrAfterHistoryWrite() {
        for (isMmol in listOf(true, false)) {
            val scale = if (isMmol) 1f else 18.0182f
            val stock = 3.7f * scale
            val calibrated = 5.1f * scale
            withCalibration(1.4f * scale) {
                for (viewMode in listOf(0, 2)) {
                    for (history in listOf(emptyList(), listOf(GlucosePoint(TIME, stock, 0f)))) {
                        // Ottai supplies formula glucose and no raw glucose lane. Its
                        // rawCurrent diagnostic must never be treated as raw glucose.
                        val display = resolve(LiveReadingLanes.stock(stock, Float.NaN), history, viewMode, isMmol)
                        assertEquals(calibrated, display.primaryValue, 0.001f)
                        assertFalse(display.rawValue.isFinite() && display.rawValue > 0f)

                        // The external float API marks this value resolved. Both alert
                        // engines and the retained current reading must keep 5.1, not
                        // bypass calibration at 3.7 or apply the correction twice at 6.5.
                        val alert = resolve(LiveReadingLanes.resolved(display.primaryValue), history, viewMode, isMmol)
                        assertEquals(calibrated, alert.primaryValue, 0.001f)
                        assertTrue(isLow(stock, isMmol, scale))
                        assertFalse(isLow(alert.primaryValue, isMmol, scale))
                    }
                }
            }
        }
    }

    @Test
    fun liveReadingWithoutCalibrationStillTriggersARealLow() {
        withCalibration(0f, enabled = false) {
            val display = resolve(LiveReadingLanes.stock(3.7f, Float.NaN), emptyList(), 0, true)
            assertEquals(3.7f, display.primaryValue, 0.001f)
            assertTrue(isLow(display.primaryValue, true, 1f))
        }
    }

    private fun resolve(
        reading: LiveReadingLanes,
        history: List<GlucosePoint>,
        viewMode: Int,
        isMmol: Boolean,
    ) = requireNotNull(CurrentDisplaySource.resolveSnapshot(
        current = CurrentGlucoseSource.Snapshot.of(reading, TIME, "", 0f, SENSOR, 0, 0, "ottai-live"),
        recentPoints = history,
        historyStart = TIME - 120_000L,
        viewMode = viewMode,
        isMmol = isMmol,
        smoothingMode = CurrentDisplaySource.SmoothingMode(false, 0, false),
        sensorId = SENSOR,
    ))

    private fun isLow(value: Float, isMmol: Boolean, scale: Float) = AlertType.LOW in
        StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = value,
            rate = 0f,
            configs = mapOf(AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 3.9f * scale)),
            alertTypes = listOf(AlertType.LOW),
            isMmol = isMmol,
            isConfigActive = { true },
        )

    private fun withCalibration(offset: Float, enabled: Boolean = true, block: () -> Unit) {
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) =
                enabled && !isRawMode && sensorId == SENSOR

            override fun getCalibratedValue(
                value: Float, timestamp: Long, isRawMode: Boolean, emitDiagnostics: Boolean, sensorId: String?,
            ): Float {
                assertEquals(SENSOR, sensorId)
                assertFalse(isRawMode)
                return value + offset
            }
        })
        try {
            block()
        } finally {
            CalibrationAccess.unregisterForTests()
        }
    }

    private companion object {
        const val SENSOR = "B0E8E8871A25"
        const val TIME = 1_791_288_000_000L
    }
}
