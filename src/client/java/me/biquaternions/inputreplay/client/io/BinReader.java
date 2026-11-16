package me.biquaternions.inputreplay.client.io;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Cursor over a byte array with the decodings matching {@link Bin}. */
public final class BinReader {
    private final byte[] b;
    private int p;
    private final int end;

    public BinReader(byte[] b, int off, int end) {
        this.b = b;
        this.p = off;
        this.end = end;
    }

    public BinReader(byte[] b) {
        this(b, 0, b.length);
    }

    public int pos() {
        return p;
    }

    public void pos(int pos) {
        p = pos;
    }

    public int remaining() {
        return end - p;
    }

    public int u8() {
        if (p >= end) throw new IndexOutOfBoundsException();
        return b[p++] & 0xFF;
    }

    public boolean bool() {
        return u8() != 0;
    }

    public long uvarint() {
        long result = 0;
        int shift = 0;
        while (true) {
            int x = u8();
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IllegalStateException("varint too long");
        }
    }

    public long svarint() {
        long v = uvarint();
        return (v >>> 1) ^ -(v & 1);
    }

    public int i32() {
        if (p + 4 > end) throw new IndexOutOfBoundsException();
        int v = (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
        p += 4;
        return v;
    }

    public long i64() {
        long lo = i32() & 0xFFFFFFFFL;
        long hi = i32() & 0xFFFFFFFFL;
        return lo | hi << 32;
    }

    public float f32() {
        return Float.intBitsToFloat(i32());
    }

    public double f64() {
        return Double.longBitsToDouble(i64());
    }

    public byte[] bytes() {
        int n = (int) uvarint();
        if (p + n > end) throw new IndexOutOfBoundsException();
        byte[] out = new byte[n];
        System.arraycopy(b, p, out, 0, n);
        p += n;
        return out;
    }

    public void skipBytes() {
        int n = (int) uvarint();
        if (p + n > end) throw new IndexOutOfBoundsException();
        p += n;
    }

    public String str() {
        int n = (int) uvarint();
        if (p + n > end) throw new IndexOutOfBoundsException();
        String s = new String(b, p, n, StandardCharsets.UTF_8);
        p += n;
        return s;
    }

    public List<String> strs() {
        int n = (int) uvarint();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(str());
        return out;
    }
}
