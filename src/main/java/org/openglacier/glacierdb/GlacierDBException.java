package org.openglacier.glacierdb;

/** Base of every exception the driver throws. */
public class GlacierDBException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GlacierDBException(String message) { super(message); }
    public GlacierDBException(String message, Throwable cause) { super(message, cause); }
}
