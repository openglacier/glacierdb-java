package org.openglacier.glacierdb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** A small streaming CSV reader (RFC 4180 quoting). Values stay strings; convert them before loading if needed. */
public final class Csv {
    private Csv() {}

    /** How a CSV file is read: field separator, and whether the first record names the fields. */
    public record Options(char delimiter, boolean headers) {
        public static Options defaults() { return new Options(',', true); }
    }

    /** Documents of {@code path}, one per record, read lazily. Close the returned iterable's reader by draining it. */
    public static Iterable<Map<String, Object>> documents(Path path, Options options) {
        return () -> {
            try {
                return documents(Files.newBufferedReader(path, StandardCharsets.UTF_8), options);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    public static Iterator<Map<String, Object>> documents(Reader reader, Options options) {
        Iterator<List<String>> records = records(reader, options.delimiter());
        return new Iterator<>() {
            List<String> header;
            long record;

            @Override public boolean hasNext() {
                if (options.headers() && header == null && records.hasNext()) { header = records.next(); record++; }
                return records.hasNext();
            }

            @Override public Map<String, Object> next() {
                if (!hasNext()) throw new NoSuchElementException();
                List<String> row = records.next();
                record++;
                List<String> keys = header;
                if (keys == null) { keys = new ArrayList<>(); for (int i = 0; i < row.size(); i++) keys.add(Integer.toString(i)); }
                if (row.size() != keys.size()) throw new IllegalArgumentException("CSV record " + record + " has " + row.size() + " fields; expected " + keys.size());
                Map<String, Object> document = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) document.put(keys.get(i), row.get(i));
                return document;
            }
        };
    }

    public static Iterator<List<String>> records(Reader source, char delimiter) {
        BufferedReader reader = source instanceof BufferedReader b ? b : new BufferedReader(source, 256 * 1024);
        return new Iterator<>() {
            List<String> next;
            boolean done;

            @Override public boolean hasNext() {
                if (next == null && !done) next = read();
                return next != null;
            }

            @Override public List<String> next() {
                if (!hasNext()) throw new NoSuchElementException();
                List<String> row = next;
                next = null;
                return row;
            }

            private List<String> read() {
                List<String> row = new ArrayList<>();
                StringBuilder field = new StringBuilder();
                boolean quoted = false, pendingQuote = false, any = false;
                try {
                    int c;
                    while ((c = reader.read()) != -1) {
                        any = true;
                        if (quoted) {
                            if (pendingQuote) {
                                pendingQuote = false;
                                if (c == '"') { field.append('"'); continue; }
                                quoted = false;
                                if (c != delimiter && c != '\n' && c != '\r') throw new IllegalArgumentException("invalid character after closing CSV quote");
                            } else {
                                if (c == '"') pendingQuote = true; else field.append((char) c);
                                continue;
                            }
                        }
                        if (c == '"' && field.isEmpty()) quoted = true;
                        else if (c == delimiter) { row.add(field.toString()); field.setLength(0); }
                        else if (c == '\n') { row.add(field.toString()); return row; }
                        else if (c != '\r') field.append((char) c);
                    }
                    done = true;
                    reader.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (quoted && !pendingQuote) throw new IllegalArgumentException("unterminated CSV quoted field");
                if (!any || (field.isEmpty() && row.isEmpty())) return null;
                row.add(field.toString());
                return row;
            }
        };
    }
}
