package me.biquaternions.inputreplay.client.io;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

/**
 * Growable byte buffer with the primitive encodings of the .irr format: unsigned/zig-zag varints,
 * little-endian fixed-width numbers, length-prefixed strings and byte arrays.
 */
public final class Bin {
    private byte[] buf;
    private int len;

    public Bin() {
        this(64);
    }

    public Bin(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    private void ensure(int extra) {
        if (len + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
        }
    }

    public Bin u8(int v) {
        ensure(1);
        buf[len++] = (byte) v;
        return this;
    }

    public Bin bool(boolean v) {
        return u8(v ? 1 : 0);
    }

    public Bin uvarint(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[len++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[len++] = (byte) v;
        return this;
    }

    public Bin svarint(long v) {
        return uvarint((v << 1) ^ (v >> 63));
    }

    public Bin i32(int v) {
        ensure(4);
        buf[len++] = (byte) v;
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 24);
        return this;
    }

    public Bin i64(long v) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            buf[len++] = (byte) (v >>> (8 * i));
        }
        return this;
    }

    public Bin f32(float v) {
        return i32(Float.floatToRawIntBits(v));
    }

    public Bin f64(double v) {
        return i64(Double.doubleToRawLongBits(v));
    }

    public Bin str(String s) {
        if (s == null) s = "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return bytes(b, 0, b.length);
    }

    public Bin strs(Collection<String> list) {
        uvarint(list.size());
        for (String s : list) str(s);
        return this;
    }

    public Bin bytes(byte[] b) {
        return b == null ? uvarint(0) : bytes(b, 0, b.length);
    }

    public Bin bytes(byte[] b, int off, int n) {
        uvarint(n);
        raw(b, off, n);
        return this;
    }

    public Bin raw(byte[] b, int off, int n) {
        ensure(n);
        System.arraycopy(b, off, buf, len, n);
        len += n;
        return this;
    }

    public int length() {
        return len;
    }

    public byte[] array() {
        return buf;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    public void reset() {
        len = 0;
    }
}
