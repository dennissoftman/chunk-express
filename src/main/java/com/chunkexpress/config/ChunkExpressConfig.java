package com.chunkexpress.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/chunkexpress-common.toml}. A COMMON config, not a per-world SERVER config: the same
 * settings apply to every world, and nothing Chunk Express-specific is written into world folders.
 */
public final class ChunkExpressConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue INSTRUMENTATION;
    private static final ModConfigSpec.BooleanValue LOG_SESSION_SUMMARY;
    private static final ModConfigSpec.IntValue SLOW_WRITE_WARN_MILLIS;
    private static final ModConfigSpec.BooleanValue OFFLOAD_SURFACE;
    private static final ModConfigSpec.BooleanValue OFFLOAD_CARVERS;

    static {
        Settings defaults = Settings.defaults();
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Measurements only; these never change how Minecraft handles chunks.",
                        "Changes apply the next time a server (or singleplayer world) starts.")
                .push("instrumentation");
        INSTRUMENTATION = b
                .comment("Record chunk, storage and tick metrics. When false, Chunk Express' measuring hooks do nothing.")
                .define("enabled", defaults.instrumentation());
        LOG_SESSION_SUMMARY = b
                .comment("Write a status summary to the log when the server stops.")
                .define("logSessionSummary", defaults.logSessionSummary());
        SLOW_WRITE_WARN_MILLIS = b
                .comment("Warn when a single region-file write takes longer than this many milliseconds,",
                        "measured from submission to vanilla's I/O worker until the write returns",
                        "(so it includes time queued behind other writes). 0 disables the warning.")
                .defineInRange("slowWriteWarnMillis", defaults.slowWriteWarnMillis(), 0, 600_000);
        b.pop();

        b.comment("Changes to how chunks are generated. Each one falls back to vanilla behaviour on its own",
                        "when its safety conditions are not met, and says why in the log and in /chunkexpress status.",
                        "None changes the world format or what gets generated.",
                        "Changes apply the next time a server (or singleplayer world) starts.")
                .push("optimizations");
        OFFLOAD_SURFACE = b
                .comment("Run the 'surface' generation stage on Minecraft's worker threads, the way vanilla already",
                        "runs the 'noise' stage, instead of on each dimension's single generation lane.",
                        "Applies only to vanilla terrain generators whose surface rules use no mod-added types, and",
                        "where no other mod patches the surface code (patches reviewed as safe, such as",
                        "Lithostitched's, are allowed). false = exactly vanilla.")
                .define("offloadSurface", defaults.offloadSurface());
        OFFLOAD_CARVERS = b
                .comment("Run the 'carvers' generation stage (caves and canyons) on Minecraft's worker threads too.",
                        "Same conditions as offloadSurface, and additionally no mod-added carver types.",
                        "false = exactly vanilla.")
                .define("offloadCarvers", defaults.offloadCarvers());
        b.pop();

        SPEC = b.build();
    }

    private ChunkExpressConfig() {
    }

    /** The current values, or the defaults if the config file has not been loaded yet. */
    public static Settings snapshot() {
        if (!SPEC.isLoaded()) {
            return Settings.defaults();
        }
        return new Settings(INSTRUMENTATION.get(), LOG_SESSION_SUMMARY.get(), SLOW_WRITE_WARN_MILLIS.get(),
                OFFLOAD_SURFACE.get(), OFFLOAD_CARVERS.get());
    }
}
