package org.openglacier.glacierdb;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MessagePack subset the protocol uses. Decoded values: {@code null}, {@link Boolean}, {@link Long},
 * {@link Double}, {@link String}, {@code byte[]}, {@link List} and {@link Map} with string keys.
 */
public final class MessagePack {
    private MessagePack() {}

    public static byte[] encode(Object value) {
        Out out = new Out();
        write(out, value);
        return out.toByteArray();
    }

    public static Object decode(byte[] bytes) {
        In in = new In(bytes);
        Object value = in.read();
        if (in.offset != bytes.length) throw new ProtocolException("trailing MessagePack bytes");
        return value;
    }

    private static final class Out extends ByteArrayOutputStream {
        Out() { super(256); }
        void u8(int v) { write(v); }
        void u16(int v) { write(v >>> 8); write(v); }
        void u32(long v) { u16((int) (v >>> 16) & 0xffff); u16((int) v & 0xffff); }
        void u64(long v) { u32(v >>> 32); u32(v & 0xffffffffL); }
    }

    private static void write(Out out, Object v) {
        if (v == null) { out.u8(0xc0); return; }
        if (v instanceof Boolean b) { out.u8(b ? 0xc3 : 0xc2); return; }
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) { writeLong(out, ((Number) v).longValue()); return; }
        if (v instanceof BigInteger big) { writeLong(out, big.longValueExact()); return; }
        if (v instanceof Float || v instanceof Double || v instanceof BigDecimal) { out.u8(0xcb); out.u64(Double.doubleToLongBits(((Number) v).doubleValue())); return; }
        if (v instanceof CharSequence || v instanceof Character || v instanceof Enum<?>) {
            byte[] b = (v instanceof Enum<?> e ? e.name() : v.toString()).getBytes(StandardCharsets.UTF_8);
            int n = b.length;
            if (n <= 31) out.u8(0xa0 | n);
            else if (n <= 0xff) { out.u8(0xd9); out.u8(n); }
            else if (n <= 0xffff) { out.u8(0xda); out.u16(n); }
            else { out.u8(0xdb); out.u32(n); }
            out.write(b, 0, n);
            return;
        }
        if (v instanceof byte[] b) {
            int n = b.length;
            if (n <= 0xff) { out.u8(0xc4); out.u8(n); }
            else if (n <= 0xffff) { out.u8(0xc5); out.u16(n); }
            else { out.u8(0xc6); out.u32(n); }
            out.write(b, 0, n);
            return;
        }
        if (v instanceof Collection<?> items) {
            header(out, items.size(), 0x90, 0xdc, 0xdd);
            for (Object item : items) write(out, item);
            return;
        }
        if (v instanceof Object[] items) {
            header(out, items.length, 0x90, 0xdc, 0xdd);
            for (Object item : items) write(out, item);
            return;
        }
        if (v instanceof Map<?, ?> map) {
            header(out, map.size(), 0x80, 0xde, 0xdf);
            for (Map.Entry<?, ?> e : map.entrySet()) { write(out, String.valueOf(e.getKey())); write(out, e.getValue()); }
            return;
        }
        throw new IllegalArgumentException("unsupported MessagePack value: " + v.getClass().getName());
    }

    private static void header(Out out, int n, int fix, int m16, int m32) {
        if (n <= 15) out.u8(fix | n);
        else if (n <= 0xffff) { out.u8(m16); out.u16(n); }
        else { out.u8(m32); out.u32(n); }
    }

    private static void writeLong(Out out, long v) {
        if (v >= 0) {
            if (v <= 0x7f) out.u8((int) v);
            else if (v <= 0xff) { out.u8(0xcc); out.u8((int) v); }
            else if (v <= 0xffff) { out.u8(0xcd); out.u16((int) v); }
            else if (v <= 0xffffffffL) { out.u8(0xce); out.u32(v); }
            else { out.u8(0xcf); out.u64(v); }
        } else {
            if (v >= -32) out.u8((int) (v & 0xff));
            else if (v >= Byte.MIN_VALUE) { out.u8(0xd0); out.u8((int) v); }
            else if (v >= Short.MIN_VALUE) { out.u8(0xd1); out.u16((int) v); }
            else if (v >= Integer.MIN_VALUE) { out.u8(0xd2); out.u32(v); }
            else { out.u8(0xd3); out.u64(v); }
        }
    }

    private static final class In {
        final byte[] b;
        int offset;

        In(byte[] b) { this.b = b; }

        void need(int n) { if (n < 0 || offset + n > b.length) throw new ProtocolException("truncated MessagePack"); }
        int u8() { need(1); return b[offset++] & 0xff; }
        int u16() { return (u8() << 8) | u8(); }
        long u32() { return ((long) u16() << 16) | u16(); }
        long i64() { return (u32() << 32) | u32(); }
        int length32() { long n = u32(); if (n > Integer.MAX_VALUE) throw new ProtocolException("MessagePack length too large"); return (int) n; }

        String str(int n) { need(n); String s = new String(b, offset, n, StandardCharsets.UTF_8); offset += n; return s; }
        byte[] bin(int n) { need(n); byte[] x = java.util.Arrays.copyOfRange(b, offset, offset + n); offset += n; return x; }

        List<Object> array(int n) {
            List<Object> a = new ArrayList<>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) a.add(read());
            return a;
        }

        Map<String, Object> map(int n) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) { Object k = read(); m.put(String.valueOf(k), read()); }
            return m;
        }

        Object read() {
            int c = u8();
            if (c <= 0x7f) return (long) c;
            if (c >= 0xe0) return (long) (c - 256);
            if ((c & 0xe0) == 0xa0) return str(c & 0x1f);
            if ((c & 0xf0) == 0x90) return array(c & 0x0f);
            if ((c & 0xf0) == 0x80) return map(c & 0x0f);
            return switch (c) {
                case 0xc0 -> null;
                case 0xc2 -> false;
                case 0xc3 -> true;
                case 0xc4 -> bin(u8());
                case 0xc5 -> bin(u16());
                case 0xc6 -> bin(length32());
                case 0xca -> (double) Float.intBitsToFloat((int) u32());
                case 0xcb -> Double.longBitsToDouble(i64());
                case 0xcc -> (long) u8();
                case 0xcd -> (long) u16();
                case 0xce -> u32();
                case 0xcf -> { long x = i64(); if (x < 0) throw new ProtocolException("unsigned integer beyond 2^63"); yield x; }
                case 0xd0 -> (long) (byte) u8();
                case 0xd1 -> (long) (short) u16();
                case 0xd2 -> (long) (int) u32();
                case 0xd3 -> i64();
                case 0xd9 -> str(u8());
                case 0xda -> str(u16());
                case 0xdb -> str(length32());
                case 0xdc -> array(u16());
                case 0xdd -> array(length32());
                case 0xde -> map(u16());
                case 0xdf -> map(length32());
                default -> throw new ProtocolException(String.format("unsupported MessagePack marker 0x%02x", c));
            };
        }
    }
}
