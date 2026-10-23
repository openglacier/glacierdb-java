# glacierdb Java driver

Zero-dependency Java 17+ driver for `ogd`. One persistent TCP connection speaking the OG protocol (a big-endian `u32`
length, then one MessagePack map; see `protocol.md` at the root of the repository), streamed query results,
core-managed encrypted `.ogid` identities and byte-bounded bulk loading. The MessagePack codec, the JSON writer and a
streaming CSV reader are included.

```java
import org.openglacier.glacierdb.*;

try (Client db = Client.builder()
        .address("127.0.0.1:7878")
        .identity(Path.of("/home/me/my.ogid"), System.getenv("OGID_PASSWORD"))
        .connect()) {
    QueryResult rows = db.query("on users | limit 10");
    for (Map<String, Object> row : rows) System.out.println(row);
    System.out.println(rows.statistics());

    db.load("events", documents, LoadOptions.defaults().chunkSize(10_000));
}
```

Decoded values are `null`, `Boolean`, `Long`, `Double`, `String`, `byte[]`, `List` and `Map<String, Object>`.

## Streaming

`execute`/`query` gather the documents. `stream` hands them over one by one as the frames arrive:

```java
Map<String, Object> statistics = db.stream("on events", document -> System.out.println(document));
```

## Bulk loading

```java
LoadResult result = db.load("events", documents.iterator(), LoadOptions.defaults()
        .chunkSize(100_000)
        .mode(LoadMode.REPLACE)
        .progress(p -> System.err.printf("%d rows, %.0f rows/s%n", p.rowsDone(), p.rowsPerSecond())));

db.loadCsv("events", Path.of("events.csv"), Csv.Options.defaults(), LoadOptions.defaults());
```

`load` takes an `Iterable` or an `Iterator` (`stream.iterator()`), so the source is never held in memory.
`chunkSize` is a row ceiling; like the other drivers, a batch closes earlier when its request approaches
`maxRequestBytes` (256 KiB by default). A load never reconnects between batches, so an `.ogid` is opened once. CSV
values stay strings. Integers beyond ±(2^53 - 1) are refused before sending, as the protocol requires.

## Connection behavior

`timeout` (30 s by default) bounds the silence between two frames of a response, not the whole response. After a
timeout or a lost connection, the client closes the socket and the next call reconnects (and opens the identity
again). Methods are synchronized: one request at a time per `Client`.

Exceptions are unchecked and share `GlacierDBException`: `ConnectionException`, `ProtocolException`,
`QueryException` (with the server's `code()`) and `LoadException` (with the failing batch's first `row()`).

## Identity operations

```java
db.identityOpen(Path.of("identity.ogid"), password);
db.identityGet("new-password").save(Path.of("copy.ogid"));
db.identityRenew("new-password").save(Path.of("renewed.ogid"));
```

Any other operation goes through `db.operation("files.list", Map.of())`.

## Build and tests

No Maven or Gradle needed: the Makefile uses `javac` and `jar` (a `pom.xml` is there for Maven users).

```sh
make build                                     # build/glacierdb-dev.jar
make test                                      # mock server tests
GLACIERDB_TEST_ADDR=127.0.0.1:7878 make test   # also the live test against a running ogd
make example ADDR=127.0.0.1:7878
```
