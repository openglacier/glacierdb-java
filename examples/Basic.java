import java.nio.file.Path;
import java.util.Map;
import java.util.stream.IntStream;

import org.openglacier.glacierdb.Client;
import org.openglacier.glacierdb.LoadOptions;
import org.openglacier.glacierdb.LoadResult;
import org.openglacier.glacierdb.QueryResult;

/** make example ADDR=127.0.0.1:7878 [OGID=identity.ogid] (password in OGID_PASSWORD) */
public class Basic {
    public static void main(String[] args) {
        Client.Builder builder = Client.builder().address(args.length > 0 ? args[0] : "127.0.0.1:7878");
        if (args.length > 1) builder.identity(Path.of(args[1]), System.getenv().getOrDefault("OGID_PASSWORD", ""));
        try (Client db = builder.connect()) {
            var rows = IntStream.range(0, 1000).mapToObj(i -> Map.<String, Object>of("id", i, "city", i % 2 == 0 ? "Paris" : "Lyon")).iterator();
            LoadResult loaded = db.load("example_cities", rows, LoadOptions.defaults().progress(p -> System.err.println("batch " + p.batch() + ": " + p.rowsDone() + " rows")));
            System.out.println("loaded " + loaded.inserted() + " rows in " + loaded.elapsed().toMillis() + " ms");

            QueryResult result = db.query("on example_cities | where city == \"Lyon\" | limit 3");
            for (Map<String, Object> row : result) System.out.println(row);
            System.out.println("statistics: " + result.statistics().get("returned"));
        }
    }
}
