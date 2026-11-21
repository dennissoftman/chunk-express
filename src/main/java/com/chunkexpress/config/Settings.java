package com.chunkexpress.config;

/**
 * An immutable snapshot of the configuration, taken once when a server starts and kept for that
 * server's whole life. Editing the file while a world is open takes effect the next time a server
 * starts, so one server never runs under two different configurations.
 *
 * <p>Only options that do something exist. Options for later milestones are added with the code
 * that reads them, never earlier: an option that does nothing misleads the people tuning it.
 *
 * @param instrumentation      record metrics at all; off turns every probe and listener into a no-op
 * @param logSessionSummary    log a status summary when the server stops
 * @param slowWriteWarnMillis  warn when one region write takes longer than this (0 = never)
 * @param offloadSurface       run the "surface" generation stage on vanilla's worker pool instead of
 *                             the dimension's serial worldgen lane, where safe (see
 *                             {@code integration.generation.StageOffload}); false = vanilla behaviour
 * @param offloadCarvers       the same for the "carvers" stage
 * @param offloadStructureStarts the same for the "structure_starts" stage (O3)
 * @param fastFluidSettling    O4: answer fluid settling's reads of the chunk being settled from that chunk
 */
public record Settings(boolean instrumentation, boolean logSessionSummary, int slowWriteWarnMillis,
                       boolean offloadSurface, boolean offloadCarvers, boolean offloadStructureStarts,
                       boolean fastFluidSettling) {

    public static Settings defaults() {
        return new Settings(true, true, 10_000, true, true, true, true);
    }

    /** Whether any optimisation is switched on. */
    public boolean anyOptimisation() {
        return this.offloadSurface || this.offloadCarvers || this.offloadStructureStarts || this.fastFluidSettling;
    }
}
