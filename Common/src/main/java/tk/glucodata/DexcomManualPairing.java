package tk.glucodata;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Builds a scan-compatible payload for a manually entered Dexcom G7 pairing code. */
public final class DexcomManualPairing {
    private static final String MANUAL_PAYLOAD_PREFIX = "JUGGLUCO-MANUAL-G7:";
    private static final String MANUAL_PAYLOAD_PADDING = "00000000000000000";
    private static final String DEXCOM_PAIRING_MARKER = "240";
    private static final int PAIRING_CODE_LENGTH = 4;
    private static final AtomicLong lastPayloadNonce = new AtomicLong(System.currentTimeMillis());

    private DexcomManualPairing() {
    }

    public static String normalizePairingCode(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        return rawCode.trim();
    }

    public static boolean isValidPairingCode(String rawCode) {
        final String code = normalizePairingCode(rawCode);
        if (code.length() != PAIRING_CODE_LENGTH) {
            return false;
        }
        for (int index = 0; index < code.length(); index++) {
            final char character = code.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a payload accepted by the existing native QR parser, or {@code null} for invalid
     * input. The generated sensor ID is intentionally unique: a future sensor may legitimately
     * reuse the same four-digit PIN and must not reopen an older sensor record.
     */
    public static String createScanPayload(String rawCode) {
        final long nonce = lastPayloadNonce.updateAndGet(previous ->
                Math.max(System.currentTimeMillis(), previous + 1L));
        return createScanPayload(rawCode, nonce);
    }

    static String createScanPayload(String rawCode, long nonce) {
        final String code = normalizePairingCode(rawCode);
        if (!isValidPairingCode(code) || nonce < 0L) {
            return null;
        }

        final String encodedNonce = Long.toUnsignedString(nonce, 36).toUpperCase(Locale.ROOT);
        final String sensorId = ("00000000000" + encodedNonce);
        final String fixedSensorId = "M" + sensorId.substring(sensorId.length() - 11);
        return MANUAL_PAYLOAD_PREFIX
                + fixedSensorId
                + MANUAL_PAYLOAD_PADDING
                + DEXCOM_PAIRING_MARKER
                + code;
    }
}
