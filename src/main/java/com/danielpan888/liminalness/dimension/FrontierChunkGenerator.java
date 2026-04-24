package com.danielpan888.liminalness.dimension;

import com.danielpan888.liminalness.liminalness;
import com.danielpan888.liminalness.util.ChestLootHandler;
import com.danielpan888.liminalness.util.DimensionConfig;
import com.danielpan888.liminalness.util.RoomSpatialIndex;
import com.danielpan888.liminalness.util.SchematicLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class FrontierChunkGenerator extends ChunkGenerator {

    public volatile ServerLevel serverLevel;
    public volatile boolean running = false;
    public boolean needsSeed = false;
    public long worldSeed;
    public boolean initialized = false;
    public volatile BlockPos startingRoomOrigin;
    public volatile ResourceLocation dimensionId;

    public final Set<BlockPos> portalPositions = ConcurrentHashMap.newKeySet();
    public final Set<BlockPos> chestPositions = ConcurrentHashMap.newKeySet();

    public final Set<BlockPos> consumedChests = ConcurrentHashMap.newKeySet();

    public final Map<BlockPos, SchematicLoader.Schematic> roomOrigins = new ConcurrentHashMap<>();
    public final Set<BlockPos> claimed = ConcurrentHashMap.newKeySet();
    public final ArrayDeque<FrontierEntry> frontiers = new ArrayDeque<>();
    public final Map<SchematicLoader.Schematic, int[]> extentsCache = new ConcurrentHashMap<>();

    public final Set<Long> committedChunks = ConcurrentHashMap.newKeySet();
    public final Set<Long> pendingChunks = ConcurrentHashMap.newKeySet();

    public final RoomSpatialIndex spatialIndex = new RoomSpatialIndex();

    private final Map<Long, List<SchematicLoader.Schematic>> candidateIndex = new HashMap<>();
    private final Map<Long, int[]> candidateCumulativeWeights = new HashMap<>();

    public final Set<BlockPos> persistedRooms = ConcurrentHashMap.newKeySet();
    public final Set<Long> stalePatchedChunks = ConcurrentHashMap.newKeySet();

    private final Map<SchematicLoader.Schematic, String> schematicPaths = new HashMap<>();
    private final Map<String, SchematicLoader.Schematic> pathToSchematic = new HashMap<>();
    private final Map<SchematicLoader.Schematic, Integer> schematicWeights = new HashMap<>();
    protected List<SchematicLoader.Schematic> schematics = new ArrayList<>();
    protected List<SchematicLoader.Schematic> weightedPool = new ArrayList<>();
    protected List<SchematicLoader.Schematic> spawnPool = new ArrayList<>();

    // default if no config
    public int generationY      = 20;
    public int minGenerationY   = 0;
    public int maxGenerationY   = 512;
    public int radiusHorizontal = 256;
    public int radiusVertical   = 64;
    public int stepsPerTick     = 10;
    public int minRooms         = 100;
    public BlockState fillSpaceState = Blocks.SMOOTH_SANDSTONE.defaultBlockState();

    public record FrontierEntry(
            BlockPos attachPoint,
            Direction incomingFacing,
            int width,
            int height,
            Block markerBlock
    ) {}

    private record PlacementOption(
            SchematicLoader.Schematic candidate,
            BlockPos origin
    ) {}

    public FrontierChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public ResourceLocation getDimensionId() {
        if (dimensionId != null) {
            return dimensionId;
        }
        if (serverLevel != null) {
            return serverLevel.dimension().location();
        }
        return ResourceLocation.fromNamespaceAndPath(liminalness.MODID, "unknown");
    }

    // --- generation ---

    public void initialize(DimensionConfig dimensionConfig, long seed) {

        this.running = false;
        this.roomOrigins.clear();
        this.claimed.clear();
        this.frontiers.clear();
        this.extentsCache.clear();
        this.schematicPaths.clear();
        this.pathToSchematic.clear();
        this.schematicWeights.clear();
        this.schematics.clear();
        this.weightedPool.clear();
        this.spawnPool.clear();
        this.spatialIndex.clear();
        this.startingRoomOrigin = null;

        candidateIndex.clear();
        candidateCumulativeWeights.clear();

        worldSeed        = seed;
        generationY      = dimensionConfig.generationY();
        minGenerationY   = dimensionConfig.minY();
        maxGenerationY   = dimensionConfig.maxY();
        fillSpaceState   = resolveFillSpace(dimensionConfig.fillSpace());
        radiusHorizontal = dimensionConfig.generationRadiusHorizontal();
        radiusVertical   = dimensionConfig.generationRadiusVertical();
        stepsPerTick     = dimensionConfig.stepsPerTick();
        minRooms         = dimensionConfig.minRooms();

        for (DimensionConfig.SchematicEntry entry : dimensionConfig.schematics()) {
            List<Map.Entry<String, SchematicLoader.Schematic>> variants =
                    SchematicLoader.createHorizontalVariants(entry.path(), entry.schematic());

            boolean firstVariant = true;
            for (Map.Entry<String, SchematicLoader.Schematic> variant : variants) {
                SchematicLoader.Schematic schematic = variant.getValue();
                schematics.add(schematic);
                schematicPaths.put(schematic, variant.getKey());
                pathToSchematic.put(variant.getKey(), schematic);
                schematicWeights.put(schematic, entry.weight());
                if (firstVariant) {
                    pathToSchematic.put(entry.path(), schematic);
                    firstVariant = false;
                }

                if (entry.weight() == 0) {
                    continue;
                }

                if (entry.weight() == 1) {
                    spawnPool.add(schematic);
                }

                for (int i = 0; i < entry.weight(); i++) {
                    this.weightedPool.add(schematic);
                }
            }
        }

        spatialIndex.clear();
        for (var entry : roomOrigins.entrySet()) {
            spatialIndex.add(entry.getKey(), getExtents(entry.getValue()));
        }

        Map<Long, Map<SchematicLoader.Schematic, Integer>> uniqueByKey = new HashMap<>();

        for (DimensionConfig.SchematicEntry entry : dimensionConfig.schematics()) {
            if (entry.weight() == 0) continue;
            for (Map.Entry<String, SchematicLoader.Schematic> variant :
                    SchematicLoader.createHorizontalVariants(entry.path(), entry.schematic())) {
                SchematicLoader.Schematic schematic = variant.getValue();
                for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {
                    long key = connectionSignature(connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock());
                    uniqueByKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(schematic, entry.weight(), Math::max);
                }
            }
        }

        for (var e : uniqueByKey.entrySet()) {
            long key = e.getKey();
            List<SchematicLoader.Schematic> schemList = new ArrayList<>(e.getValue().keySet());
            int[] weights = e.getValue().values().stream().mapToInt(Integer::intValue).toArray();

            // cumulative weights for O(1) weight pick
            int[] cumulative = new int[weights.length];
            cumulative[0] = weights[0];
            for (int i = 1; i < weights.length; i++) {
                cumulative[i] = cumulative[i - 1] + weights[i];
            }

            candidateIndex.put(key, schemList);
            candidateCumulativeWeights.put(key, cumulative);
        }

        liminalness.LOGGER.info("frontier generator - initialization complete in: {} with {} schematics and weights: {}", getDimensionId(), schematics.size(), weightedPool.size());

    }

    public boolean isReady() {
        return !schematics.isEmpty();
    }

    // --- lifecycle ---

    public void resume() {
        running = true;
        liminalness.LOGGER.info("frontier generator - {}: resumed - frontier: {} rooms: {}", getDimensionId(), frontiers.size(), roomOrigins.size());
    }

    public void pause() {
        running = false;
    }

    public void clearFrontier() {
        this.frontiers.clear();
    }

    public void seedFresh() {
        if (this.schematics.isEmpty()) return;
        liminalness.LOGGER.info("seeding new generation for: {}", getDimensionId());

        // Pick spawn room from weight-1 pool, fall back to weightedPool if empty
        List<SchematicLoader.Schematic> pool = spawnPool.isEmpty() ? weightedPool : spawnPool;
        if (pool.isEmpty()) return;

        long hash = worldSeed;
        hash ^= getDimensionId().toString().hashCode();
        hash  = Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;
        SchematicLoader.Schematic startSchema = pool.get(
                (int) Long.remainderUnsigned(hash, pool.size())
        );

        int[] extents = getExtents(startSchema);
        long positionHash = hash;
        int spawnRange = Math.max(radiusHorizontal, 8192);

        positionHash = Long.rotateLeft(positionHash, 17) * 0x94D049BB133111EBL;
        int startX = randomInRange(positionHash, -spawnRange, spawnRange) - (extents[0] / 2);

        positionHash = Long.rotateLeft(positionHash, 17) * 0x94D049BB133111EBL;
        int startZ = randomInRange(positionHash, -spawnRange, spawnRange) - (extents[2] / 2);

        BlockPos startPos = new BlockPos(
            startX,
            generationY - (extents[1] / 2),
            startZ
        );

        startingRoomOrigin = startPos;
        roomOrigins.put(startPos, startSchema);
        spatialIndex.add(startPos, getExtents(startSchema));
        registerBlockMarkers(startPos, startSchema);
        seedFrontier(startPos, startSchema);
        resume();

        liminalness.LOGGER.info("{}: seeded with {} at {}", getDimensionId(), getPathBySchematic(startSchema), startPos);
    }

    private int randomInRange(long hash, int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        long span = (long) maxInclusive - minInclusive + 1L;
        return minInclusive + (int) Long.remainderUnsigned(hash, span);
    }

    private void restartFromDisconnectedSeed() {
        List<Map.Entry<BlockPos, SchematicLoader.Schematic>> rooms = new ArrayList<>(roomOrigins.entrySet());
        if (rooms.isEmpty() || weightedPool.isEmpty()) {
            return;
        }

        long hash = worldSeed;
        hash ^= (long) roomOrigins.size() * 0x9E3779B97F4A7C15L;
        hash ^= getDimensionId().toString().hashCode();
        hash = Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;

        for (int attempt = 0; attempt < Math.min(rooms.size() * 8, 128); attempt++) {
            hash = Long.rotateLeft(hash, 17) * 0x94D049BB133111EBL;
            Map.Entry<BlockPos, SchematicLoader.Schematic> anchorEntry =
                    rooms.get((int) Long.remainderUnsigned(hash, rooms.size()));

            hash = Long.rotateLeft(hash, 17) * 0x94D049BB133111EBL;
            SchematicLoader.Schematic candidate = weightedPool.get((int) Long.remainderUnsigned(hash, weightedPool.size()));

            BlockPos candidateOrigin = findDisconnectedPlacement(anchorEntry.getKey(), anchorEntry.getValue(), candidate, hash);
            if (candidateOrigin == null) {
                continue;
            }

            roomOrigins.put(candidateOrigin, candidate);
            spatialIndex.add(candidateOrigin, getExtents(candidate));
            writeToWorld(candidateOrigin, candidate);
            registerBlockMarkers(candidateOrigin, candidate);
            seedFrontier(candidateOrigin, candidate);

            final BlockPos finalOrigin = candidateOrigin;
            final SchematicLoader.Schematic finalCandidate = candidate;
            serverLevel.getServer().execute(() -> applyBlockEntities(finalOrigin, finalCandidate));

            liminalness.LOGGER.info("frontier generator - restarted disconnected generation with {} at {}", getPathBySchematic(candidate), candidateOrigin);
            return;
        }
    }

    private BlockPos findDisconnectedPlacement(BlockPos anchorOrigin, SchematicLoader.Schematic anchor, SchematicLoader.Schematic candidate, long hash) {
        int[] anchorExtents = getExtents(anchor);
        int[] candidateExtents = getExtents(candidate);
        int gap = 10;

        for (int offset = 0; offset < 4; offset++) {
            int directionIndex = (int) Long.remainderUnsigned(hash + offset, 4);
            BlockPos candidateOrigin = switch (directionIndex) {
                case 0 -> new BlockPos(
                    anchorOrigin.getX() + anchorExtents[0] + gap,
                    generationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ()
                );
                case 1 -> new BlockPos(
                    anchorOrigin.getX() - candidateExtents[0] - gap,
                    generationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ()
                );
                case 2 -> new BlockPos(
                    anchorOrigin.getX(),
                    generationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ() + anchorExtents[2] + gap
                );
                default -> new BlockPos(
                    anchorOrigin.getX(),
                    generationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ() - candidateExtents[2] - gap
                );
            };

            if (candidateOrigin.getY() < minGenerationY ||
                    candidateOrigin.getY() + candidateExtents[1] > maxGenerationY) {
                continue;
            }

            if (!overlapsAny(candidate, candidateOrigin)) {
                return candidateOrigin;
            }
        }

        return null;
    }

    private BlockState resolveFillSpace(String fillSpace) {
        ResourceLocation blockId = ResourceLocation.tryParse(fillSpace);
        if (blockId == null) {
            liminalness.LOGGER.warn("frontier generator - invalid fill_space '{}' for {}, defaulting to minecraft:smooth_sandstone", fillSpace, getDimensionId());
            return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        }

        Block block = BuiltInRegistries.BLOCK.get(blockId);
        if (block == Blocks.AIR && !blockId.equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))) {
            liminalness.LOGGER.warn("frontier generator - unknown fill_space '{}' for {}, defaulting to minecraft:smooth_sandstone", fillSpace, getDimensionId());
            return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        }

        return block.defaultBlockState();
    }

    public Vec3 getStartingSpawnPosition() {
        BlockPos origin = startingRoomOrigin;
        if (origin == null) {
            return new Vec3(0.5, generationY, 0.5);
        }

        SchematicLoader.Schematic schematic = roomOrigins.get(origin);
        if (schematic == null) {
            return new Vec3(origin.getX() + 0.5, generationY, origin.getZ() + 0.5);
        }

        int[] extents = getExtents(schematic);
        return new Vec3(
            origin.getX() + (extents[0] / 2.0) + 0.5,
            generationY,
            origin.getZ() + (extents[2] / 2.0) + 0.5
        );
    }

    public void seedFrontier(BlockPos origin, SchematicLoader.Schematic schematic) {
        for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {
            BlockPos worldCorner = origin.offset(connectionPoint.corner());
            BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
            if (!claimed.contains(attachPoint)) {
                frontiers.add(new FrontierEntry(attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock()));
            }
        }
    }

    public void reconstructFrontier() {
        this.frontiers.clear();
        for (var entry : this.roomOrigins.entrySet()) {
            BlockPos origin = entry.getKey();
            SchematicLoader.Schematic schematic = entry.getValue();
            for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {
                BlockPos worldCorner = origin.offset(connectionPoint.corner());
                BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
                if (!this.claimed.contains(attachPoint)) {
                    frontiers.add(new FrontierEntry(attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock()));
                }
            }
        }
        liminalness.LOGGER.info("frontier generator - {} open connections from {} rooms", frontiers.size(), roomOrigins.size());
    }

    public void tick() {

        if (serverLevel == null) return;
        List<BlockPos> playerPositions = serverLevel.players().stream().map(p -> p.blockPosition()).toList();
        if (playerPositions.isEmpty()) return;
        if (needsSeed && isReady() && roomOrigins.isEmpty()) {
            needsSeed = false;
            seedFresh();
            return;
        }
        if (!running) return;
        if (frontiers.isEmpty() && !roomOrigins.isEmpty() && isReady()) {
            restartFromDisconnectedSeed();
        }

        if (!stalePatchedChunks.isEmpty()) {
            for (var player : serverLevel.players()) {
                int playerChunkX = player.blockPosition().getX() >> 4;
                int playerChunkZ = player.blockPosition().getZ() >> 4;
                int watchRadius = serverLevel.getServer().getPlayerList().getViewDistance();

                for (int cx = playerChunkX - watchRadius; cx <= playerChunkX + watchRadius; cx++) {
                    for (int cz = playerChunkZ - watchRadius; cz <= playerChunkZ + watchRadius; cz++) {
                        long ck = chunkKey(cx, cz);
                        if (!stalePatchedChunks.remove(ck)) continue;
                        if (committedChunks.contains(ck)) continue;

                        int minX = cx << 4, maxX = minX + 16;
                        int minZ = cz << 4, maxZ = minZ + 16;

                        Set<BlockPos> nearbyRooms = spatialIndex.getRoomsInChunk(minX, maxX, minZ, maxZ);
                        boolean allResolved = true;

                        for (BlockPos origin : nearbyRooms) {
                            SchematicLoader.Schematic schematic = roomOrigins.get(origin);
                            if (schematic == null) { allResolved = false; continue; }

                            for (var block : schematic.finalBlocks().entrySet()) {
                                BlockPos local = block.getKey();
                                BlockPos world = origin.offset(block.getKey());
                                if (world.getX() < minX || world.getX() >= maxX) continue;
                                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                                if (!serverLevel.isLoaded(world)) { allResolved = false; continue; }
                                serverLevel.setBlock(world, block.getValue(), Block.UPDATE_CLIENTS);
                                if (schematic.chestPositions().contains(local)) {
                                    scheduleChestFill(world.immutable(), 0);
                                }
                            }
                        }

                        if (allResolved) {
                            committedChunks.add(ck);
                            pendingChunks.remove(ck);
                        } else {
                            stalePatchedChunks.add(ck);
                        }
                    }
                }
            }
        }

        // expand frontier processing
        boolean belowMinRooms = roomOrigins.size() < minRooms;
        int processed = 0;
        int scanned = 0;
        int scanLimit = belowMinRooms ? frontiers.size() : Math.min(frontiers.size(), stepsPerTick * 8);
        List<FrontierEntry> deferred = null;

        while (processed < stepsPerTick && scanned < scanLimit && !frontiers.isEmpty()) {
            FrontierEntry entry = frontiers.poll();
            scanned++;

            if (claimed.contains(entry.attachPoint())) continue;

            if (belowMinRooms || isInRange(entry.attachPoint(), playerPositions)) {
                expandFrontier(entry);
                processed++;
            } else {
                if (deferred == null) deferred = new ArrayList<>();
                deferred.add(entry);
            }
        }

        if (deferred != null) frontiers.addAll(deferred);
    }

    private boolean isInRange(BlockPos pos, List<BlockPos> players) {
        for (BlockPos player : players) {
            if (Math.abs(pos.getX() - player.getX()) <= radiusHorizontal &&
                Math.abs(pos.getZ() - player.getZ()) <= radiusHorizontal &&
                Math.abs(pos.getY() - player.getY()) <= radiusVertical)
            {
                return true;
            }
        }
        return false;
    }

    private void expandFrontier(FrontierEntry entry) {

        long key = connectionSignature(entry.incomingFacing().getOpposite(), entry.width(), entry.height(), entry.markerBlock());
        List<SchematicLoader.Schematic> candidates = candidateIndex.getOrDefault(key, List.of());

        if (candidates.isEmpty()) {
            claimed.add(entry.attachPoint());
            return;
        }

        long hash = worldSeed;
        hash ^= (long) entry.attachPoint().getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) entry.attachPoint().getY() * 0x6C62272E07BB0142L;
        hash ^= (long) entry.attachPoint().getZ() * 0xD2A98B26625EEE7BL;
        hash  = Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;

        boolean needsConnections = roomOrigins.size() < minRooms;
        Map<SchematicLoader.Schematic, List<PlacementOption>> validPlacements = new LinkedHashMap<>();

        for (SchematicLoader.Schematic candidate : candidates) {
            List<SchematicLoader.ConnectionPoint> matches = candidate.connectionPointIndex().get(key);
            if (matches == null) continue;

            for (SchematicLoader.ConnectionPoint matchingConnectionPoint : matches) {
                BlockPos candidateOrigin = entry.attachPoint().subtract(matchingConnectionPoint.corner());

                int[] extents = getExtents(candidate);
                if (candidateOrigin.getY() < minGenerationY ||
                        candidateOrigin.getY() + extents[1] > maxGenerationY) continue;

                if (overlapsAny(candidate, candidateOrigin)) continue;

                int newConnections = countNewConnections(candidate, candidateOrigin);
                if (needsConnections && newConnections == 0) continue;

                validPlacements.computeIfAbsent(candidate, ignored -> new ArrayList<>())
                        .add(new PlacementOption(candidate, candidateOrigin));
            }
        }

        if (validPlacements.isEmpty()) {
            claimed.add(entry.attachPoint());
            return;
        }

        hash = Long.rotateLeft(hash, 17) * 0x94D049BB133111EBL;
        SchematicLoader.Schematic selected = selectWeightedSchematic(validPlacements.keySet(), hash);
        List<PlacementOption> options = validPlacements.get(selected);

        hash = Long.rotateLeft(hash, 17) * 0x94D049BB133111EBL;
        PlacementOption chosen = options.get((int) Long.remainderUnsigned(hash, options.size()));
        placeCandidate(entry, chosen.candidate(), chosen.origin());
    }

    private SchematicLoader.Schematic selectWeightedSchematic(Collection<SchematicLoader.Schematic> candidates, long hash) {
        int totalWeight = 0;
        for (SchematicLoader.Schematic candidate : candidates) {
            totalWeight += schematicWeights.getOrDefault(candidate, 1);
        }

        int roll = (int) Long.remainderUnsigned(hash, totalWeight);
        int running = 0;
        for (SchematicLoader.Schematic candidate : candidates) {
            running += schematicWeights.getOrDefault(candidate, 1);
            if (roll < running) {
                return candidate;
            }
        }

        return candidates.iterator().next();
    }

    private void placeCandidate(FrontierEntry entry, SchematicLoader.Schematic candidate, BlockPos candidateOrigin) {
        claimed.add(entry.attachPoint());
        roomOrigins.put(candidateOrigin, candidate);
        spatialIndex.add(candidateOrigin, getExtents(candidate));
        writeToWorld(candidateOrigin, candidate);
        registerBlockMarkers(candidateOrigin, candidate);

        final BlockPos finalOrigin = candidateOrigin;
        final SchematicLoader.Schematic finalCandidate = candidate;
        serverLevel.getServer().execute(() -> applyBlockEntities(finalOrigin, finalCandidate));

        for (SchematicLoader.ConnectionPoint connectionPoint : candidate.connectionPoints()) {
            BlockPos worldCorner = candidateOrigin.offset(connectionPoint.corner());
            BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
            if (!claimed.contains(attachPoint)) {
                frontiers.add(new FrontierEntry(attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock()));
            }
        }
    }

    private int countNewConnections(SchematicLoader.Schematic candidate, BlockPos origin) {
        int count = 0;
        for (SchematicLoader.ConnectionPoint connectionPoint : candidate.connectionPoints()) {
            BlockPos worldCorner = origin.offset(connectionPoint.corner());
            BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
            if (!claimed.contains(attachPoint)) {
                count++;
            }
        }
        return Math.max(0, count - 1);
    }

    private void writeToWorld(BlockPos origin, SchematicLoader.Schematic schematic) {
        if (serverLevel == null) return;

        int[] extents = getExtents(schematic);
        int minChunkX = origin.getX() >> 4;
        int maxChunkX = (origin.getX() + extents[0]) >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkZ = (origin.getZ() + extents[2]) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long ck = chunkKey(cx, cz);
                committedChunks.remove(ck);
                stalePatchedChunks.add(ck);
            }
        }

        // write directly
        for (var block : schematic.finalBlocks().entrySet()) {
            BlockPos local = block.getKey();
            BlockPos world = origin.offset(local);
            if (!serverLevel.isLoaded(world)) continue;
            serverLevel.setBlock(world, block.getValue(), Block.UPDATE_CLIENTS);
            if (schematic.chestPositions().contains(local)) {
                scheduleChestFill(world.immutable(), 0);
            }
        }

        for (BlockPos local : schematic.chestPositions()) {
            BlockPos world = origin.offset(local).immutable();
            if (serverLevel.isLoaded(world)) continue;
            stalePatchedChunks.add(chunkKey(world.getX() >> 4, world.getZ() >> 4));
        }
    }

    private void scheduleChestFill(BlockPos world, int attempt) {
        if (attempt > 10) {
            liminalness.LOGGER.warn("frontier generator - chest at {} failed after 10 attempts", world);
            return;
        }
        serverLevel.getServer().execute(() -> {
            if (!serverLevel.isLoaded(world)) {
                scheduleChestFill(world, attempt + 1);
                return;
            }
            var blockEntity = serverLevel.getBlockEntity(world);
            if (blockEntity == null) {
                scheduleChestFill(world, attempt + 1);
                return;
            }
            if (!consumedChests.add(world)) {
                return;
            }
            ChestLootHandler.fillChest(serverLevel, world, worldSeed);
        });
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager,
                                                        ChunkAccess chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxX = minX + 16;
        int maxZ = minZ + 16;

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();


        // solid fill debug test
        for (int x = minX; x < maxX; x++)
            for (int z = minZ; z < maxZ; z++)
                for (int y = minGenerationY; y <= maxGenerationY; y++) {
                    mutable.set(x, y, z);
                    chunk.setBlockState(mutable, fillSpaceState, false);
                }

        Set<BlockPos> roomSnapshot = spatialIndex.getRoomsInChunk(minX, maxX, minZ, maxZ);
        boolean allResolved = true;

        for (BlockPos origin : roomSnapshot) {
            SchematicLoader.Schematic schematic = roomOrigins.get(origin);
            if (schematic == null) {
                allResolved = false;
                continue;
            }

            for (var block : schematic.finalBlocks().entrySet()) {
                BlockPos world = origin.offset(block.getKey());
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                mutable.set(world);
                chunk.setBlockState(mutable, block.getValue(), false);
            }
        }

        long ck = chunkKey(chunk.getPos().x, chunk.getPos().z);
        if (allResolved) {
            committedChunks.add(ck);
        } else {
            pendingChunks.add(ck);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private void registerBlockMarkers(BlockPos origin, SchematicLoader.Schematic schematic) {

        for (BlockPos local : schematic.portalPositions()) {
            liminalness.LOGGER.debug("frontier generator - register portal at {}", local);
            portalPositions.add(origin.offset(local));
        }
        for (BlockPos local : schematic.chestPositions()) {
            liminalness.LOGGER.debug("frontier generator - register chest at {}", local);
            chestPositions.add(origin.offset(local));
        }

    }

    private void applyBlockEntities(BlockPos origin, SchematicLoader.Schematic schematic) {
        if (serverLevel == null) return;
        if (schematic.blockEntityData().isEmpty()) return;

        for (var entry : schematic.blockEntityData().entrySet()) {
            BlockPos world = origin.offset(entry.getKey()).immutable();
            if (!serverLevel.isLoaded(world)) continue;

            BlockState current = serverLevel.getBlockState(world);
            liminalness.LOGGER.info("applyBlockEntities - pos={} block={}", world, current);

            var blockEntity = serverLevel.getBlockEntity(world);
            if (blockEntity == null) {
                liminalness.LOGGER.warn("applyBlockEntities - no block entity at {} ({})", world, current);
                continue;
            }

            CompoundTag toApply = entry.getValue().copy();
            toApply.putInt("x", world.getX());
            toApply.putInt("y", world.getY());
            toApply.putInt("z", world.getZ());

            blockEntity.loadWithComponents(toApply, serverLevel.registryAccess());
            blockEntity.setChanged();
            serverLevel.sendBlockUpdated(world, current, current, Block.UPDATE_CLIENTS);
            liminalness.LOGGER.info("applyBlockEntities - applied {} items at {}",
                    toApply.contains("Items") ? toApply.getList("Items", 10).size() : 0, world);
        }
    }

    // --- utils ---

    public static long connectionSignature(Direction facing, int width, int height, Block markerBlock) {
        return ((long) facing.ordinal() << 48)
                | ((long) (BuiltInRegistries.BLOCK.getId(markerBlock) & 0xFFFF) << 32)
                | ((long) (width  & 0xFFFF) << 16)
                | (height & 0xFFFF
        );
    }

    private boolean overlapsAny(SchematicLoader.Schematic candidate, BlockPos origin) {
        return spatialIndex.overlapsAny(origin, getExtents(candidate));
    }

    public int[] getExtents(SchematicLoader.Schematic s) {
        return extentsCache.computeIfAbsent(s, sch -> new int[]{
            sch.blocks().keySet().stream().mapToInt(BlockPos::getX).max().orElse(0),
            sch.blocks().keySet().stream().mapToInt(BlockPos::getY).max().orElse(0),
            sch.blocks().keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0)
        });
    }

    public String getPathBySchematic(SchematicLoader.Schematic s) {
        return schematicPaths.getOrDefault(s, "unknown");
    }

    public SchematicLoader.Schematic getSchematicByPath(String path) {
        return pathToSchematic.get(path);
    }


    // --- required overrides ---
    @Override
    public void applyCarvers(WorldGenRegion r, long seed, RandomState rs,
                             BiomeManager bm, StructureManager sm,
                             ChunkAccess c, GenerationStep.Carving carving) {}

    @Override
    public void buildSurface(WorldGenRegion r, StructureManager sm,
                             RandomState rs, ChunkAccess c) {}

    @Override
    public void spawnOriginalMobs(WorldGenRegion r) {}

    @Override
    public int getGenDepth() { return 384; }

    @Override
    public int getSeaLevel() { return -63; }

    @Override
    public int getMinY() { return -64; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types t,
                             LevelHeightAccessor l, RandomState rs) {
        return generationY;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z,
                                     LevelHeightAccessor l, RandomState rs) {
        return new NoiseColumn(0, new BlockState[0]);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState rs, BlockPos pos) {
        info.add("dimension: " + getDimensionId());
        info.add("rooms: " + roomOrigins.size());
        info.add("frontier: " + frontiers.size());
    }

}

