package org.openglacier.glacierdb;

import java.time.Duration;

/** What a bulk load did. */
public record LoadResult(String collection, long rowsRead, long inserted, long replaced, long requestsSent, long bytesSent, Duration elapsed) {
    public double rowsPerSecond() {
        double seconds = elapsed.toNanos() / 1e9;
        return seconds > 0 ? rowsRead / seconds : 0;
    }
}
