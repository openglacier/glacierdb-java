package org.openglacier.glacierdb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/** Writes documents as the JSON the query language embeds (insert, load). */
final class Json {
    /** Integers beyond ±(2^53 - 1) are refused by the protocol rather than rounded. */
    static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private Json() {}

    static String document(Object document) {
        if (!(document instanceof Map<?, ?>)) throw new IllegalArgumentException("document must be a Map");
        StringBuilder out = new StringBuilder(128);
        write(out, document);
        return out.toString();
    }

    private static void write(StringBuilder out, Object v) {
        if (v == null) { out.append("null"); return; }
        if (v instanceof Boolean b) { out.append(b); return; }
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) { out.append(safe(((Number) v).longValue())); return; }
        if (v instanceof BigInteger big) {
            if (big.bitLength() > 53) throw new IllegalArgumentException("integer " + big + " is outside ±(2^53 - 1)");
            out.append(safe(big.longValue()));
            return;
        }
        if (v instanceof BigDecimal d) { out.append(d.toString()); return; }
        if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) out.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d).append(".0");
            else out.append(d);
            return;
        }
        if (v instanceof byte[] bytes) { string(out, Base64.getEncoder().encodeToString(bytes)); return; }
        if (v instanceof Date date) { string(out, date.toInstant().toString()); return; }
        if (v instanceof TemporalAccessor || v instanceof CharSequence || v instanceof Character || v instanceof java.util.UUID) { string(out, v.toString()); return; }
        if (v instanceof Enum<?> e) { string(out, e.name()); return; }
        if (v instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
            return;
        }
        if (v instanceof Collection<?> items) { array(out, items); return; }
        if (v instanceof Object[] items) { array(out, java.util.Arrays.asList(items)); return; }
        throw new IllegalArgumentException("unsupported value type: " + v.getClass().getName());
    }

    private static long safe(long n) {
        if (n > MAX_SAFE_INTEGER || n < -MAX_SAFE_INTEGER) throw new IllegalArgumentException("integer " + n + " is outside ±(2^53 - 1)");
        return n;
    }

    private static void array(StringBuilder out, Collection<?> items) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) out.append(',');
            first = false;
            write(out, item);
        }
        out.append(']');
    }

    static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** UTF-8 size of {@code s}, without encoding it. */
    static int utf8Length(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c)) { n += 4; i++; }
            else n += 3;
        }
        return n;
    }
}
