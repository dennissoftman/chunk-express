package com.chunkexpress.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The one Chunk Express logger. Kept free of Minecraft types so plain unit tests can load it. */
public final class Logs {
    public static final Logger LOG = LoggerFactory.getLogger("ChunkExpress");

    private Logs() {
    }
}
