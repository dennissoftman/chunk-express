package com.chunkexpress.integration.compatibility;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard block lives in neoforge.mods.toml, where NeoForge enforces it before anything loads.
 * This keeps it in step with the {@link KnownMods} table.
 */
class ModsTomlTest {
    private static final Pattern DEPENDENCY = Pattern.compile(
            "\\[\\[dependencies\\.chunkexpress]](.*?)(?=\\[\\[|\\z)", Pattern.DOTALL);

    private static String modsToml() throws IOException {
        try (InputStream in = ModsTomlTest.class.getClassLoader().getResourceAsStream("META-INF/neoforge.mods.toml")) {
            assertNotNull(in, "generated neoforge.mods.toml not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyIncompatibleKnownModIsBlockedByNeoForgeAndNothingElseIs() throws IOException {
        Set<String> declared = new TreeSet<>();
        Matcher dependency = DEPENDENCY.matcher(modsToml());
        while (dependency.find()) {
            String body = dependency.group(1);
            if (!body.contains("type=\"incompatible\"")) {
                continue;
            }
            Matcher modId = Pattern.compile("modId=\"([a-z0-9_]+)\"").matcher(body);
            assertTrue(modId.find(), "incompatible dependency without a modId");
            declared.add(modId.group(1));
            Matcher reason = Pattern.compile("reason=\"([^\"]*)\"").matcher(body);
            assertTrue(reason.find(), modId.group(1) + " is blocked without a reason for the player");
            // FML formats the reason with MessageFormat, where ' and { are special.
            assertFalse(reason.group(1).contains("'") || reason.group(1).contains("{"),
                    modId.group(1) + ": reason must not contain ' or {");
            assertFalse(body.contains("versionRange"), modId.group(1) + " must be blocked in every version");
        }
        Set<String> expected = KnownMods.ALL.stream()
                .filter(m -> m.status() == CompatibilityStatus.INCOMPATIBLE)
                .map(KnownMods.KnownMod::modId)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, declared);
        assertTrue(declared.containsAll(Set.of("moonrise", "c2me", "c2me_base")));
    }
}
