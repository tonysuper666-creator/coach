package com.coach.desktop.books.mobi;

/** Only the two bounded, unsigned big-endian reads the isolated decoder needs. */
final class EndianUtils {
    static int getUShortBE(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    static long getUIntBE(byte[] data, int offset) {
        return ((long) getUShortBE(data, offset) << 16) | getUShortBE(data, offset + 2);
    }
}
