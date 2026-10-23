package org.openglacier.glacierdb;

import java.util.function.Consumer;

/**
 * Bulk load settings. {@code chunkSize} is a row ceiling: a batch also closes earlier when its request would pass
 * {@code maxRequestBytes - requestHeadroom}.
 */
public final class LoadOptions {
    int chunkSize = 10_000;
    LoadMode mode = LoadMode.MERGE;
    int maxRequestBytes = Client.MAX_REQUEST_BYTES;
    int requestHeadroom = 8192;
    Long totalRows;
    Consumer<LoadProgress> progress;

    public static LoadOptions defaults() { return new LoadOptions(); }

    public LoadOptions chunkSize(int rows) { this.chunkSize = rows; return this; }
    public LoadOptions mode(LoadMode mode) { this.mode = mode; return this; }
    public LoadOptions maxRequestBytes(int bytes) { this.maxRequestBytes = bytes; return this; }
    public LoadOptions requestHeadroom(int bytes) { this.requestHeadroom = bytes; return this; }
    public LoadOptions totalRows(long rows) { this.totalRows = rows; return this; }
    public LoadOptions progress(Consumer<LoadProgress> callback) { this.progress = callback; return this; }
}
