package com.chunkexpress.integration;

import com.chunkexpress.integration.compatibility.CompatibilityReport;
import com.chunkexpress.integration.compatibility.KnownMods;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happened to each Chunk Express mixin (probe or optimisation) at class-transformation time, as recorded by
 * {@code ChunkExpressMixinPlugin}, for {@code /chunkexpress status}.
 *
 * <p>"Applied" means Mixin merged the probe class into its target. Injectors use
 * {@code defaultRequire = 0}, so an injection point that another mod removed is skipped silently
 * rather than crashing the game; the game tests are what prove the injection points still match
 * vanilla. At run time, an applied probe that has recorded nothing after a while is a hint that
 * another mod bypasses the hooked method.
 *
 * <p>Thread safety: written during class loading, read from the server thread; concurrent map.
 */
public final class ProbeRegistry {
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static volatile CompatibilityReport earlyReport;

    private ProbeRegistry() {
    }

    public static void reportEvaluated(CompatibilityReport report) {
        earlyReport = report;
        for (String probe : KnownMods.ALL_MIXINS) {
            STATES.putIfAbsent(probe, State.PENDING);
        }
    }

    public static void decided(String probe, boolean apply) {
        STATES.put(probe, apply ? State.PENDING : State.DISABLED);
    }

    public static void applied(String probe) {
        STATES.put(probe, State.APPLIED);
    }

    /** The report the mixin plugin used, or null if the plugin never ran in this class loader. */
    public static CompatibilityReport earlyReport() {
        return earlyReport;
    }

    /** Probe name to state, in the order of {@link KnownMods#ALL_MIXINS}. */
    public static Map<String, State> states() {
        Map<String, State> ordered = new LinkedHashMap<>();
        for (String probe : KnownMods.ALL_MIXINS) {
            ordered.put(probe, STATES.getOrDefault(probe, State.UNKNOWN));
        }
        return ordered;
    }

    public enum State {
        /** Mixin merged the probe into its target class. */
        APPLIED,
        /** Allowed, but its target class has not been loaded yet. */
        PENDING,
        /** Switched off by compatibility rules or the kill switch. */
        DISABLED,
        /** The mixin plugin's records are not visible (should not happen; report it). */
        UNKNOWN
    }
}
