package org.openglacier.glacierdb;

/** The server refused a request: a stable {@link #code()} and a readable message. */
public class QueryException extends GlacierDBException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final Long requestId;

    public QueryException(String code, String message, Long requestId) {
        super((requestId != null ? "request " + requestId + ": " : "") + code + ": " + message);
        this.code = code;
        this.requestId = requestId;
    }

    public String code() { return code; }
    public Long requestId() { return requestId; }
}
