package org.openglacier.glacierdb;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

/**
 * Driver tests against a mock og-core (framed MessagePack), plus one against a real ogd when GLACIERDB_TEST_ADDR is
 * set. Plain main() so that it runs with javac alone: {@code make test}.
 */
public final class DriverTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        run("MessagePack round trip", DriverTest::messagePackRoundTrip);
        run("query streams partial frames then complete, events skipped", DriverTest::queryStreams);
        run("server error keeps its code and the connection", DriverTest::serverError);
        run("multi-batch .ogid load keeps one connection and one identity.open", DriverTest::multiBatchLoad);
        run("the byte budget closes batches before the row ceiling", DriverTest::byteBudget);
        run("unsafe integers and bad collections are refused before sending", DriverTest::refusedBeforeSending);
        run("200k documents in frames split into small writes", DriverTest::manyDocuments);
        run("the timeout bounds the silence between frames", DriverTest::timeout);
        run("CSV quoting, CRLF and headers", DriverTest::csv);
        String address = System.getenv("GLACIERDB_TEST_ADDR");
        if (address != null && !address.isBlank()) run("live ogd round trip (" + address + ")", () -> live(address));
        else System.out.println("SKIP live ogd round trip (GLACIERDB_TEST_ADDR not set)");
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    interface Body { void run() throws Exception; }

    private static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("ok   " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    // --- mock server --------------------------------------------------------------------------------------------

    static byte[] frame(Map<String, ?> message) {
        byte[] payload = MessagePack.encode(message);
        byte[] out = new byte[4 + payload.length];
        out[0] = (byte) (payload.length >>> 24);
        out[1] = (byte) (payload.length >>> 16);
        out[2] = (byte) (payload.length >>> 8);
        out[3] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    static byte[] response(Object id, Map<String, ?> fields) {
        Map<String, Object> m = new LinkedHashMap<>(fields);
        m.put("version", 1);
        m.put("kind", "response");
        m.put("id", id);
        return frame(m);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> request) { return (Map<String, Object>) request.get("data"); }

    record Mock(int port, AtomicInteger connections) {}

    @SuppressWarnings("unchecked")
    static Mock mock(BiConsumer<OutputStream, Map<String, Object>> respond) throws IOException {
        ServerSocket server = new ServerSocket(0);
        AtomicInteger connections = new AtomicInteger();
        Thread accept = new Thread(() -> {
            while (true) {
                Socket s;
                try { s = server.accept(); } catch (IOException e) { return; }
                connections.incrementAndGet();
                Thread t = new Thread(() -> {
                    try (s) {
                        DataInputStream in = new DataInputStream(s.getInputStream());
                        OutputStream out = s.getOutputStream();
                        while (true) {
                            byte[] payload = new byte[in.readInt()];
                            in.readFully(payload);
                            respond.accept(out, (Map<String, Object>) MessagePack.decode(payload));
                        }
                    } catch (IOException ignored) {
                        // client gone
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        });
        accept.setDaemon(true);
        accept.start();
        return new Mock(server.getLocalPort(), connections);
    }

    static void write(OutputStream out, byte[] bytes) {
        try { out.write(bytes); out.flush(); } catch (IOException e) { throw new RuntimeException(e); }
    }

    static Client client(int port) { return Client.builder().port(port).build(); }

    // --- tests --------------------------------------------------------------------------------------------------

    static void messagePackRoundTrip() {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("query", "from café");
        x.put("n", -2L);
        x.put("big", 1L << 40);
        x.put("f", 2.5);
        x.put("ok", true);
        x.put("items", new ArrayList<>(java.util.Arrays.asList(1L, null, "x", "y".repeat(300))));
        check(MessagePack.decode(MessagePack.encode(x)).equals(x), "round trip");
    }

    static void queryStreams() throws Exception {
        Mock m = mock((out, r) -> {
            check(r.get("op").equals("query.execute") && data(r).get("query").equals("on users"), "request shape");
            Object id = r.get("id");
            write(out, response(id, Map.of("status", "partial", "data", Map.of("name", "Ada"))));
            write(out, frame(Map.of("v", 1, "kind", "event", "type", "noise", "payload", Map.of())));
            write(out, response(id, Map.of("status", "partial", "data", Map.of("name", "Linus", "n", 2.5))));
            write(out, response(id, Map.of("status", "complete", "statistics", Map.of("returned", 2))));
        });
        try (Client db = client(m.port())) {
            QueryResult r = db.query("on users");
            check(r.size() == 2 && r.get(1).get("name").equals("Linus") && r.get(1).get("n").equals(2.5), "documents");
            check(r.statistics().get("returned").equals(2L), "statistics");
        }
    }

    static void serverError() throws Exception {
        Mock m = mock((out, r) -> write(out, response(r.get("id"), data(r).get("query").equals("bad")
                ? Map.of("status", "error", "error", Map.of("code", "query.invalid", "message", "bad query"))
                : Map.of("status", "complete", "statistics", Map.of()))));
        try (Client db = client(m.port())) {
            try {
                db.query("bad");
                throw new AssertionError("no error");
            } catch (QueryException e) {
                check(e.code().equals("query.invalid") && e.getMessage().contains("bad query"), "code");
            }
            db.query("good");
            check(m.connections().get() == 1, "one connection");
        }
    }

    static void multiBatchLoad() throws Exception {
        AtomicInteger opens = new AtomicInteger(), queries = new AtomicInteger();
        Mock m = mock((out, r) -> {
            if (r.get("op").equals("identity.open")) {
                check(data(r).get("password").equals("secret"), "password");
                opens.incrementAndGet();
                write(out, response(r.get("id"), Map.of("status", "ok", "data", Map.of("authenticated", true))));
            } else {
                queries.incrementAndGet();
                String q = (String) data(r).get("query");
                check(q.startsWith("on items\n| load\n    | with merge\n    | chunk ["), "load query");
                int rows = q.split("\"payload\"", -1).length - 1;
                write(out, response(r.get("id"), Map.of("status", "complete", "statistics", Map.of("inserted", rows))));
            }
        });
        Path dir = Files.createTempDirectory("glacierdb-java-");
        Path ogid = Files.writeString(dir.resolve("x.ogid"), "encrypted-placeholder");
        try (Client db = Client.builder().port(m.port()).identity(ogid, "secret").build()) {
            List<Map<String, Object>> docs = IntStream.range(0, 7).mapToObj(i -> Map.<String, Object>of("i", i, "payload", "x".repeat(80))).toList();
            LoadResult result = db.load("items", docs, LoadOptions.defaults().chunkSize(2).maxRequestBytes(2048).requestHeadroom(256));
            check(result.rowsRead() == 7 && result.inserted() == 7, "rows " + result);
            check(queries.get() >= 4, "batches");
            check(m.connections().get() == 1 && opens.get() == 1, "one connection, one identity.open");
            check(db.isAuthenticated(), "authenticated");
        } finally {
            Files.deleteIfExists(ogid);
            Files.deleteIfExists(dir);
        }
    }

    static void byteBudget() throws Exception {
        Mock m = mock((out, r) -> {
            check(((String) data(r).get("query")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 4096, "request size");
            write(out, response(r.get("id"), Map.of("status", "complete", "statistics", Map.of())));
        });
        try (Client db = client(m.port())) {
            Iterator<Map<String, Object>> docs = IntStream.range(0, 50).mapToObj(i -> Map.<String, Object>of("i", i, "payload", "é".repeat(100))).iterator();
            LoadResult result = db.load("items", docs, LoadOptions.defaults().maxRequestBytes(4096).requestHeadroom(512));
            check(result.requestsSent() > 1 && result.inserted() == 50, "batches " + result);
        }
    }

    static void refusedBeforeSending() {
        Client db = client(1);
        try {
            db.load("items", List.of(Map.of("big", 1L << 60)), LoadOptions.defaults());
            throw new AssertionError("no error");
        } catch (LoadException e) {
            check(e.row() == 1, "row");
        }
        try {
            db.insert("bad name", Map.of());
            throw new AssertionError("no error");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    static void manyDocuments() throws Exception {
        int total = 200_000;
        Mock m = mock((out, r) -> {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < total; i++) all.writeBytes(response(r.get("id"), Map.of("status", "partial", "data", Map.of("i", i))));
            all.writeBytes(response(r.get("id"), Map.of("status", "complete", "statistics", Map.of("returned", total))));
            byte[] bytes = all.toByteArray();
            for (int o = 0; o < bytes.length; o += 1000) {
                try { out.write(bytes, o, Math.min(1000, bytes.length - o)); } catch (IOException e) { throw new RuntimeException(e); }
            }
        });
        try (Client db = client(m.port())) {
            long[] seen = {0};
            Map<String, Object> statistics = db.stream("on items", d -> {
                check(d.get("i").equals(seen[0]), "order");
                seen[0]++;
            });
            check(seen[0] == total && statistics.get("returned").equals((long) total), "all documents");
        }
    }

    static void timeout() throws Exception {
        Mock slow = mock((out, r) -> {
            for (int i = 0; i < 6; i++) {
                try { Thread.sleep(60); } catch (InterruptedException e) { return; }
                write(out, response(r.get("id"), Map.of("status", "partial", "data", Map.of("i", i))));
            }
            write(out, response(r.get("id"), Map.of("status", "complete", "statistics", Map.of())));
        });
        try (Client db = Client.builder().port(slow.port()).timeout(Duration.ofMillis(200)).build()) {
            check(db.query("on items").size() == 6, "slow but steady answer");
        }
        Mock silent = mock((out, r) -> {});
        try (Client db = Client.builder().port(silent.port()).timeout(Duration.ofMillis(100)).build()) {
            try {
                db.query("on items");
                throw new AssertionError("no timeout");
            } catch (ConnectionException e) {
                check(e.getMessage().contains("timed out") && !db.isConnected(), "timed out and closed");
            }
        }
    }

    static void csv() {
        List<List<String>> rows = new ArrayList<>();
        Csv.records(new StringReader("a,b\r\n\"x,1\",\"say \"\"hi\"\"\"\n\"multi\nline\",\n1,2"), ',').forEachRemaining(rows::add);
        check(rows.equals(List.of(List.of("a", "b"), List.of("x,1", "say \"hi\""), List.of("multi\nline", ""), List.of("1", "2"))), "records " + rows);
        Iterator<Map<String, Object>> docs = Csv.documents(new StringReader("name,city\nAda,Paris\n"), Csv.Options.defaults());
        check(docs.next().get("city").equals("Paris") && !docs.hasNext(), "documents");
    }

    static void live(String address) {
        try (Client db = Client.connect(address)) {
            String collection = "glacierdb_java_" + ProcessHandle.current().pid();
            Iterator<Map<String, Object>> docs = IntStream.range(0, 2500).mapToObj(i -> Map.<String, Object>of("i", i, "label", "row é " + i, "even", i % 2 == 0)).iterator();
            LoadResult loaded = db.load(collection, docs, LoadOptions.defaults().chunkSize(1000));
            check(loaded.inserted() == 2500 && loaded.requestsSent() == 3, "loaded " + loaded);
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("a", java.util.Arrays.asList(1, 2.5, null));
            db.insert(collection, Map.of("i", 9999, "nested", nested));
            QueryResult rows = db.query("on " + collection + " | where i >= 2498");
            check(rows.size() == 3, "rows " + rows.size());
            try {
                db.query("on " + collection + " | bogus");
                throw new AssertionError("no error");
            } catch (QueryException e) {
                check(e.code() != null, "code");
            }
            check(db.query("on " + collection + " | where i == 0").size() == 1, "connection still usable");
        }
    }
}
