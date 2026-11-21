package com.chunkexpress.integration.compatibility;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mixin names used by the compatibility rules and the kill switch must be exactly the mixins we
 * ship, and each must sit in the right package: observe-only probes in {@code hook.probe},
 * behaviour-changing optimisations in {@code hook.optimization}.
 */
class MixinConfigTest {

    @Test
    void mixinConfigListsExactlyTheKnownMixinsInTheirPackages() throws IOException {
        String json;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("chunkexpress.mixins.json")) {
            assertNotNull(in, "chunkexpress.mixins.json not on the classpath");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher block = Pattern.compile("\"mixins\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(json);
        assertTrue(block.find());
        List<String> probes = new ArrayList<>();
        List<String> optimizations = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher entry = Pattern.compile("\"([a-z]+)\\.([A-Za-z0-9_]+)\"").matcher(block.group(1));
        while (entry.find()) {
            String pkg = entry.group(1);
            String name = entry.group(2);
            names.add(name);
            switch (pkg) {
                case "probe" -> probes.add(name);
                case "optimization" -> optimizations.add(name);
                default -> throw new AssertionError("mixin " + name + " is in unexpected package " + pkg);
            }
        }
        assertEquals(KnownMods.ALL_MIXINS, names);
        assertEquals(KnownMods.ALL_PROBES, probes);
        assertEquals(KnownMods.OPTIMIZATIONS, optimizations);
        assertTrue(json.contains("\"defaultRequire\": 0"), "hooks must never crash the game when a target moves");
    }
}
