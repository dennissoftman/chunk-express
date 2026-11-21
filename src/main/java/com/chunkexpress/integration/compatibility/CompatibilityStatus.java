package com.chunkexpress.integration.compatibility;

/**
 * How much we know about running Chunk Express next to another mod.
 *
 * <p>A status may only be raised with evidence: {@link #VERIFIED} needs a named, repeatable test
 * scenario in docs/COMPATIBILITY.md that passed against a pinned version of the other mod.
 */
public enum CompatibilityStatus {
    /** Passed the documented test scenarios against a pinned version. */
    VERIFIED,
    /** Reported working, or no shared code paths, but not covered by our own test scenarios. */
    COMPATIBLE,
    /** Not tested. Chunk Express uses its conservative behaviour. */
    UNKNOWN,
    /**
     * Replaces code Chunk Express hooks. The affected Chunk Express probes or features are switched off
     * and the reason logged; Chunk Express never tries to force its own behaviour over such a mod.
     */
    CONFLICTING,
    /**
     * Must never run alongside Chunk Express. Declared {@code type="incompatible"} in
     * neoforge.mods.toml, so NeoForge refuses to start and tells the player why. The probe
     * rules for these mods stay as a second line of defence.
     */
    INCOMPATIBLE
}
