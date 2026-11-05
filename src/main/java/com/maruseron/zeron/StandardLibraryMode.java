package com.maruseron.zeron;

/**
 * How a compilation obtains the standard library.
 *
 * <p>{@link #DEFAULT} is used when a manifest does not select a mode. It prefers the compiled
 * standard-library artifact bundled with the installation and falls back to the bundled sources
 * when no artifact is available, which keeps source checkouts and tests working.
 */
enum StandardLibraryMode {
    DEFAULT,
    SOURCE,
    COMPILED
}
