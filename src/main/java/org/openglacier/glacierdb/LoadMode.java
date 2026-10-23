package org.openglacier.glacierdb;

/** How loaded documents meet those already stored. */
public enum LoadMode {
    MERGE, REPLACE, UPDATE;

    String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
}
