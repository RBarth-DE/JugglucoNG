package tk.glucodata.glucosemeter

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterSyncPacingTests {
    /** Links back to back, each [linkMs] long, dialled [gapMs] after the last one ended. */
    private class Meter(val pacer: MeterLinkPacer = MeterLinkPacer(), var now: Long = 1_000_000L) {
        fun link(
            outcome: MeterLinkOutcome,
            quick: Boolean = true,
            linkMs: Long = 10_000L,
            minutes: Int = 5,
        ): Long {
            val connectedAt = now
            now += linkMs
            val wait = pacer.linkEnded(outcome, connectedAt, quick, now, minutes)
            now += maxOf(wait, 23_000L)
            return wait
        }
    }

    @Test
    fun aMeterThatStaysAwakeIsPacedOnceItHasBeenAwakeTwoMinutes() {
        // Satellite in #798: back within a second of every link, nothing new, every 33 s.
        val meter = Meter()
        val waits = List(8) { meter.link(MeterLinkOutcome.NOTHING_NEW) }
        assertEquals(listOf(0L, 0L, 0L, 0L), waits.take(4))
        assertEquals("then one link per interval, for as long as it stays awake", List(4) { 300_000L }, waits.drop(4))
    }

    @Test
    fun aMeterThatSleepsBetweenMeasurementsIsNeverHeldBack() {
        // Contour: a fingerstick, the transfer, a minute of answering with nothing new, asleep.
        val meter = Meter()
        assertEquals(0L, meter.link(MeterLinkOutcome.NEW_READINGS))
        assertEquals(0L, meter.link(MeterLinkOutcome.NOTHING_NEW))
        assertEquals(0L, meter.link(MeterLinkOutcome.NOTHING_NEW))
        // The next link waits for the meter to wake up, so it comes slowly: a fresh start.
        meter.now += 3_600_000L
        assertEquals(0L, meter.link(MeterLinkOutcome.NOTHING_NEW, quick = false))
        assertEquals(0L, meter.link(MeterLinkOutcome.NOTHING_NEW))
        assertEquals(0L, meter.link(MeterLinkOutcome.NEW_READINGS))
    }

    @Test
    fun newReadingsOrAnUnansweredLinkEndTheAwakeRun() {
        for (breaker in listOf(MeterLinkOutcome.NEW_READINGS, MeterLinkOutcome.UNANSWERED)) {
            val meter = Meter()
            repeat(4) { meter.link(MeterLinkOutcome.NOTHING_NEW) }
            assertEquals(300_000L, meter.link(MeterLinkOutcome.NOTHING_NEW))
            assertEquals(0L, meter.link(breaker))
            assertEquals("$breaker restarts the two minutes", 0L, meter.link(MeterLinkOutcome.NOTHING_NEW))
        }
    }

    @Test
    fun aFailureNeverDelaysOnItsOwnButARepeatingOneIsPaced() {
        val meter = Meter()
        assertEquals(0L, meter.link(MeterLinkOutcome.FAILED))
        assertEquals(0L, meter.link(MeterLinkOutcome.FAILED))
        // A wrong Satellite code fails every link: not redialled twice a minute forever.
        repeat(2) { meter.link(MeterLinkOutcome.FAILED) }
        assertEquals(300_000L, meter.link(MeterLinkOutcome.FAILED))
    }

    @Test
    fun intervalZeroReconnectsAtOnceAsBefore() {
        val meter = Meter()
        repeat(10) { assertEquals(0L, meter.link(MeterLinkOutcome.NOTHING_NEW, minutes = 0)) }
        assertTrue(MeterSyncPacing.DEFAULT_MINUTES in MeterSyncPacing.CHOICES_MINUTES)
    }

    @Test
    fun onlyASuccessfulReportStoredRecordsAnswerCountsAsASync() {
        fun racp(vararg bytes: Int) = MeterSyncPacing.racpReportSucceeded(ByteArray(bytes.size) { bytes[it].toByte() })
        assertTrue("records sent", racp(0x06, 0x00, 0x01, 0x01))
        assertTrue("no records stored", racp(0x06, 0x00, 0x01, 0x06))
        assertFalse("op code not supported", racp(0x06, 0x00, 0x01, 0x02))
        assertFalse("invalid operator", racp(0x06, 0x00, 0x01, 0x03))
        assertFalse("procedure not completed", racp(0x06, 0x00, 0x01, 0x08))
        assertFalse("answer to another request", racp(0x06, 0x00, 0x04, 0x01))
        assertFalse("number of records response", racp(0x05, 0x00, 0x03, 0x00))
        assertFalse("truncated", racp(0x06, 0x00, 0x01))
        assertFalse(MeterSyncPacing.racpReportSucceeded(null))
    }

    @Test
    fun productionPacingMethodsSurviveStaleTasksAndNewerLinks() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test-meter-pacing.py").isFile }
        val output = File.createTempFile("meter-pacing-", ".log")
        try {
            val process = ProcessBuilder("python3", File(root, "tools/test-meter-pacing.py").path)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("Meter pacing timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            output.delete()
        }
    }
}
