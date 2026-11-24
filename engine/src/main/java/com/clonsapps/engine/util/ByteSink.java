package com.clonsapps.engine.util;

import java.util.Arrays;

/** Buffer byte yang dapat tumbuh dengan penulisan little-endian. */
public final class ByteSink {
    private byte[] buf;
    private int size;

    public ByteSink(int initial) {
        buf = new byte[Math.max(16, initial)];
    }

    private void ensure(int extra) {
        if (size + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + extra));
        }
    }

    public int size() {
        return size;
    }

    public void u8(int v) {
        ensure(1);
        buf[size++] = (byte) v;
    }

    public void u16(int v) {
        ensure(2);
        Le.put16(buf, size, v);
        size += 2;
    }

    public void i32(int v) {
        ensure(4);
        Le.put32(buf, size, v);
        size += 4;
    }

    public void bytes(byte[] b) {
        bytes(b, 0, b.length);
    }

    public void bytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, size, len);
        size += len;
    }

    public void zeros(int n) {
        ensure(n);
        size += n;
    }

    public void padTo4() {
        while ((size & 3) != 0) {
            u8(0);
        }
    }

    public void set32(int at, int v) {
        Le.put32(buf, at, v);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }
}
