package org.openglacier.glacierdb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** An encrypted identity file returned by {@code identity.get} / {@code identity.renew}. {@code fileName} may be null. */
public record IdentityFile(byte[] bytes, String fileName) {
    /** Writes the file to {@code destination}, or inside it (under its own name) when it is a directory. */
    public Path save(Path destination) {
        Path target = Files.isDirectory(destination) ? destination.resolve(fileName != null ? fileName : "identity.ogid") : destination;
        try {
            return Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
