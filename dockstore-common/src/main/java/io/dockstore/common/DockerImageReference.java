package io.dockstore.common;

/**
 * Describes whether a reference to a Docker image from a descriptor is a literal string, dynamic (parameterized), or we don't know
 */
public enum DockerImageReference {
    LITERAL,
    DYNAMIC,
    UNKNOWN
}
