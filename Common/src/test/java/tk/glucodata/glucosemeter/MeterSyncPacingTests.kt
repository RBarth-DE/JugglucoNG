package tk.glucodata.glucosemeter

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterSyncPacingTests {
    @Test
    fun waitsOnlyAfterASyncThatBroughtNothingNew() {
        assertEquals(5 * 60_000L, MeterSyncPacing.reconnectDelayMillis(synced = true, broughtNew = false, minutes = 5))
        // A meter that just sent a reading may only advertise briefly again: never hold it back.
        assertEquals(0L, MeterSyncPacing.reconnectDelayMillis(synced = true, broughtNew = true, minutes = 5))
        // A link that dropped before the meter answered is retried as before.
        assertEquals(0L, MeterSyncPacing.reconnectDelayMillis(synced = false, broughtNew = false, minutes = 5))
        assertEquals(0L, MeterSyncPacing.reconnectDelayMillis(synced = true, broughtNew = false, minutes = 0))
        assertTrue(MeterSyncPacing.DEFAULT_MINUTES in MeterSyncPacing.CHOICES_MINUTES)
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
