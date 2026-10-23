package org.openglacier.glacierdb;

/** The server sent something the protocol does not allow, or a request is too large. */
public class ProtocolException extends GlacierDBException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) { super(message); }
}
