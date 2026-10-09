package tk.glucodata

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomRecoveryTests {
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/dex/java/tk/glucodata/DexGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    /**
     * Status 19 after a G7 slot must not re-dial at 0ms: that catches the dying
     * link as a phantom CONNECTED+19 and burns a second GATT object. Receive
     * time drives the quiet floor; sample time only feeds next-slot math.
     */
    @Test fun status19QuietDropNeverRedialsImmediately() {
        val dex = source("Common/src/dex/java/tk/glucodata/DexGattCallback.java")
            .replace(Regex("\\s+"), " ")

        assertTrue(dex.contains("datatime=timmsec"))
        assertTrue(dex.contains("sampletime=newtime"))
        assertFalse(dex.contains("datatime=newtime"))

        val getdata = dex.substring(dex.indexOf("private void getdata"))
        assertTrue(getdata.contains("datatime=timmsec"))

        val quiet = dex.substring(dex.indexOf("if(justdata)"))
        assertTrue(quiet.contains("minQuietMs"))
        assertTrue(quiet.contains("else if (reachedSensor)"))
        val reached = quiet.substring(quiet.indexOf("else if (reachedSensor)"))
        val direct = reached.indexOf("connect direct")
        assertTrue(reached.contains("scheduleDexReconnect(sensorbluetooth, tim, 5000L)"))
        assertTrue(direct > reached.indexOf("scheduleDexReconnect(sensorbluetooth, tim, 5000L)"))
    }

    @Test fun recoveryRetainsIdentityAndWakesForTheNextAdvert() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test-dexcom-connect.py").isFile }
        val output = File.createTempFile("dexcom-recovery-", ".log")
        try {
            val process = ProcessBuilder("python3", File(root, "tools/test-dexcom-connect.py").path)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("Dexcom recovery timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            output.delete()
        }
    }

    @Test fun nativeLifetimeAndScanPathsPreserveSensorRecords() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test-dexcom-native.py").isFile }
        val output = File.createTempFile("dexcom-native-", ".log")
        try {
            val process = ProcessBuilder("python3", File(root, "tools/test-dexcom-native.py").path)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("Dexcom native tests timed out: ${output.readText()}", finished)
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            output.delete()
        }
    }
}
