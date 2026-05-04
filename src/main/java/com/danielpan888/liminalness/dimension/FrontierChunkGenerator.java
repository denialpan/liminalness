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
    public final Set<BlockPos> jigsawPortalPositions = ConcurrentHashMap.newKeySet();
    public final Set<BlockPos> structurePortalPositions = ConcurrentHashMap.newKeySet();
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
    private final ArrayDeque<Long> staleChunkQueue = new ArrayDeque<>();
    private final Set<Long> queuedStaleChunks = ConcurrentHashMap.newKeySet();

    private final Map<SchematicLoader.Schematic, String> schematicPaths = new HashMap<>();
    private final Map<String, SchematicLoader.Schematic> pathToSchematic = new HashMap<>();
    private final Map<SchematicLoader.Schematic, Integer> schematicWeights = new HashMap<>();
    private final Map<SchematicLoader.Schematic, String> schematicFamilies = new HashMap<>();
    private final Map<SchematicLoader.Schematic, Set<Integer>> schematicLevels = new HashMap<>();
    private final Map<SchematicLoader.Schematic, Boolean> schematicLiteralMatches = new HashMap<>();
    private final Map<String, Boolean> familyCanConnectItselfVertically = new HashMap<>();
    private final Map<String, Boolean> familyCanConnectItselfHorizontally = new HashMap<>();
    private final Map<String, Integer> familyWeightPenalty = new HashMap<>();
    private final Map<String, List<SchematicLoader.Schematic>> variantsByBasePath = new HashMap<>();
    private final ArrayDeque<String> recentPlacedFamilies = new ArrayDeque<>();
    private final Map<String, Integer> recentFamilyCounts = new HashMap<>();
    protected List<SchematicLoader.Schematic> schematics = new ArrayList<>();
    protected List<SchematicLoader.Schematic> weightedPool = new ArrayList<>();
    protected List<SchematicLoader.Schematic> spawnPool = new ArrayList<>();

    private static final int RECENT_FAMILY_WINDOW = 12;

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
            BlockPos sourceRoomOrigin,
            BlockPos attachPoint,
            Direction incomingFacing,
            int width,
            int height,
            long patternHash,
            int[] pattern,
            int level
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
        this.schematicFamilies.clear();
        this.schematicLevels.clear();
        this.schematicLiteralMatches.clear();
        this.familyCanConnectItselfVertically.clear();
        this.familyCanConnectItselfHorizontally.clear();
        this.familyWeightPenalty.clear();
        this.variantsByBasePath.clear();
        this.recentPlacedFamilies.clear();
        this.recentFamilyCounts.clear();
        this.schematics.clear();
        this.weightedPool.clear();
        this.spawnPool.clear();
        this.spatialIndex.clear();
        this.startingRoomOrigin = null;
        this.portalPositions.clear();
        this.jigsawPortalPositions.clear();
        this.structurePortalPositions.clear();
        this.chestPositions.clear();
        this.consumedChests.clear();
        this.staleChunkQueue.clear();
        this.queuedStaleChunks.clear();

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
            List<Map.Entry<String, SchematicLoader.Schematic>> variants = SchematicLoader.createHorizontalVariants(entry.path(), entry.schematic(), entry.mirroredVariants());

            String family = schematicFamily(entry.path());
            familyCanConnectItselfVertically.put(family, entry.canConnectItselfVertically());
            familyCanConnectItselfHorizontally.put(family, entry.canConnectItselfHorizontally());
            familyWeightPenalty.put(family, entry.weightPenalty());
            List<SchematicLoader.Schematic> familyVariants = new ArrayList<>();

            boolean firstVariant = true;
            for (Map.Entry<String, SchematicLoader.Schematic> variant : variants) {

                SchematicLoader.Schematic schematic = variant.getValue();
                familyVariants.add(schematic);
                schematics.add(schematic);
                schematicPaths.put(schematic, variant.getKey());
                pathToSchematic.put(variant.getKey(), schematic);
                schematicWeights.put(schematic, entry.weight());
                schematicFamilies.put(schematic, family);
                schematicLevels.put(schematic, entry.levels());
                schematicLiteralMatches.put(schematic, entry.literalMatch());

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

            variantsByBasePath.put(entry.path(), familyVariants);
        }

        spatialIndex.clear();
        for (var entry : roomOrigins.entrySet()) {
            spatialIndex.add(entry.getKey(), getExtents(entry.getValue()));
        }

        Map<Long, Map<SchematicLoader.Schematic, Integer>> uniqueByKey = new HashMap<>();

        for (DimensionConfig.SchematicEntry entry : dimensionConfig.schematics()) {
            if (entry.weight() == 0) continue;
            for (SchematicLoader.Schematic schematic : variantsByBasePath.getOrDefault(entry.path(), List.of())) {
                for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {

                    long key = connectionShapeSignature(connectionPoint.facing(), connectionPoint.width(), connectionPoint.height());
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

    public void resetStaleChunkTracking() {
        stalePatchedChunks.clear();
        staleChunkQueue.clear();
        queuedStaleChunks.clear();
    }

    public void markChunkStale(long chunkKey) {
        if (stalePatchedChunks.add(chunkKey) && queuedStaleChunks.add(chunkKey)) {
            staleChunkQueue.addLast(chunkKey);
        }
    }

    public void seedFresh() {
        if (this.schematics.isEmpty()) return;
        liminalness.LOGGER.info("seeding new generation for: {}", getDimensionId());

        // Pick spawn room from weight-1 pool, fall back to weightedPool if empty
        List<SchematicLoader.Schematic> pool = spawnPool.isEmpty() ? weightedPool : spawnPool;
        if (pool.isEmpty()) return;

        long hash = startingRoomHash();
        SchematicLoader.Schematic startSchema = pool.get(
                (int) Long.remainderUnsigned(hash, pool.size())
        );

        long positionHash = hash;
        int spawnRange = Math.max(radiusHorizontal, 2560000);

        positionHash = Long.rotateLeft(positionHash, 17) * 0x94D049BB133111EBL;
        int startCenterX = randomInRange(positionHash, -spawnRange, spawnRange);

        positionHash = Long.rotateLeft(positionHash, 17) * 0x94D049BB133111EBL;
        int startCenterZ = randomInRange(positionHash, -spawnRange, spawnRange);

        seedFreshAt(startSchema, startCenterX, startCenterZ);
    }

    public void seedFreshAt(int startCenterX, int startCenterZ) {
        if (this.schematics.isEmpty()) return;
        liminalness.LOGGER.info("seeding new generation for: {}", getDimensionId());

        SchematicLoader.Schematic startSchema = selectStartingSchematic();
        if (startSchema == null) return;
        seedFreshAt(startSchema, startCenterX, startCenterZ);
    }

    public Vec3 ensureLinkedSpawn(int startCenterX, int startCenterZ) {
        SchematicLoader.Schematic startSchema = selectStartingSchematic();
        if (startSchema == null) {
            return new Vec3(0.5, generationY, 0.5);
        }

        BlockPos existingOrigin = findRoomContaining(startCenterX, startCenterZ);
        if (existingOrigin != null) {
            SchematicLoader.Schematic existing = roomOrigins.get(existingOrigin);
            if (existing != null) {
                return getSpawnPositionForRoom(existingOrigin, existing);
            }
        }

        BlockPos desiredOrigin = originForCenter(startSchema, startCenterX, startCenterZ);
        SchematicLoader.Schematic exactExisting = roomOrigins.get(desiredOrigin);
        if (exactExisting != null) {
            return getSpawnPositionForRoom(desiredOrigin, exactExisting);
        }

        if (!overlapsAny(startSchema, desiredOrigin)) {
            placeDisconnectedSeed(desiredOrigin, startSchema);
            return getSpawnPositionForRoom(desiredOrigin, startSchema);
        }

        BlockPos overlappingOrigin = findOverlappingRoom(startSchema, desiredOrigin);
        if (overlappingOrigin != null) {
            SchematicLoader.Schematic overlapping = roomOrigins.get(overlappingOrigin);
            if (overlapping != null) {
                return getSpawnPositionForRoom(overlappingOrigin, overlapping);
            }
        }

        return getStartingSpawnPosition();
    }

    private void seedFreshAt(SchematicLoader.Schematic startSchema, int startCenterX, int startCenterZ) {
        BlockPos startPos = originForCenter(startSchema, startCenterX, startCenterZ);

        startingRoomOrigin = startPos;
        roomOrigins.put(startPos, startSchema);
        spatialIndex.add(startPos, getExtents(startSchema));
        registerBlockMarkers(startPos, startSchema);
        seedFrontier(startPos, startSchema);
        recordPlacedFamily(startSchema);
        resume();

        liminalness.LOGGER.info("{}: seeded with {} at {}", getDimensionId(), getPathBySchematic(startSchema), startPos);
    }

    private void placeDisconnectedSeed(BlockPos origin, SchematicLoader.Schematic schematic) {
        roomOrigins.put(origin, schematic);
        spatialIndex.add(origin, getExtents(schematic));
        writeToWorld(origin, schematic);
        registerBlockMarkers(origin, schematic);
        seedFrontier(origin, schematic);
        recordPlacedFamily(schematic);

        final BlockPos finalOrigin = origin;
        final SchematicLoader.Schematic finalSchematic = schematic;
        serverLevel.getServer().execute(() -> applyBlockEntities(finalOrigin, finalSchematic));
        resume();
    }

    private long startingRoomHash() {
        long hash = worldSeed;
        hash ^= getDimensionId().toString().hashCode();
        return Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;
    }

    private SchematicLoader.Schematic selectStartingSchematic() {
        List<SchematicLoader.Schematic> pool = spawnPool.isEmpty() ? weightedPool : spawnPool;
        if (pool.isEmpty()) {
            return null;
        }

        long hash = startingRoomHash();
        return pool.get((int) Long.remainderUnsigned(hash, pool.size()));
    }

    private BlockPos originForCenter(SchematicLoader.Schematic schematic, int centerX, int centerZ) {
        int[] extents = getExtents(schematic);
        return new BlockPos(
            centerX - (extents[0] / 2),
            generationY - (extents[1] / 2),
            centerZ - (extents[2] / 2)
        );
    }

    private Vec3 getSpawnPositionForRoom(BlockPos origin, SchematicLoader.Schematic schematic) {
        int[] extents = getExtents(schematic);
        return new Vec3(
            origin.getX() + (extents[0] / 2.0) + 0.5,
            generationY,
            origin.getZ() + (extents[2] / 2.0) + 0.5
        );
    }

    private BlockPos findRoomContaining(int centerX, int centerZ) {
        Set<BlockPos> nearbyRooms = spatialIndex.getRoomsInChunk(centerX, centerX + 1, centerZ, centerZ + 1);
        for (BlockPos origin : nearbyRooms) {
            SchematicLoader.Schematic schematic = roomOrigins.get(origin);
            if (schematic == null) {
                continue;
            }
            int[] extents = getExtents(schematic);

            // offset by 1 boundary length
            if (centerX < origin.getX() || centerX >= origin.getX() + extents[0]) {
                continue;
            }
            if (centerZ < origin.getZ() || centerZ >= origin.getZ() + extents[2]) {
                continue;
            }

            return origin;
        }

        return null;
    }

    private BlockPos findOverlappingRoom(SchematicLoader.Schematic candidate, BlockPos origin) {
        int[] candidateExtents = getExtents(candidate);
        int minX = origin.getX();
        int maxX = origin.getX() + candidateExtents[0];
        int minZ = origin.getZ();
        int maxZ = origin.getZ() + candidateExtents[2];

        Set<BlockPos> nearbyRooms = spatialIndex.getRoomsInChunk(minX, maxX, minZ, maxZ);
        for (BlockPos existingOrigin : nearbyRooms) {
            SchematicLoader.Schematic existing = roomOrigins.get(existingOrigin);
            if (existing == null) {
                continue;
            }
            int[] existingExtents = getExtents(existing);
            if (boxesOverlap(origin, candidateExtents, existingOrigin, existingExtents)) {
                return existingOrigin;
            }
        }
        return null;
    }

    private boolean boxesOverlap(BlockPos aOrigin, int[] aExtents, BlockPos bOrigin, int[] bExtents) {
        return aOrigin.getX() < bOrigin.getX() + bExtents[0]
            && aOrigin.getX() + aExtents[0] > bOrigin.getX()
            && aOrigin.getY() < bOrigin.getY() + bExtents[1]
            && aOrigin.getY() + aExtents[1] > bOrigin.getY()
            && aOrigin.getZ() < bOrigin.getZ() + bExtents[2]
            && aOrigin.getZ() + aExtents[2] > bOrigin.getZ();
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

            if (candidateOrigin.getY() < minGenerationY || candidateOrigin.getY() + candidateExtents[1] > maxGenerationY + 1) {
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

        return getSpawnPositionForRoom(origin, schematic);
    }

    public void seedFrontier(BlockPos origin, SchematicLoader.Schematic schematic) {
        for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {

            BlockPos worldCorner = origin.offset(connectionPoint.corner());
            BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);

            for (int level : getLevelsForSchematic(schematic)) {
                if (!claimed.contains(attachPoint)) {
                    frontiers.add(new FrontierEntry(origin, attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.patternHash(), connectionPoint.pattern().clone(), level));
                }
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
                for (int level : getLevelsForSchematic(schematic)) {
                    if (!this.claimed.contains(attachPoint)) {
                        frontiers.add(new FrontierEntry(origin, attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.patternHash(), connectionPoint.pattern().clone(), level));
                    }
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
            processStaleChunks(playerPositions);
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

            List<FrontierEntry> competingEntries = collectCompetingFrontiers(entry);
            FrontierEntry selectedEntry = chooseCompetingFrontier(competingEntries);

            if (claimed.contains(selectedEntry.attachPoint())) continue;

            if (belowMinRooms || isInRange(selectedEntry.attachPoint(), playerPositions)) {
                expandFrontier(selectedEntry);
                processed++;
            } else {
                if (deferred == null) deferred = new ArrayList<>();
                deferred.addAll(competingEntries);
            }
        }

        if (deferred != null) frontiers.addAll(deferred);
    }

    private void processStaleChunks(List<BlockPos> playerPositions) {
        int budget = Math.max(stepsPerTick * 2, 16);
        int attempts = 0;

        while (attempts < budget && !staleChunkQueue.isEmpty()) {
            long ck = staleChunkQueue.pollFirst();
            queuedStaleChunks.remove(ck);
            attempts++;

            if (!stalePatchedChunks.contains(ck) || committedChunks.contains(ck)) {
                stalePatchedChunks.remove(ck);
                continue;
            }

            int chunkX = (int) (ck >> 32);
            int chunkZ = (int) ck;
            if (!isChunkNearAnyPlayer(chunkX, chunkZ, playerPositions)) {
                requeueStaleChunk(ck);
                continue;
            }

            if (patchChunk(chunkX, chunkZ)) {
                stalePatchedChunks.remove(ck);
                committedChunks.add(ck);
                pendingChunks.remove(ck);
            } else {
                requeueStaleChunk(ck);
            }
        }
    }

    private boolean patchChunk(int chunkX, int chunkZ) {
        int minX = chunkX << 4, maxX = minX + 16;
        int minZ = chunkZ << 4, maxZ = minZ + 16;

        Set<BlockPos> nearbyRooms = spatialIndex.getRoomsInChunk(minX, maxX, minZ, maxZ);
        boolean allResolved = true;

        for (BlockPos origin : nearbyRooms) {
            SchematicLoader.Schematic schematic = roomOrigins.get(origin);
            if (schematic == null) {
                allResolved = false;
                continue;
            }

            for (var block : schematic.finalBlocks().entrySet()) {
                BlockPos local = block.getKey();
                BlockPos world = origin.offset(local);
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                if (!serverLevel.isLoaded(world)) {
                    allResolved = false;
                    continue;
                }
                serverLevel.setBlock(world, block.getValue(), Block.UPDATE_CLIENTS);
                if (schematic.chestPositions().contains(local)) {
                    scheduleChestFill(world.immutable(), 0);
                }
            }
        }

        return allResolved;
    }

    private boolean isChunkNearAnyPlayer(int chunkX, int chunkZ, List<BlockPos> playerPositions) {
        int watchRadius = serverLevel.getServer().getPlayerList().getViewDistance();
        for (BlockPos playerPos : playerPositions) {
            int playerChunkX = playerPos.getX() >> 4;
            int playerChunkZ = playerPos.getZ() >> 4;
            if (Math.abs(chunkX - playerChunkX) <= watchRadius &&
                    Math.abs(chunkZ - playerChunkZ) <= watchRadius) {
                return true;
            }
        }
        return false;
    }

    private void requeueStaleChunk(long chunkKey) {
        if (stalePatchedChunks.contains(chunkKey) && queuedStaleChunks.add(chunkKey)) {
            staleChunkQueue.addLast(chunkKey);
        }
    }

    private List<FrontierEntry> collectCompetingFrontiers(FrontierEntry firstEntry) {
        List<FrontierEntry> competingEntries = new ArrayList<>();
        competingEntries.add(firstEntry);

        Iterator<FrontierEntry> iterator = frontiers.iterator();
        while (iterator.hasNext()) {
            FrontierEntry candidate = iterator.next();
            if (candidate.attachPoint().equals(firstEntry.attachPoint())) {
                competingEntries.add(candidate);
                iterator.remove();
            }
        }

        return competingEntries;
    }

    private FrontierEntry chooseCompetingFrontier(List<FrontierEntry> competingEntries) {
        if (competingEntries.size() == 1) {
            return competingEntries.getFirst();
        }

        BlockPos attachPoint = competingEntries.getFirst().attachPoint();
        long hash = worldSeed;
        hash ^= (long) attachPoint.getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) attachPoint.getY() * 0x6C62272E07BB0142L;
        hash ^= (long) attachPoint.getZ() * 0xD2A98B26625EEE7BL;
        hash ^= (long) roomOrigins.size() * 0x94D049BB133111EBL;

        int index = (int) Long.remainderUnsigned(hash, competingEntries.size());
        return competingEntries.get(index);
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

        long key = connectionShapeSignature(entry.incomingFacing().getOpposite(), entry.width(), entry.height());
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

            if (!canConnectItself(entry, candidate)) {
                continue;
            }
            if (!supportsFrontierLevel(candidate, entry.level())) {
                continue;
            }

            List<SchematicLoader.ConnectionPoint> matches = getMatchingConnectionPoints(candidate, entry);
            if (matches == null) continue;

            for (SchematicLoader.ConnectionPoint matchingConnectionPoint : matches) {
                BlockPos candidateOrigin = entry.attachPoint().subtract(matchingConnectionPoint.corner());

                int[] extents = getExtents(candidate);
                if (candidateOrigin.getY() < minGenerationY || candidateOrigin.getY() + extents[1] > maxGenerationY + 1) continue;

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
            totalWeight += getEffectiveWeight(candidate);
        }

        if (totalWeight <= 0) {
            return candidates.iterator().next();
        }

        int roll = (int) Long.remainderUnsigned(hash, totalWeight);
        int running = 0;
        for (SchematicLoader.Schematic candidate : candidates) {
            running += getEffectiveWeight(candidate);
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
        recordPlacedFamily(candidate);

        // schematic family check
        SchematicLoader.Schematic source = roomOrigins.get(entry.sourceRoomOrigin());
        String sourceFamily = source != null ? schematicFamilies.getOrDefault(source, "unknown") : "unknown";
        String candidateFamily = schematicFamilies.getOrDefault(candidate, "unknown");
//        liminalness.LOGGER.info(
//            "frontier generator - placement source_family={} source_origin={} candidate_family={} candidate_path={} candidate_origin={} level={}",
//            sourceFamily,
//            entry.sourceRoomOrigin(),
//            candidateFamily,
//            getPathBySchematic(candidate),
//            candidateOrigin,
//            entry.level()
//        );

        final BlockPos finalOrigin = candidateOrigin;
        final SchematicLoader.Schematic finalCandidate = candidate;
        serverLevel.getServer().execute(() -> applyBlockEntities(finalOrigin, finalCandidate));

        for (SchematicLoader.ConnectionPoint connectionPoint : candidate.connectionPoints()) {
            BlockPos worldCorner = candidateOrigin.offset(connectionPoint.corner());
            BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
            for (int level : getLevelsForSchematic(candidate)) {
                if (!claimed.contains(attachPoint)) {
                    frontiers.add(new FrontierEntry(candidateOrigin, attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.patternHash(), connectionPoint.pattern().clone(), level));
                }
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
        int maxChunkX = (origin.getX() + extents[0] - 1) >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkZ = (origin.getZ() + extents[2] - 1) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long ck = chunkKey(cx, cz);
                committedChunks.remove(ck);
                markChunkStale(ck);
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
            markChunkStale(chunkKey(world.getX() >> 4, world.getZ() >> 4));
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
        for (BlockPos local : schematic.jigsawPortalPositions()) {
            liminalness.LOGGER.debug("frontier generator - register jigsaw portal at {}", local);
            jigsawPortalPositions.add(origin.offset(local));
        }
        for (BlockPos local : schematic.structurePortalPositions()) {
            liminalness.LOGGER.debug("frontier generator - register structure portal at {}", local);
            structurePortalPositions.add(origin.offset(local));
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

    public static long connectionSignature(Direction facing, int width, int height, long patternHash) {
        long hash = 0xcbf29ce484222325L;
        hash ^= facing.ordinal();
        hash *= 0x100000001b3L;
        hash ^= width;
        hash *= 0x100000001b3L;
        hash ^= height;
        hash *= 0x100000001b3L;
        hash ^= patternHash;
        hash *= 0x100000001b3L;
        return hash;
    }

    public static long connectionShapeSignature(Direction facing, int width, int height) {
        long hash = 0xcbf29ce484222325L;
        hash ^= facing.ordinal();
        hash *= 0x100000001b3L;
        hash ^= width;
        hash *= 0x100000001b3L;
        hash ^= height;
        hash *= 0x100000001b3L;
        return hash;
    }

    private boolean overlapsAny(SchematicLoader.Schematic candidate, BlockPos origin) {
        return spatialIndex.overlapsAny(origin, getExtents(candidate));
    }

    public int[] getExtents(SchematicLoader.Schematic s) {
        return extentsCache.computeIfAbsent(s, sch -> new int[]{
            sch.extentX(),
            sch.extentY(),
            sch.extentZ()
        });
    }

    public String getPathBySchematic(SchematicLoader.Schematic s) {
        return schematicPaths.getOrDefault(s, "unknown");
    }

    public SchematicLoader.Schematic getSchematicByPath(String path) {
        return pathToSchematic.get(path);
    }

    private boolean canConnectItself(FrontierEntry entry, SchematicLoader.Schematic candidate) {

        SchematicLoader.Schematic source = roomOrigins.get(entry.sourceRoomOrigin());
        if (source == null) {
            return true;
        }

        String sourceFamily = schematicFamilies.get(source);
        String candidateFamily = schematicFamilies.get(candidate);
        if (sourceFamily == null || !sourceFamily.equals(candidateFamily)) {
            return true;
        }

        return entry.incomingFacing().getAxis() == Direction.Axis.Y
            ? familyCanConnectItselfVertically.getOrDefault(sourceFamily, true)
            : familyCanConnectItselfHorizontally.getOrDefault(sourceFamily, true);
    }

    private String schematicFamily(String path) {
        int variantSeparator = path.indexOf('#');
        return variantSeparator >= 0 ? path.substring(0, variantSeparator) : path;
    }

    private Set<Integer> getLevelsForSchematic(SchematicLoader.Schematic schematic) {
        return schematicLevels.getOrDefault(schematic, Set.of(1));
    }

    private boolean supportsFrontierLevel(SchematicLoader.Schematic schematic, int level) {
        return getLevelsForSchematic(schematic).contains(level);
    }

    private List<SchematicLoader.ConnectionPoint> getMatchingConnectionPoints(SchematicLoader.Schematic candidate, FrontierEntry entry) {
        List<SchematicLoader.ConnectionPoint> matches = new ArrayList<>();
        Direction requiredFacing = entry.incomingFacing().getOpposite();

        for (SchematicLoader.ConnectionPoint connectionPoint : candidate.connectionPoints()) {
            if (connectionPoint.facing() != requiredFacing) {
                continue;
            }
            if (connectionPoint.width() != entry.width() || connectionPoint.height() != entry.height()) {
                continue;
            }
            if (!patternsMatch(entry, candidate, connectionPoint)) {
                continue;
            }
            matches.add(connectionPoint);
        }

        return matches.isEmpty() ? null : matches;
    }

    private boolean patternsMatch(FrontierEntry entry, SchematicLoader.Schematic candidate, SchematicLoader.ConnectionPoint connectionPoint) {
        if (!requiresLiteralMatch(entry.sourceRoomOrigin(), candidate)) {
            return connectionPoint.patternHash() == entry.patternHash();
        }

        int[] expectedPattern = literalMatchPattern(entry.incomingFacing(), entry.width(), entry.height(), entry.pattern());
        return Arrays.equals(connectionPoint.pattern(), expectedPattern);
    }

    private boolean requiresLiteralMatch(BlockPos sourceRoomOrigin, SchematicLoader.Schematic candidate) {
        SchematicLoader.Schematic source = roomOrigins.get(sourceRoomOrigin);
        boolean sourceLiteralMatch = source != null && schematicLiteralMatches.getOrDefault(source, true);
        boolean candidateLiteralMatch = schematicLiteralMatches.getOrDefault(candidate, true);
        return sourceLiteralMatch || candidateLiteralMatch;
    }

    private int[] literalMatchPattern(Direction facing, int width, int height, int[] pattern) {
        if (facing.getAxis() == Direction.Axis.Y) {
            return pattern.clone();
        }

        int[] transformed = new int[pattern.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                transformed[y * width + (width - 1 - x)] = pattern[y * width + x];
            }
        }
        return transformed;
    }

    private int getEffectiveWeight(SchematicLoader.Schematic schematic) {
        int baseWeight = schematicWeights.getOrDefault(schematic, 1);
        String family = schematicFamilies.get(schematic);
        if (family == null) {
            return baseWeight;
        }

        int penalty = familyWeightPenalty.getOrDefault(family, 0);
        if (penalty <= 0) {
            return baseWeight;
        }

        int recentCount = recentFamilyCounts.getOrDefault(family, 0);
        return Math.max(1, baseWeight - (recentCount * penalty));
    }

    private void recordPlacedFamily(SchematicLoader.Schematic schematic) {
        String family = schematicFamilies.get(schematic);
        if (family == null) {
            return;
        }

        recentPlacedFamilies.addLast(family);
        recentFamilyCounts.merge(family, 1, Integer::sum);

        while (recentPlacedFamilies.size() > RECENT_FAMILY_WINDOW) {
            String expired = recentPlacedFamilies.removeFirst();
            recentFamilyCounts.computeIfPresent(expired, (ignored, count) -> count > 1 ? count - 1 : null);
        }
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

