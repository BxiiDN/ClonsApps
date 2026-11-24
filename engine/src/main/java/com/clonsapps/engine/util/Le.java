package com.clonsapps.engine.util;

/** Pembacaan/penulisan little-endian pada array byte. */
public final class Le {
    private Le() {
    }

    public static int u8(byte[] b, int o) {
        return b[o] & 0xFF;
    }

    public static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    public static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    public static long u32(byte[] b, int o) {
        return i32(b, o) & 0xFFFFFFFFL;
    }

    public static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }

    public static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    public static boolean inRange(byte[] b, long off, long len) {
        return off >= 0 && len >= 0 && off + len <= b.length;
    }
}
