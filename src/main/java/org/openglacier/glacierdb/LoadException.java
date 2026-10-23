package org.openglacier.glacierdb;

/** A bulk load stopped. {@link #row()} is the first row (1-based) of the batch that failed. */
public class LoadException extends GlacierDBException {
    private static final long serialVersionUID = 1L;

    private final String collection;
    private final long row;

    public LoadException(String collection, long row, Throwable cause) {
        super("cannot load batch (collection=\"" + collection + "\", row=" + row + "): " + cause.getMessage(), cause);
        this.collection = collection;
        this.row = row;
    }

    public String collection() { return collection; }
    public long row() { return row; }

    /** The server's error code when the batch was refused by the server, else null. */
    public String code() { return getCause() instanceof QueryException q ? q.code() : null; }
}
