package org.firstinspires.ftc.teamcode.dashboard;

/**
 * Base64 encoding for the websocket handshake. java.util.Base64 only exists from Android 8,
 * and the Control Hub runs Android 7.
 */
final class Base64Codec {
    private Base64Codec() { }

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String encode(byte[] data) {
        StringBuilder text = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            text.append(ALPHABET[b0 >>> 2]);
            text.append(ALPHABET[((b0 & 3) << 4) | (b1 >>> 4)]);
            text.append(i + 1 < data.length ? ALPHABET[((b1 & 15) << 2) | (b2 >>> 6)] : '=');
            text.append(i + 2 < data.length ? ALPHABET[b2 & 63] : '=');
        }
        return text.toString();
    }
}
