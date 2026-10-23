package org.openglacier.glacierdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A connection to glacierdb ({@code ogd}): frames of a big-endian {@code u32} length followed by one MessagePack map
 * (see {@code protocol.md}). Query results stream one document per frame, encrypted {@code .ogid} identities are
 * opened by the Core, and bulk loads are cut into byte-bounded batches.
 *
 * <pre>{@code
 * try (Client db = Client.connect("127.0.0.1:7878")) {
 *     db.load("users", List.of(Map.of("name", "Ada", "age", 36)), LoadOptions.defaults());
 *     for (Map<String, Object> row : db.query("on users | where age > 30 | limit 10")) System.out.println(row);
 * }
 * }</pre>
 *
 * Methods are synchronized: one request at a time per connection.
 */
public final class Client implements AutoCloseable {
    public static final int PROTOCOL_VERSION = 1;
    /** Largest request the protocol accepts. */
    public static final int PROTOCOL_MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    /** Largest request a bulk load sends by default, as the other drivers do. */
    public static final int MAX_REQUEST_BYTES = 256 * 1024;
    /** Largest response frame accepted. */
    public static final int MAX_RESPONSE_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final String host;
    private final int port;
    private final Duration timeout;
    private final Duration connectTimeout;
    private final Path identity;
    private final String identityPassword;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private long nextRequestId = 1;
    private boolean authenticated;

    private Client(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.timeout = b.timeout;
        this.connectTimeout = b.connectTimeout;
        this.identity = b.identity;
        this.identityPassword = b.identityPassword;
    }

    /** Connects to {@code host:port} without authentication. */
    public static Client connect(String address) {
        return builder().address(address).connect();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Connection settings. */
    public static final class Builder {
        private String host = "127.0.0.1";
        private int port = 7878;
        private Duration timeout = Duration.ofSeconds(30);
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Path identity;
        private String identityPassword;

        public Builder host(String host) { this.host = Objects.requireNonNull(host); return this; }
        public Builder port(int port) { this.port = port; return this; }

        /** {@code host:port}. */
        public Builder address(String address) {
            int i = address.lastIndexOf(':');
            if (i < 1) throw new IllegalArgumentException("address must use the form 'host:port'");
            this.host = address.substring(0, i).replace("[", "").replace("]", "");
            this.port = Integer.parseInt(address.substring(i + 1));
            return this;
        }

        /** Longest silence allowed between two frames of a response (not the whole response). Zero or null waits forever. */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder connectTimeout(Duration timeout) { this.connectTimeout = Objects.requireNonNull(timeout); return this; }

        /** An encrypted {@code .ogid} identity, opened by the Core each time the connection is established. */
        public Builder identity(Path ogid, String password) {
            this.identity = Objects.requireNonNull(ogid);
            this.identityPassword = Objects.requireNonNull(password);
            return this;
        }

        /** A client that connects on first use. */
        public Client build() { return new Client(this); }

        public Client connect() {
            Client client = build();
            client.open();
            return client;
        }
    }

    public synchronized boolean isConnected() { return socket != null; }
    public synchronized boolean isAuthenticated() { return authenticated; }

    /** Opens the connection (and the identity) unless it is already open. */
    public synchronized Client open() {
        if (socket != null) return this;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
            s.setSoTimeout(timeout == null ? 0 : (int) Math.max(1, timeout.toMillis()));
            in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
        } catch (IOException e) {
            closeQuietly(s);
            throw new ConnectionException("cannot connect to ogd at " + host + ":" + port + ": " + e.getMessage(), e);
        }
        socket = s;
        authenticated = false;
        if (identity != null) {
            try {
                identityOpen(identity, identityPassword);
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }
        return this;
    }

    /** Closes the connection. The next call opens a new one. */
    @Override
    public synchronized void close() {
        Socket s = socket;
        socket = null;
        in = null;
        out = null;
        authenticated = false;
        closeQuietly(s);
    }

    /** Runs a query and gathers its documents. */
    public synchronized QueryResult execute(String query) {
        return executeWithLimit(query, MAX_REQUEST_BYTES);
    }

    /** Same as {@link #execute}. */
    public QueryResult query(String query) {
        return execute(query);
    }

    /**
     * Runs a query and hands each document to {@code onDocument} as it arrives, without gathering them. Returns the
     * statistics. If {@code onDocument} throws, the connection is closed (the rest of the answer cannot be skipped).
     */
    public synchronized Map<String, Object> stream(String query, Consumer<Map<String, Object>> onDocument) {
        checkQuery(query);
        long id = send("query.execute", Map.of("query", query), MAX_REQUEST_BYTES);
        boolean[] failed = {false};
        QueryEnd end;
        try {
            end = readQuery(id, d -> {
                try {
                    onDocument.accept(d);
                } catch (RuntimeException e) {
                    failed[0] = true;
                    throw e;
                }
            });
        } finally {
            if (failed[0]) close();
        }
        if (end.documents != null) end.documents.forEach(onDocument);
        return end.statistics;
    }

    /** Inserts one document. */
    public synchronized QueryResult insert(String collection, Map<String, ?> document) {
        checkCollection(collection);
        return execute("from " + collection + " | insert " + Json.document(document));
    }

    /** Bulk loads documents, keeping one connection (and one identity) for every batch. */
    public synchronized LoadResult load(String collection, Iterable<? extends Map<String, ?>> documents, LoadOptions options) {
        return load(collection, documents.iterator(), options);
    }

    /** Bulk loads documents from an iterator (a {@code Stream}'s {@code iterator()}, a cursor). */
    public synchronized LoadResult load(String collection, Iterator<? extends Map<String, ?>> documents, LoadOptions options) {
        checkCollection(collection);
        if (options.chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0");
        if (options.maxRequestBytes < 1024 || options.maxRequestBytes > PROTOCOL_MAX_REQUEST_BYTES)
            throw new IllegalArgumentException("maxRequestBytes must be between 1024 and " + PROTOCOL_MAX_REQUEST_BYTES);
        if (options.requestHeadroom < 0 || options.requestHeadroom >= options.maxRequestBytes)
            throw new IllegalArgumentException("requestHeadroom must be in [0, maxRequestBytes)");
        String mode = options.mode.wire();
        int documentBudget = options.maxRequestBytes - options.requestHeadroom - requestSize(loadQuery(collection, mode, "[]"));
        if (documentBudget <= 0) throw new IllegalArgumentException("requestHeadroom leaves no room for documents");

        long started = System.nanoTime();
        long rowsRead = 0, inserted = 0, replaced = 0, requests = 0, bytesSent = 0, firstRow = 1;
        StringBuilder chunk = new StringBuilder(Math.min(options.maxRequestBytes, 1 << 20)).append('[');
        int rowsInChunk = 0, chunkBytes = 0;
        while (true) {
            boolean more;
            String encoded = null;
            try {
                more = documents.hasNext();
                if (more) encoded = Json.document(documents.next());
            } catch (RuntimeException e) {
                throw new LoadException(collection, rowsRead + 1, e);
            }
            if (encoded != null) {
                int size = Json.utf8Length(encoded);
                if (size > documentBudget)
                    throw new LoadException(collection, rowsRead + 1, new ProtocolException("document is " + size + " bytes; the request budget is " + documentBudget));
                boolean full = rowsInChunk >= options.chunkSize || chunkBytes + 1 + size > documentBudget;
                if (rowsInChunk == 0 || !full) {
                    if (rowsInChunk > 0) { chunk.append(','); chunkBytes++; }
                    chunk.append(encoded);
                    chunkBytes += size;
                    rowsInChunk++;
                    rowsRead++;
                    continue;
                }
            }
            if (rowsInChunk > 0) {
                String query = loadQuery(collection, mode, chunk.append(']').toString());
                QueryResult answer;
                try {
                    answer = executeWithLimit(query, options.maxRequestBytes);
                } catch (GlacierDBException e) {
                    throw new LoadException(collection, firstRow, e);
                }
                inserted += number(answer.statistics().get("inserted"), rowsInChunk);
                replaced += number(answer.statistics().get("replaced"), 0);
                requests++;
                bytesSent += Json.utf8Length(query);
                if (options.progress != null) {
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                    double seconds = elapsed.toNanos() / 1e9;
                    options.progress.accept(new LoadProgress(collection, requests, rowsRead, options.totalRows, seconds > 0 ? rowsRead / seconds : 0, elapsed));
                }
                chunk.setLength(0);
                chunk.append('[');
                rowsInChunk = 0;
                chunkBytes = 0;
                firstRow = rowsRead + 1;
            }
            if (encoded == null) break;
            chunk.append(encoded);
            chunkBytes = Json.utf8Length(encoded);
            rowsInChunk = 1;
            rowsRead++;
        }
        return new LoadResult(collection, rowsRead, inserted, replaced, requests, bytesSent, Duration.ofNanos(System.nanoTime() - started));
    }

    /** Bulk loads a CSV file (values stay strings), streaming it from disk. */
    public synchronized LoadResult loadCsv(String collection, Path path, Csv.Options csv, LoadOptions options) {
        return load(collection, Csv.documents(path, csv), options);
    }

    /** Sends any operation ({@code files.list}, {@code events.subscribe}, {@code auth.begin}, ...) and returns its {@code data}. */
    public synchronized Map<String, Object> operation(String op, Map<String, ?> data) {
        if (op == null || op.isBlank()) throw new IllegalArgumentException("op must be a non-empty string");
        long id = send(op, data == null ? Map.of() : data, PROTOCOL_MAX_REQUEST_BYTES);
        Map<String, Object> message = readResponse(id);
        String status = String.valueOf(message.get("status"));
        if (status.equals("error")) throw remoteError(message, id);
        if (!status.equals("ok")) {
            close();
            throw new ProtocolException("unexpected operation status " + status);
        }
        Object value = message.get("data");
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?>)) {
            close();
            throw new ProtocolException("operation response data must be an object");
        }
        return cast(value);
    }

    /** Opens an encrypted {@code .ogid} identity: the Core decrypts it and authenticates this connection. */
    public synchronized void identityOpen(Path ogid, String password) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(ogid);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + ogid + ": " + e.getMessage(), e);
        }
        Map<String, Object> answer = operation("identity.open", Map.of("file", Base64.getEncoder().encodeToString(raw), "password", password));
        if (!Boolean.TRUE.equals(answer.get("authenticated"))) throw new ProtocolException("identity.open did not confirm authentication");
        authenticated = true;
    }

    /** A copy of the current identity, encrypted with {@code password}. */
    public synchronized IdentityFile identityGet(String password) {
        return identityFile(operation("identity.get", Map.of("password", password)));
    }

    /** Renews the current identity's key; returns the new file, encrypted with {@code password}. */
    public synchronized IdentityFile identityRenew(String password) {
        IdentityFile file = identityFile(operation("identity.renew", Map.of("password", password)));
        authenticated = true;
        return file;
    }

    // --- wire ---------------------------------------------------------------------------------------------------

    private record QueryEnd(Map<String, Object> statistics, List<Map<String, Object>> documents) {}

    private QueryResult executeWithLimit(String query, int maximumBytes) {
        checkQuery(query);
        long id = send("query.execute", Map.of("query", query), maximumBytes);
        List<Map<String, Object>> documents = new ArrayList<>();
        QueryEnd end = readQuery(id, documents::add);
        if (end.documents != null) documents = end.documents;
        return new QueryResult(Collections.unmodifiableList(documents), end.statistics, id);
    }

    /** A query's answer: {@code partial} frames then {@code complete}, or one {@code ok} frame carrying {@code documents}. */
    private QueryEnd readQuery(long id, Consumer<Map<String, Object>> onDocument) {
        while (true) {
            Map<String, Object> message = readResponse(id);
            String status = String.valueOf(message.get("status"));
            switch (status) {
                case "partial" -> {
                    if (!(message.get("data") instanceof Map<?, ?> data)) {
                        close();
                        throw new ProtocolException("partial query response data must be an object");
                    }
                    onDocument.accept(cast(data));
                }
                case "complete" -> {
                    return new QueryEnd(objectField(message, "statistics"), null);
                }
                case "ok" -> {
                    Map<String, Object> data = objectField(message, "data");
                    List<Map<String, Object>> documents = new ArrayList<>();
                    if (data.get("documents") instanceof List<?> items) for (Object d : items) if (d instanceof Map<?, ?> m) documents.add(cast(m));
                    return new QueryEnd(objectField(data, "statistics"), documents);
                }
                case "error" -> throw remoteError(message, id);
                default -> {
                    close();
                    throw new ProtocolException("unknown query response status " + status);
                }
            }
        }
    }

    private long send(String op, Map<String, ?> data, int maximumBytes) {
        open();
        long id = nextRequestId++;
        byte[] payload = encodeRequest(id, op, data);
        if (payload.length > maximumBytes) throw new ProtocolException("request is " + payload.length + " bytes; maximum is " + maximumBytes);
        try {
            out.write(new byte[] {(byte) (payload.length >>> 24), (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length});
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            close();
            throw new ConnectionException("communication with ogd failed: " + e.getMessage(), e);
        }
        return id;
    }

    /** The next response to request {@code id}, skipping pushed events. */
    private Map<String, Object> readResponse(long id) {
        while (true) {
            Map<String, Object> message;
            try {
                message = readMessage();
            } catch (SocketTimeoutException e) {
                close();
                throw new ConnectionException("request " + id + " timed out", e);
            } catch (EOFException e) {
                close();
                throw new ConnectionException("ogd closed the connection", e);
            } catch (IOException e) {
                close();
                throw new ConnectionException("communication with ogd failed: " + e.getMessage(), e);
            } catch (ProtocolException e) {
                close();
                throw e;
            }
            Object version = message.containsKey("version") ? message.get("version") : message.get("v");
            if (!(version instanceof Long v) || v != PROTOCOL_VERSION) {
                close();
                throw new ProtocolException("invalid protocol message/version");
            }
            Object kind = message.get("kind");
            if ("event".equals(kind)) continue;
            if (kind != null && !"response".equals(kind)) {
                close();
                throw new ProtocolException("unexpected message kind " + kind);
            }
            if (!(message.get("id") instanceof Long got) || got != id) {
                close();
                throw new ProtocolException("response id mismatch: expected " + id + ", got " + message.get("id"));
            }
            return message;
        }
    }

    private Map<String, Object> readMessage() throws IOException {
        if (in == null) throw new ConnectionException("not connected");
        int length = in.readInt();
        if (length <= 0 || length > MAX_RESPONSE_MESSAGE_BYTES)
            throw new ProtocolException("response payload length " + Integer.toUnsignedString(length) + " is outside the protocol limit");
        byte[] payload = new byte[length];
        in.readFully(payload);
        if (!(MessagePack.decode(payload) instanceof Map<?, ?> message)) throw new ProtocolException("a response must be a MessagePack map");
        return cast(message);
    }

    private int requestSize(String query) {
        return encodeRequest(nextRequestId, "query.execute", Map.of("query", query)).length;
    }

    private static byte[] encodeRequest(long id, String op, Map<String, ?> data) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", PROTOCOL_VERSION);
        request.put("id", id);
        request.put("op", op);
        request.put("data", data);
        return MessagePack.encode(request);
    }

    private static String loadQuery(String collection, String mode, String chunk) {
        return "on " + collection + "\n| load\n    | with " + mode + "\n    | chunk " + chunk + "\n| end";
    }

    private static void checkQuery(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must be a non-empty string");
    }

    private static void checkCollection(String collection) {
        if (collection == null || !COLLECTION.matcher(collection).matches())
            throw new IllegalArgumentException("collection must match [A-Za-z_][A-Za-z0-9_.-]*");
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static Map<String, Object> objectField(Map<String, Object> message, String key) {
        return message.get(key) instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
    }

    private static QueryException remoteError(Map<String, Object> message, long id) {
        Map<String, Object> detail = objectField(message, "error");
        if (detail.isEmpty()) detail = objectField(message, "data");
        Object code = detail.getOrDefault("code", message.getOrDefault("code", "query.error"));
        Object text = detail.getOrDefault("message", message.getOrDefault("message", "query failed"));
        return new QueryException(String.valueOf(code), String.valueOf(text), id);
    }

    private static IdentityFile identityFile(Map<String, Object> answer) {
        if (!(answer.get("file") instanceof String encoded) || encoded.isEmpty()) throw new ProtocolException("identity operation returned no encrypted file");
        return new IdentityFile(Base64.getDecoder().decode(encoded), answer.get("fileName") instanceof String name ? name : null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object map) {
        return (Map<String, Object>) map;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
