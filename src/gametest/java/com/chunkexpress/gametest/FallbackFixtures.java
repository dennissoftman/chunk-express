package com.chunkexpress.gametest;

import com.chunkexpress.ChunkExpress;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Test-only content for docs/RELEASE_CRITERIA.md B5: mod-added types that the stage-offload gates
 * must catch in game. Lives in the gametest source set, so it never ships in the mod jar.
 *
 * <ul>
 *   <li>{@code chunkexpress:test_rule}, a surface rule type (wraps another rule), and
 *       {@code chunkexpress:test_carver}, a carver type (a plain cave carver). Both are used only by
 *       the dimension {@code chunkexpress:fixture_rules} (its noise settings and its one biome), so
 *       only that dimension's surface and carvers stages must stay on the lane.</li>
 *   <li>{@code chunkexpress:test_generator}, a subclass of the noise generator, used by the
 *       dimension {@code chunkexpress:fixture_generator}: not exactly vanilla's class, so it must stay
 *       on the lane.</li>
 * </ul>
 * The data (dimensions, biome, noise settings, configured carver) is in src/gametest/resources. The
 * game-test server ignores data-pack dimensions, so the check runs in a normal world:
 * scripts/fallback-test.sh (pre-generates both fixture dimensions and checks the logged decisions).
 */
@EventBusSubscriber(modid = ChunkExpress.MOD_ID)
public final class FallbackFixtures {
    private FallbackFixtures() {
    }

    @SubscribeEvent
    static void register(RegisterEvent event) {
        event.register(Registries.MATERIAL_RULE, helper -> helper.register(id("test_rule"), TestRule.CODEC.codec()));
        event.register(Registries.CARVER, helper -> helper.register(id("test_carver"), new CaveWorldCarver(CaveCarverConfiguration.CODEC)));
        event.register(Registries.CHUNK_GENERATOR, helper -> helper.register(id("test_generator"), TestGenerator.CODEC));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ChunkExpress.MOD_ID, path);
    }

    /**
     * A surface rule type that applies the wrapped rule unchanged: what matters is only that its type
     * is not minecraft's. SurfaceRules' Context and SurfaceRule types are protected, so the rule is a
     * dynamic proxy of the public RuleSource interface instead of a class naming them.
     */
    static final class TestRule {
        private static final java.util.Map<SurfaceRules.RuleSource, SurfaceRules.RuleSource> INNER =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        static final KeyDispatchDataCodec<SurfaceRules.RuleSource> CODEC = KeyDispatchDataCodec.of(
                SurfaceRules.RuleSource.CODEC.fieldOf("inner").xmap(TestRule::wrap, INNER::get));

        private TestRule() {
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static SurfaceRules.RuleSource wrap(SurfaceRules.RuleSource inner) {
            SurfaceRules.RuleSource proxy = (SurfaceRules.RuleSource) java.lang.reflect.Proxy.newProxyInstance(
                    FallbackFixtures.class.getClassLoader(), new Class<?>[]{SurfaceRules.RuleSource.class},
                    (self, method, args) -> switch (method.getName()) {
                        case "codec" -> CODEC;
                        case "apply" -> ((java.util.function.Function) inner).apply(args[0]);
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        case "toString" -> "TestRule[" + inner + "]";
                        default -> method.invoke(inner, args);
                    });
            INNER.put(proxy, inner);
            return proxy;
        }
    }

    /** Vanilla's noise generator under another class: a modded generator, as far as the gates know. */
    static final class TestGenerator extends NoiseBasedChunkGenerator {
        static final MapCodec<TestGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings)
        ).apply(instance, TestGenerator::new));

        TestGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
            super(biomeSource, settings);
        }

        @Override
        protected MapCodec<? extends ChunkGenerator> codec() {
            return CODEC;
        }
    }
}
