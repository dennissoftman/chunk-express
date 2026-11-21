package com.chunkexpress.bench;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ice-road ride: in a new world with a fixed seed, a boat crosses {@code length} blocks of
 * fresh terrain at full blue-ice speed, on a road laid just ahead of it at height 200, so every
 * chunk on the way has to be generated while the player watches. Records what a player feels:
 * <ul>
 *   <li><b>ride time</b> - the server moves the boat a steady 3.6 blocks per tick (blue-ice top
 *       speed, 72 blocks/s at 20 TPS), so a slow ride means slow ticks;</li>
 *   <li><b>server tick times</b>;</li>
 *   <li><b>chunk stalls</b> - the road needs the chunks 48 blocks ahead of the boat. If normal
 *       loading has not finished a chunk by then, the road builder asks for it synchronously and
 *       the server thread waits: that wait is a stall, counted and timed per chunk;</li>
 *   <li><b>frame rate and frame times</b> on the client;</li>
 *   <li><b>view distance actually loaded</b> - how many chunks ahead along the road the client
 *       has, compared with the render distance;</li>
 *   <li>garbage collection during the ride.</li>
 * </ul>
 * Results are appended to {@code chunkexpress-bench/iceroad.txt} (readable) and
 * {@code chunkexpress-bench/iceroad.csv} (one row per ride) in the game folder; then the game quits.
 *
 * <p>Based on an earlier ice-road benchmark by the same author, with the same seed, height, speed
 * and look-ahead so the numbers stay comparable.
 *
 * <p>Threads: road, boat and tick timing run on the integrated server thread; frame rate and the
 * loaded-ahead count on the client thread. The server side finishes first and hands its results to
 * the client thread, which adds its own and writes the report, so no list is read while another
 * thread writes it.
 */
public final class IceRoadBench {
    static final Logger LOG = LogUtils.getLogger();

    public static final String WORLD_FOLDER = "chunkexpress_iceroad";
    private static final int LENGTH = Integer.getInteger("chunkexpress.bench.iceroad", 0);
    private static final long SEED = Long.getLong("chunkexpress.bench.seed", 20261117L);
    private static final int RENDER_DISTANCE = Integer.getInteger("chunkexpress.bench.renderDistance", 12);
    /** Reopen the existing ride world instead of creating a new one (crash-recovery checks). */
    private static final boolean RESUME = Boolean.getBoolean("chunkexpress.bench.resume");
    private static final int WAIT = 100;
    /**
     * Pre-generation instead of a ride: "&lt;dimension&gt;@&lt;radius in chunks&gt;", e.g. "minecraft:the_nether@16".
     * Generates that square around chunk (1000, 1000), 16,000 blocks out, to FULL, then quits; far from
     * the overworld spawn chunks, because stage-hash files key chunks by position only. For stage-hash comparisons in any
     * dimension (docs/RELEASE_CRITERIA.md B2/B3); not a benchmark.
     */
    private static final String PREGEN = emptyToNull(System.getProperty("chunkexpress.bench.pregen"));
    /** World preset of the new world: normal, large_biomes, amplified, single_biome_surface. */
    private static final String PRESET = System.getProperty("chunkexpress.bench.preset", "normal");
    /** Server-thread time spent requesting chunks per tick while pre-generating. */
    private static final long PREGEN_BUDGET_NANOS = 45_000_000L;
    private static final int PREGEN_CENTRE = 1000;
    private static int pregenNext;
    private static int pregenTarget;
    /**
     * Pre-generation only: how many worlds to create one after another in this game session
     * (docs/RELEASE_CRITERIA.md B9: no state may leak from one singleplayer world to the next).
     */
    private static final int SESSIONS = Integer.getInteger("chunkexpress.bench.sessions", 1);
    private static int worldsDone;
    private static long pregenStart;
    /**
     * Benchmarks ride above almost all terrain. Showcase captures ride lower, nearer the ground and
     * under the clouds, so the edge of the terrain the client has received is in view (the server
     * work is the same at any height: the same chunks are generated in the same order).
     */
    private static final int ROAD_Y = Integer.getInteger("chunkexpress.bench.roadY",
            System.getProperty("chunkexpress.bench.capture", "").isBlank() ? 200 : 120);
    /** Full speed for a boat on blue ice, blocks per tick. */
    private static final double SPEED = 3.6;
    /** How far ahead of the boat the road is laid. */
    private static final int AHEAD = 48;
    /** The ride is called off after this many ticks (ten minutes at 20 TPS). */
    private static final int GIVE_UP = 12000;
    /** Send to clients, but no neighbour updates and no shape updates of neighbours. */
    private static final int ROAD_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Engines we name in the report; anything else is listed as an extra mod. */
    private static final Set<String> FRAMEWORK = Set.of("minecraft", "neoforge", BenchMod.MOD_ID);

    private static boolean started;

    // ---- server thread ----
    private static int serverTicks;
    private static int stage;
    private static int startX;
    private static int startZ;
    private static int laidTo;
    private static int lastRoadChunkX = Integer.MIN_VALUE;
    private static Boat boat;
    private static long rideStart;
    private static long tickStart;
    private static int rideTicks;
    private static final List<Long> tickNanos = new ArrayList<>();
    private static final List<Long> tickIntervalNanos = new ArrayList<>();
    private static int roadChunks;
    private static final List<Long> stallNanos = new ArrayList<>();
    /** Time spent laying the road, per tick: should be tiny unless a chunk had to be waited for. */
    private static final List<Long> roadWorkNanos = new ArrayList<>();
    private static int newChunks;
    private static final Map<String, long[]> gcAtStart = new LinkedHashMap<>();
    private static final List<String> chunkExpressStatus = new ArrayList<>();

    // ---- client thread ----
    private static volatile boolean riding;
    private static final List<Integer> fps = new ArrayList<>();
    private static final List<Integer> ahead = new ArrayList<>();
    private static final List<Long> frameNanos = new ArrayList<>();
    private static long lastFrame;

    // ---- showcase capture (client thread; off unless -Dchunkexpress.bench.capture=<label>) ----
    /**
     * Saves about 10 frames per second of the ride to chunkexpress-bench/capture/&lt;label&gt;/, named by
     * frame number and the player's x, so that art/make_showcase_gif.py can pair two rides by position.
     * Capturing costs time, so a capture ride is for showing, never a benchmark result.
     */
    private static final String CAPTURE = emptyToNull(System.getProperty("chunkexpress.bench.capture"));
    /** Degrees below the horizon: the ground ahead and the clouds above both in view. */
    private static final float CAPTURE_PITCH = 25.0F;
    private static int captureTicks;
    private static int captureIndex;
    private static boolean capturePending;

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOG.warn("Could not clear {}: {}", dir, e.toString());
        }
    }

    private static void captureFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        int x = minecraft.player.getBlockX();
        NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
        Path file = Path.of("chunkexpress-bench", "capture", CAPTURE, String.format(Locale.ROOT, "%05d_%d.png", captureIndex++, x));
        Util.ioPool().execute(() -> {
            try (image) {
                Files.createDirectories(file.getParent());
                image.writeToFile(file);
            } catch (IOException e) {
                LOG.warn("Could not save capture frame {}: {}", file, e.toString());
            }
        });
    }

    private IceRoadBench() {
    }

    static void register() {
        if (LENGTH <= 0) {
            return;
        }
        LOG.info("Ice-road benchmark armed: {} blocks, seed {}, render distance {}", LENGTH, SEED, RENDER_DISTANCE);
        // For scripts/kill-test.sh: which process to kill.
        try {
            Path folder = Path.of("chunkexpress-bench");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("game.pid"), Long.toString(ProcessHandle.current().pid()));
        } catch (IOException e) {
            LOG.warn("Could not write game.pid: {}", e.toString());
        }
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, IceRoadBench::onScreen);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, event -> {
            long now = System.nanoTime();
            // Start to start: includes server-thread work done between ticks (chunk tasks, packets),
            // which tick work alone does not show.
            if (stage == 1 && tickStart != 0) {
                tickIntervalNanos.add(now - tickStart);
            }
            tickStart = now;
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> onServerTick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> {
            if (stage == 1) {
                LOG.warn("Ice road: the game stopped {} blocks into the ride (window closed or quit from the menu?). "
                        + "No result recorded; run it again and leave the window alone until it quits by itself.",
                        boat == null ? 0 : (int) (boat.getX() - startX));
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onClientTick());
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, event -> {
            long now = System.nanoTime();
            if (riding && lastFrame != 0) {
                frameNanos.add(now - lastFrame);
            }
            lastFrame = now;
            if (capturePending) {
                capturePending = false;
                captureFrame();
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (riding && event.isNewChunk() && event.getLevel() instanceof ServerLevel) {
                newChunks++;
            }
        });
    }

    private static void onScreen(ScreenEvent.Init.Post event) {
        if (started || !(event.getScreen() instanceof TitleScreen title)) {
            return;
        }
        started = true;
        startWorld(title);
    }

    /** Creates (or with RESUME reopens) the benchmark world; {@code parent} is the screen to return to. */
    private static void startWorld(net.minecraft.client.gui.screens.Screen title) {
        if (CAPTURE != null) {
            deleteRecursively(Path.of("chunkexpress-bench", "capture", CAPTURE));
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.renderDistance().set(RENDER_DISTANCE);
        minecraft.options.simulationDistance().set(Math.min(RENDER_DISTANCE, 12));
        minecraft.options.pauseOnLostFocus = false;
        minecraft.options.save();
        if (RESUME) {
            // Reopen the existing ride world, e.g. after scripts/kill-test.sh killed the game mid-ride:
            // the ride then crosses terrain that was being written when the game died.
            LOG.info("Ice road: reopening the existing world {}", WORLD_FOLDER);
            minecraft.createWorldOpenFlows().openWorld(WORLD_FOLDER, () -> {
                LOG.error("Ice road: the existing world {} could not be opened", WORLD_FOLDER);
                minecraft.stop();
            });
            return;
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings("Chunk Express Ice Road", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                rules, WorldDataConfiguration.DEFAULT);
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> preset =
                net.minecraft.resources.ResourceKey.create(Registries.WORLD_PRESET,
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace(PRESET));
        LOG.info("Ice road: new world, seed {}, preset {}", SEED, preset.location());
        String folder = worldsDone == 0 ? WORLD_FOLDER : WORLD_FOLDER + "_" + (worldsDone + 1);
        LOG.info("Ice road: world {} of {} in this session ({})", worldsDone + 1, Math.max(1, SESSIONS), folder);
        minecraft.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(SEED, true, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(preset).value()
                        .createWorldDimensions(), title);
    }

    private static void onServerTick(MinecraftServer server) {
        long tick = System.nanoTime() - tickStart;
        if (stage == 2 || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        serverTicks++;
        if (PREGEN != null) {
            if (serverTicks >= WAIT) {
                pregenTick(server);
            }
            return;
        }
        if (stage == 0) {
            if (serverTicks >= WAIT) {
                setOff(server, level, player);
            }
            return;
        }
        if (rideTicks == 0) {
            rideStart = System.nanoTime();
            riding = true;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcAtStart.put(gc.getName(), new long[]{gc.getCollectionCount(), gc.getCollectionTime()});
            }
        } else {
            tickNanos.add(tick);
        }
        rideTicks++;
        if (rideTicks % 280 == 0) {
            LOG.info("Ice road: {} blocks in {} s, {} chunk stalls so far", (int) (boat.getX() - startX),
                    String.format(Locale.ROOT, "%.1f", (System.nanoTime() - rideStart) / 1e9), stallNanos.size());
        }
        // The road ahead first, then the boat moved on along it at full speed. The server carries it a
        // steady 3.6 blocks a tick, so the boat's own friction and bumps cannot slow the ride.
        layRoad(level, (int) boat.getX() + AHEAD);
        boat.setPos(boat.getX() + SPEED, ROAD_Y + 1.0, startZ + 0.5);
        boat.setDeltaMovement(Vec3.ZERO);
        boat.setYRot(-90.0F);
        boolean arrived = boat.getX() >= startX + LENGTH;
        if (arrived || rideTicks >= GIVE_UP || !player.isPassenger()) {
            riding = false;
            stage = 2;
            finishServerSide(server, arrived, player.isPassenger());
        }
    }

    private static void setOff(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        BlockPos spawn = level.getSharedSpawnPos();
        startX = spawn.getX();
        startZ = spawn.getZ();
        laidTo = startX - 4;
        layRoad(level, startX + AHEAD);
        // Chunks around spawn existed before the ride; only stalls during the ride count.
        roadChunks = 0;
        stallNanos.clear();
        roadWorkNanos.clear();
        boat = new Boat(level, startX + 0.5, ROAD_Y + 1.0, startZ + 0.5);
        boat.setYRot(-90.0F);
        level.addFreshEntity(boat);
        ArmorStand driver = new ArmorStand(EntityType.ARMOR_STAND, level);
        driver.setInvisible(true);
        driver.setPos(boat.position());
        level.addFreshEntity(driver);
        driver.startRiding(boat, true);
        player.teleportTo(level, startX + 0.5, ROAD_Y + 1.5, startZ + 0.5, -90.0F, 10.0F);
        // The player sits on the driver, not in the boat. Vanilla puts a boarding player first in a
        // vehicle's passenger list, which would make the player the boat's driver: the client would
        // then steer the boat too, fighting the server's steady push (and logging "moved wrongly!"
        // every tick). Stacked like this, the boat's driver stays the armour stand.
        player.startRiding(driver, true);
        runChunkExpressCommand(server, "chunkexpress reset", null);
        if (CAPTURE != null) {
            level.setDayTime(6000); // noon: no low sun in the camera
        }
        LOG.info("Ice road: off from {}, {} at height {}, {} blocks east", startX, startZ, ROAD_Y, LENGTH);
        stage = 1;
    }

    /**
     * Lays the road up to {@code toX}: blue ice five wide, a cobblestone kerb on each edge, three
     * blocks clear above. Each new chunk the road enters is checked first: if normal loading has not
     * made it ready yet, it is fetched synchronously here, and that wait is recorded as a stall.
     *
     * <p>Blocks are placed without neighbour updates ({@link #ROAD_FLAGS}). With neighbour updates,
     * a block at a chunk edge pokes the next chunk, which forces that chunk to load on the server
     * thread: the benchmark would disturb the very thing it measures, and those waits would not be
     * counted as stalls (seen in an early version, via Chunk Express' chunk-wait probe).
     */
    private static void layRoad(ServerLevel level, int toX) {
        long before = System.nanoTime();
        BlockState ice = Blocks.BLUE_ICE.defaultBlockState();
        BlockState kerb = Blocks.COBBLESTONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = laidTo + 1; x <= toX; x++) {
            if (x >> 4 != lastRoadChunkX) {
                lastRoadChunkX = x >> 4;
                awaitRoadChunks(level, lastRoadChunkX);
            }
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(at.set(x, ROAD_Y, startZ + dz), ice, ROAD_FLAGS);
                level.setBlock(at.set(x, ROAD_Y + 1, startZ + dz), Math.abs(dz) == 2 ? kerb : air, ROAD_FLAGS);
                level.setBlock(at.set(x, ROAD_Y + 2, startZ + dz), air, ROAD_FLAGS);
                level.setBlock(at.set(x, ROAD_Y + 3, startZ + dz), air, ROAD_FLAGS);
            }
        }
        laidTo = Math.max(laidTo, toX);
        roadWorkNanos.add(System.nanoTime() - before);
    }

    private static void awaitRoadChunks(ServerLevel level, int chunkX) {
        for (int chunkZ = (startZ - 2) >> 4; chunkZ <= (startZ + 2) >> 4; chunkZ++) {
            roadChunks++;
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                continue;
            }
            long before = System.nanoTime();
            level.getChunk(chunkX, chunkZ);
            stallNanos.add(System.nanoTime() - before);
        }
    }

    private static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        // the window is often behind others while this runs: a paused world would stop the boat
        minecraft.options.pauseOnLostFocus = false;
        if (!riding || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (CAPTURE != null && ++captureTicks % 2 == 0) {
            minecraft.options.hideGui = true;
            minecraft.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
            // Look east and down, so the terrain ahead and the edge of the loaded world are in view.
            minecraft.player.setYRot(-90.0F);
            minecraft.player.setXRot(CAPTURE_PITCH);
            capturePending = true; // grabbed after the next frame is drawn, ~10 per second
        }
        fps.add(minecraft.getFps());
        // how many chunks ahead, along the road, the client has the world loaded
        int cx = minecraft.player.getBlockX() >> 4;
        int cz = minecraft.player.getBlockZ() >> 4;
        int distance = minecraft.options.renderDistance().get();
        int loaded = 0;
        while (loaded < distance && minecraft.level.getChunkSource().hasChunk(cx + loaded + 1, cz)) {
            loaded++;
        }
        ahead.add(loaded);
    }

    /** Server thread: everything the server measured, frozen, then handed to the client thread. */
    private static void finishServerSide(MinecraftServer server, boolean arrived, boolean seated) {
        long rideNanos = System.nanoTime() - rideStart;
        runChunkExpressCommand(server, "chunkexpress status", chunkExpressStatus);
        Map<String, long[]> gcDuring = new LinkedHashMap<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long[] before = gcAtStart.getOrDefault(gc.getName(), new long[]{0, 0});
            gcDuring.put(gc.getName(), new long[]{gc.getCollectionCount() - before[0], gc.getCollectionTime() - before[1]});
        }
        ServerResult result = new ServerResult(arrived, seated, rideNanos, boat.getX() - startX, List.copyOf(tickNanos),
                List.copyOf(tickIntervalNanos), roadChunks, List.copyOf(stallNanos), List.copyOf(roadWorkNanos), newChunks,
                gcDuring, List.copyOf(chunkExpressStatus));
        Minecraft.getInstance().execute(() -> finishClientSide(result));
    }

    /** Client thread: adds frame and view-distance numbers, writes the report, quits. */
    private static void finishClientSide(ServerResult server) {
        Minecraft minecraft = Minecraft.getInstance();
        // Chunk Express' status says whether its optimisations were on; name that in the engine label so
        // A/B rides are told apart in the CSV.
        String engine = engine() + (RESUME ? " [reopened world]" : "") + (CAPTURE != null ? " [showcase capture: not a benchmark]" : "");
        if (!server.chunkExpressStatus().isEmpty()) {
            engine += server.chunkExpressStatus().get(0).contains("optimisations on") ? " (optimisations on)" : " (observe-only)";
        }
        Report report = Report.build(server, List.copyOf(fps), List.copyOf(ahead), List.copyOf(frameNanos),
                minecraft.options.renderDistance().get(), engine, LENGTH, SEED);
        LOG.info("Ice road done:\n{}", report.text());
        try {
            Path folder = Path.of("chunkexpress-bench");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("iceroad.txt"), report.text(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Path csv = folder.resolve("iceroad.csv");
            if (CAPTURE == null) { // capture rides are slowed by saving frames: never benchmark data
                if (!Files.exists(csv)) {
                    Files.writeString(csv, Report.CSV_HEADER + System.lineSeparator());
                }
                Files.writeString(csv, report.csvRow() + System.lineSeparator(), StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            LOG.warn("Could not write the ice-road report: {}", e.toString());
        }
        minecraft.stop();
    }

    /**
     * Generates the next chunks of the PREGEN squares within the tick budget; quits when all are done.
     * PREGEN lists one or more "&lt;dimension&gt;@&lt;radius&gt;" separated by ";". The n-th square (from 0)
     * is centred on chunk (1000 (n+1), 1000 (n+1)): away from spawn and from each other, because
     * stage-hash files key chunks by position only.
     */
    private static void pregenTick(MinecraftServer server) {
        String[] targets = PREGEN.split(";");
        if (pregenTarget >= targets.length) {
            return;
        }
        String target = targets[pregenTarget].trim();
        int at = target.lastIndexOf('@');
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(target.substring(0, at))));
        int radius = Integer.parseInt(target.substring(at + 1));
        int side = 2 * radius + 1;
        int centre = PREGEN_CENTRE * (pregenTarget + 1);
        if (level == null) {
            LOG.error("Pregen: no dimension {}", target.substring(0, at));
            stage = 2;
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
            return;
        }
        if (pregenNext == 0) {
            pregenStart = System.nanoTime();
            LOG.info("Pregen: {} chunks in {} (radius {}, centre chunk {}, {})", side * side, level.dimension().location(),
                    radius, centre, centre);
        }
        long until = System.nanoTime() + PREGEN_BUDGET_NANOS;
        while (pregenNext < side * side && System.nanoTime() < until) {
            level.getChunk(centre + pregenNext % side - radius, centre + pregenNext / side - radius,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            pregenNext++;
        }
        if (pregenNext >= side * side) {
            LOG.info("Pregen done: {} chunks in {} in {} s", side * side, level.dimension().location(),
                    (System.nanoTime() - pregenStart) / 1_000_000_000L);
            pregenNext = 0;
            pregenTarget++;
            if (pregenTarget >= targets.length) {
                stage = 2;
                worldsDone++;
                Minecraft minecraft = Minecraft.getInstance();
                if (worldsDone < SESSIONS) {
                    LOG.info("Pregen: world {} of {} done; leaving it for the next", worldsDone, SESSIONS);
                    minecraft.execute(() -> {
                        // Vanilla's "Save and Quit to Title" (PauseScreen.onDisconnect), then the next world.
                        minecraft.level.disconnect();
                        minecraft.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(
                                net.minecraft.network.chat.Component.literal("Saving world")));
                        TitleScreen title = new TitleScreen();
                        minecraft.setScreen(title);
                        stage = 0;
                        serverTicks = 0;
                        pregenNext = 0;
                        pregenTarget = 0;
                        startWorld(title);
                    });
                } else {
                    minecraft.execute(minecraft::stop);
                }
            }
        }
    }

    /** "vanilla", or the non-framework mods with versions, e.g. "chunkexpress 0.1.0". */
    private static String engine() {
        Set<String> mods = new TreeSet<>();
        for (IModInfo mod : ModList.get().getMods()) {
            if (!FRAMEWORK.contains(mod.getModId())) {
                mods.add(mod.getModId() + " " + mod.getVersion());
            }
        }
        return mods.isEmpty() ? "vanilla" : String.join(" + ", mods);
    }

    /**
     * When Chunk Express is present, resets its counters at the start and captures its status at the
     * end, through its command only, so this mod still has no compile-time tie to Chunk Express.
     */
    private static void runChunkExpressCommand(MinecraftServer server, String command, List<String> output) {
        if (!ModList.get().isLoaded("chunkexpress")) {
            return;
        }
        CommandSource collector = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                if (output != null) {
                    output.add(component.getString());
                }
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(collector), command);
    }

    record ServerResult(boolean arrived, boolean seated, long rideNanos, double travelled, List<Long> tickNanos,
                        List<Long> tickIntervalNanos, int roadChunks, List<Long> stallNanos, List<Long> roadWorkNanos,
                        int newChunks, Map<String, long[]> gc,
                        List<String> chunkExpressStatus) {
    }

    /** Pure arithmetic over the collected samples; kept separate so it is unit-testable. */
    record Report(String text, String csvRow) {
        static final String CSV_HEADER = String.join(",",
                "time", "engine", "length", "seed", "render_distance", "cores", "arrived",
                "ride_s", "blocks_per_s",
                "tick_avg_ms", "tick_p95_ms", "tick_p99_ms", "tick_max_ms", "ticks_over_50ms", "ticks",
                "interval_avg_ms", "interval_p95_ms", "interval_max_ms", "tps",
                "road_chunks", "stalled_chunks", "stall_total_ms", "stall_p95_ms", "stall_max_ms",
                "road_work_ms", "road_work_max_ms", "road_ticks_over_5ms", "new_chunks",
                "fps_avg", "fps_1pct_low", "frame_p99_ms", "frame_max_ms", "frames_over_50ms",
                "ahead_avg", "ahead_min", "ahead_under4_pct",
                "gc_count", "gc_ms", "heap_max_mb", "java");

        static Report build(ServerResult s, List<Integer> fps, List<Integer> ahead, List<Long> frames,
                            int renderDistance, String engine, int length, long seed) {
            double seconds = s.rideNanos() / 1e9;
            List<Long> ticks = sorted(s.tickNanos());
            List<Long> stalls = sorted(s.stallNanos());
            List<Long> frameTimes = sorted(frames);
            List<Integer> fpsSorted = new ArrayList<>(fps);
            Collections.sort(fpsSorted);

            double tickAvg = ms(ticks.stream().mapToLong(Long::longValue).average().orElse(0));
            double tickP95 = ms(at(ticks, 0.95));
            double tickP99 = ms(at(ticks, 0.99));
            double tickMax = ms(at(ticks, 1.0));
            long ticksOver = ticks.stream().filter(n -> n > 50_000_000L).count();
            List<Long> intervals = sorted(s.tickIntervalNanos());
            double intervalAvg = ms(intervals.stream().mapToLong(Long::longValue).average().orElse(0));
            double intervalP95 = ms(at(intervals, 0.95));
            double intervalMax = ms(at(intervals, 1.0));
            double tps = intervalAvg > 0 ? Math.min(20.0, 1000.0 / intervalAvg) : 0;
            double stallTotal = ms(stalls.stream().mapToLong(Long::longValue).sum());
            double stallP95 = ms(at(stalls, 0.95));
            double stallMax = ms(at(stalls, 1.0));
            List<Long> roadWork = sorted(s.roadWorkNanos());
            double roadTotal = ms(roadWork.stream().mapToLong(Long::longValue).sum());
            double roadMax = ms(at(roadWork, 1.0));
            long roadSlow = roadWork.stream().filter(n -> n > 5_000_000L).count();
            double fpsAvg = fpsSorted.stream().mapToInt(Integer::intValue).average().orElse(0);
            int fpsLow = fpsSorted.isEmpty() ? 0 : fpsSorted.get(fpsSorted.size() / 100);
            double frameP99 = ms(at(frameTimes, 0.99));
            double frameMax = ms(at(frameTimes, 1.0));
            long framesOver = frameTimes.stream().filter(n -> n > 50_000_000L).count();
            double aheadAvg = ahead.stream().mapToInt(Integer::intValue).average().orElse(0);
            int aheadMin = ahead.stream().mapToInt(Integer::intValue).min().orElse(0);
            double under4 = ahead.isEmpty() ? 0 : 100.0 * ahead.stream().filter(n -> n < 4).count() / ahead.size();
            long gcCount = s.gc().values().stream().mapToLong(v -> v[0]).sum();
            long gcMs = s.gc().values().stream().mapToLong(v -> v[1]).sum();
            int cores = Runtime.getRuntime().availableProcessors();
            long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            String java = System.getProperty("java.vendor") + " " + System.getProperty("java.version");
            String outcome = s.arrived() ? "" : s.seated() ? " (gave up)" : " (fell out of the boat)";
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT));

            StringBuilder gcText = new StringBuilder();
            s.gc().forEach((name, v) -> gcText.append(String.format(Locale.ROOT, "%s %d times, %d ms; ", name, v[0], v[1])));

            StringBuilder text = new StringBuilder();
            text.append(String.format(Locale.ROOT, "%s | %s | seed %d | render distance %d | %d cores%n",
                    time, engine, seed, renderDistance, cores));
            text.append(String.format(Locale.ROOT, "  ride: %.0f of %d blocks in %.1f s = %.1f blocks/s (full speed 72)%s%n",
                    s.travelled(), length, seconds, s.travelled() / seconds, outcome));
            text.append(String.format(Locale.ROOT, "  server ticks: average %.1f ms, p95 %.1f ms, p99 %.1f ms, worst %.0f ms, %d of %d over 50 ms%n",
                    tickAvg, tickP95, tickP99, tickMax, ticksOver, ticks.size()));
            text.append(String.format(Locale.ROOT, "  tick interval (start to start, includes work between ticks): average %.1f ms = %.1f TPS, p95 %.1f ms, worst %.0f ms%n",
                    intervalAvg, tps, intervalP95, intervalMax));
            text.append(String.format(Locale.ROOT, "  chunk stalls: %d of %d road chunks not ready in time, %.0f ms waited in all, p95 %.1f ms, worst %.0f ms%n",
                    stalls.size(), s.roadChunks(), stallTotal, stallP95, stallMax));
            text.append(String.format(Locale.ROOT, "  road building on the server thread: %.0f ms in all, worst tick %.1f ms, %d ticks over 5 ms%n",
                    roadTotal, roadMax, roadSlow));
            text.append(String.format(Locale.ROOT, "  new chunks generated during the ride: %d%n", s.newChunks()));
            text.append(String.format(Locale.ROOT, "  frames: average %.0f fps, 1%% low %d fps, p99 frame %.1f ms, worst %.0f ms, %d of %d over 50 ms%n",
                    fpsAvg, fpsLow, frameP99, frameMax, framesOver, frameTimes.size()));
            text.append(String.format(Locale.ROOT, "  world loaded ahead on screen: average %.1f of %d chunks, least %d, under 4 chunks %.0f%% of the time%n",
                    aheadAvg, renderDistance, aheadMin, under4));
            text.append(String.format(Locale.ROOT, "  garbage collection during the ride: %s%n", gcText));
            text.append(String.format(Locale.ROOT, "  java: %s, heap up to %d MB%n", java, heapMb));
            if (!s.chunkExpressStatus().isEmpty()) {
                text.append("  Chunk Express status at the end of the ride:").append(System.lineSeparator());
                s.chunkExpressStatus().forEach(line -> text.append("    ").append(line).append(System.lineSeparator()));
            }
            text.append(System.lineSeparator());

            String csv = String.join(",",
                    time, quote(engine), Integer.toString(length), Long.toString(seed), Integer.toString(renderDistance),
                    Integer.toString(cores), Boolean.toString(s.arrived()),
                    f(seconds), f(s.travelled() / seconds),
                    f(tickAvg), f(tickP95), f(tickP99), f(tickMax), Long.toString(ticksOver), Integer.toString(ticks.size()),
                    f(intervalAvg), f(intervalP95), f(intervalMax), f(tps),
                    Integer.toString(s.roadChunks()), Integer.toString(stalls.size()), f(stallTotal), f(stallP95), f(stallMax),
                    f(roadTotal), f(roadMax), Long.toString(roadSlow),
                    Integer.toString(s.newChunks()),
                    f(fpsAvg), Integer.toString(fpsLow), f(frameP99), f(frameMax), Long.toString(framesOver),
                    f(aheadAvg), Integer.toString(aheadMin), f(under4),
                    Long.toString(gcCount), Long.toString(gcMs), Long.toString(heapMb), quote(java));
            return new Report(text.toString(), csv);
        }

        private static List<Long> sorted(List<Long> values) {
            List<Long> copy = new ArrayList<>(values);
            Collections.sort(copy);
            return copy;
        }

        /** Nearest-rank percentile of a sorted list; 0 when empty. */
        static long at(List<Long> sorted, double quantile) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int index = (int) Math.ceil(quantile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        }

        private static double ms(double nanos) {
            return nanos / 1e6;
        }

        private static String f(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }

        private static String quote(String value) {
            return "\"" + value.replace("\"", "'") + "\"";
        }
    }
}
