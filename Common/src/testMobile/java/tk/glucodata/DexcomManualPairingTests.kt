package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomManualPairingTests {
    @Test
    fun `pairing code is exactly four ascii digits`() {
        assertTrue(DexcomManualPairing.isValidPairingCode("1234"))
        assertTrue(DexcomManualPairing.isValidPairingCode(" 0012 "))

        listOf(null, "", "123", "12345", "12 34", "12-34", "12A4", "１２３４").forEach { code ->
            assertFalse(code.orEmpty(), DexcomManualPairing.isValidPairingCode(code))
            assertNull(DexcomManualPairing.createScanPayload(code, 1L))
        }
    }

    @Test
    fun `manual payload matches native dexcom scan contract`() {
        val payload = DexcomManualPairing.createScanPayload("0012", 1_234_567_890L)

        requireNotNull(payload)
        assertEquals(55, payload.length)
        assertEquals("JUGGLUCO-MANUAL-G7:", payload.take(19))
        assertEquals("M00000KF12OI", payload.substring(19, 31))
        assertEquals("2400012", payload.takeLast(7))

        // makeDexComSensorindex() uses bytes 19..30 plus the PIN as its 16-byte identity.
        assertEquals("M00000KF12OI0012", payload.substring(19, 31) + payload.takeLast(4))
    }

    @Test
    fun `same pin receives a fresh sensor identity`() {
        val first = DexcomManualPairing.createScanPayload("1234", 10L)
        val second = DexcomManualPairing.createScanPayload("1234", 11L)

        requireNotNull(first)
        requireNotNull(second)
        assertNotEquals(first.substring(19, 31), second.substring(19, 31))
        assertEquals(first.takeLast(7), second.takeLast(7))
    }
}
