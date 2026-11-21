package com.chunkexpress.integration.compatibility;

import com.chunkexpress.integration.compatibility.KnownMods.KnownMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The outcome of {@link CompatibilityManager#evaluate}: which known mods are installed and which
 * probes are off, with a reason for each.
 */
public record CompatibilityReport(List<Detected> detected, Map<String, String> disabledProbes) {

    public boolean probeAllowed(String probe) {
        return !this.disabledProbes.containsKey(probe);
    }

    public long count(CompatibilityStatus status) {
        return this.detected.stream().filter(d -> d.mod().status() == status).count();
    }

    /** Lines for the log and {@code /chunkexpress compat}. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (this.detected.isEmpty()) {
            lines.add("No mods with known Chunk Express interactions are installed.");
        }
        for (Detected d : this.detected) {
            lines.add(d.mod().displayName() + " " + d.version() + ": " + d.mod().status() + " - " + d.mod().note());
        }
        if (this.disabledProbes.isEmpty()) {
            lines.add("All probes allowed.");
        } else {
            this.disabledProbes.forEach((probe, reason) -> lines.add("Probe " + probe + " off: " + reason));
        }
        return lines;
    }

    public record Detected(KnownMod mod, String version) {
    }
}
