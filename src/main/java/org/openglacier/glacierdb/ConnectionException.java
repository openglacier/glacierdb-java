package org.openglacier.glacierdb;

/** The connection could not be opened, was lost, or a request timed out. The next call reconnects. */
public class ConnectionException extends GlacierDBException {
    private static final long serialVersionUID = 1L;

    public ConnectionException(String message) { super(message); }
    public ConnectionException(String message, Throwable cause) { super(message, cause); }
}
