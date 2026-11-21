package com.chunkexpress.integration.compatibility;

import com.chunkexpress.integration.compatibility.KnownMods.KnownMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides, from the installed mods and the operator's kill switch, which Chunk Express mixins may
 * run. Pure logic: callers pass the mod list in, so it is unit-tested without a game.
 *
 * <p>Runs twice: once very early, from the mixin config plugin (to decide which mixins are
 * applied at all), and again at server start for the status report. Both see the same mod list.
 */
public final class CompatibilityManager {
    /**
     * JVM property to switch probes off without editing files: {@code all}, or a comma-separated
     * list of mixin names from {@link KnownMods#ALL_MIXINS}. Checked when classes are transformed,
     * so it needs a restart. Example: {@code -Dchunkexpress.disableProbes=IOWorkerMixin}.
     */
    public static final String DISABLE_PROBES_PROPERTY = "chunkexpress.disableProbes";

    private CompatibilityManager() {
    }

    /**
     * @param installed      mod id to version string, for every loaded mod
     * @param disableSetting value of {@link #DISABLE_PROBES_PROPERTY}, or null
     */
    public static CompatibilityReport evaluate(Map<String, String> installed, String disableSetting) {
        List<CompatibilityReport.Detected> detected = new ArrayList<>();
        Map<String, String> disabled = new TreeMap<>();

        for (KnownMod mod : KnownMods.ALL) {
            String version = installed.get(mod.modId());
            if (version == null) {
                continue;
            }
            detected.add(new CompatibilityReport.Detected(mod, version));
            for (String probe : mod.disabledProbes()) {
                disabled.merge(probe, mod.displayName() + " " + version + " is installed",
                        (a, b) -> a + "; " + b);
            }
        }

        for (String probe : parseDisableSetting(disableSetting)) {
            disabled.merge(probe, "disabled by -D" + DISABLE_PROBES_PROPERTY, (a, b) -> a + "; " + b);
        }

        return new CompatibilityReport(List.copyOf(detected), Collections.unmodifiableMap(new LinkedHashMap<>(disabled)));
    }

    /** Unknown names are ignored (and reported by the caller), so a typo cannot crash startup. */
    static Set<String> parseDisableSetting(String setting) {
        if (setting == null || setting.isBlank()) {
            return Set.of();
        }
        if (setting.trim().toLowerCase(Locale.ROOT).equals("all")) {
            return Set.copyOf(KnownMods.ALL_MIXINS);
        }
        Set<String> result = new java.util.TreeSet<>();
        for (String part : setting.split(",")) {
            String name = part.trim();
            for (String probe : KnownMods.ALL_MIXINS) {
                if (probe.equalsIgnoreCase(name)) {
                    result.add(probe);
                }
            }
        }
        return result;
    }

    /** Names in the setting that match no probe, for a warning. */
    public static List<String> unknownProbeNames(String setting) {
        if (setting == null || setting.isBlank() || setting.trim().equalsIgnoreCase("all")) {
            return List.of();
        }
        List<String> unknown = new ArrayList<>();
        for (String part : setting.split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && KnownMods.ALL_MIXINS.stream().noneMatch(p -> p.equalsIgnoreCase(name))) {
                unknown.add(name);
            }
        }
        return unknown;
    }
}
