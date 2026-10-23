package org.openglacier.glacierdb;

import java.time.Duration;

/** Where a bulk load stands, reported after each batch. {@code totalRows} is null when unknown. */
public record LoadProgress(String collection, long batch, long rowsDone, Long totalRows, double rowsPerSecond, Duration elapsed) {}
