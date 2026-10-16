package com.danielpan888.liminalness.dimension;

import com.danielpan888.liminalness.Config;
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
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class FrontierChunkGenerator extends ChunkGenerator {

    // server level state for dimension instance
    public volatile ServerLevel serverLevel;
    public volatile boolean running = false;
    public boolean needsSeed = false;
    public long worldSeed;
    public boolean initialized = false;
    public volatile BlockPos startingRoomOrigin;
    public volatile ResourceLocation dimensionId;

    // reserved block markers from placed schematics
    public final Set<BlockPos> portalPositions = ConcurrentHashMap.newKeySet();
    public final Set<BlockPos> jigsawPortalPositions = ConcurrentHashMap.newKeySet();
    public final Set<BlockPos> structurePortalPositions = ConcurrentHashMap.newKeySet();
    public final Set<BlockPos> chestPositions = ConcurrentHashMap.newKeySet();
    public final Set<BlockPos> consumedChests = ConcurrentHashMap.newKeySet();


    // current frontier connection points
    public final Set<BlockPos> claimed = ConcurrentHashMap.newKeySet();
    public final ArrayDeque<FrontierEntry> frontiers = new ArrayDeque<>();
    private final Map<BlockPos, List<FrontierEntry>> frontierGroups = new HashMap<>();
    private final Map<SchematicLoader.Schematic, Map<Long, List<ChunkBlockPlacement>>> chunkPlacementCache = new ConcurrentHashMap<>();
    public final Map<SchematicLoader.Schematic, int[]> extentsCache = new ConcurrentHashMap<>();
    public final Map<BlockPos, SchematicLoader.Schematic> roomOrigins = new ConcurrentHashMap<>();
    public final Map<BlockPos, BlockPos> roomParents = new ConcurrentHashMap<>();
    private final Map<BlockPos, Set<BlockPos>> roomAdjacency = new ConcurrentHashMap<>();
    public final Set<BlockPos> incompleteRooms = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<RoomPlacementJob> placementJobs = new ArrayDeque<>();
    private final Set<BlockPos> queuedPlacementRooms = new HashSet<>();

    // generated patched chunks
    public final Set<Long> committedChunks = ConcurrentHashMap.newKeySet();
    public final Set<Long> pendingChunks = ConcurrentHashMap.newKeySet();

    public final RoomSpatialIndex spatialIndex = new RoomSpatialIndex();
    private final Map<Long, List<SchematicLoader.Schematic>> candidateIndex = new HashMap<>();

    public final Set<BlockPos> persistedRooms = ConcurrentHashMap.newKeySet();
    public final Set<Long> stalePatchedChunks = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Long> staleChunkQueue = new ArrayDeque<>();
    private final Set<Long> queuedStaleChunks = ConcurrentHashMap.newKeySet();
    // Only these room/chunk pairs are eligible for live repair. Existing committed
    // chunks are left alone so later generation cannot overwrite player edits.
    private final Map<Long, Set<BlockPos>> pendingRoomChunks = new ConcurrentHashMap<>();

    // lookup table filtering schematics based on connection points available
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

    private static final int DEFAULT_RECENT_FAMILY_WINDOW = 10;
    private static final int DEFAULT_MINIMUM_ROOMS = 500;
    private static final int DEFAULT_STEPS_PER_TICK = 10;
    private static final int BLOCK_PLACEMENTS_PER_TICK = 512;
    private static final long BLOCK_PLACEMENT_BUDGET_NANOS = 2_000_000L;

    // default dimension json config
    public int playerSpawnGenerationY = 128;
    public int dimensionMinGenerationY = 0;
    public int dimensionMaxGenerationY = 384;
    public int radiusHorizontal = 256;
    public int radiusVertical = 64;

    public int minRooms = DEFAULT_MINIMUM_ROOMS;
    public int stepsPerTick = DEFAULT_STEPS_PER_TICK;
    public int roomGenerationDepth = 3;

    public BlockState fillSpaceState = Blocks.AIR.defaultBlockState();

    public FrontierChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    private static int recencyWindow() {
        return Config.LIMINALNESS_RECENCY_WINDOW.get();
    }

    private static int configuredMinimumRooms() {
        return Config.LIMINALNESS_MINIMUM_ROOMS.get();
    }

    private static int configuredStepsPerTick() {
        return Config.LIMINALNESS_STEPS_PER_TICK.get();
    }

    private static int configuredRoomGenerationDepth() {
        return Config.LIMINALNESS_ROOM_GENERATION_DEPTH.get();
    }

    private static int configuredTeleportRange() {
        return Config.LIMINALNESS_TELEPORT_RANGE.get();
    }

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

    private record ChunkBlockPlacement(
        BlockPos localPos,
        BlockState state,
        boolean chest
    ) {}

    private record ConnectionOwner(
        BlockPos roomOrigin,
        SchematicLoader.ConnectionPoint connection
    ) {}

    private enum PlacementStage {
        STRUCTURE,
        FUNCTIONAL_BLOCKS,
        BLOCK_ENTITIES,
        MARKERS,
        FINALIZE
    }

    private static final class RoomPlacementJob {
        private final BlockPos origin;
        private final SchematicLoader.Schematic schematic;
        private final List<ChunkBlockPlacement> structuralBlocks;
        private final List<ChunkBlockPlacement> functionalBlocks;
        private final Set<Long> touchedChunks = new HashSet<>();
        private PlacementStage stage = PlacementStage.STRUCTURE;
        private int cursor;

        private RoomPlacementJob(BlockPos origin, SchematicLoader.Schematic schematic,
                                 List<ChunkBlockPlacement> structuralBlocks,
                                 List<ChunkBlockPlacement> functionalBlocks) {
            this.origin = origin;
            this.schematic = schematic;
            this.structuralBlocks = structuralBlocks;
            this.functionalBlocks = functionalBlocks;
        }
    }

    // --- the usual initization things ---

    public void initialize(DimensionConfig dimensionConfig, long seed) {

        this.running = false;
        this.roomOrigins.clear();
        this.roomParents.clear();
        this.roomAdjacency.clear();
        this.incompleteRooms.clear();
        this.placementJobs.clear();
        this.queuedPlacementRooms.clear();
        this.claimed.clear();
        this.frontiers.clear();
        this.frontierGroups.clear();
        this.extentsCache.clear();
        this.chunkPlacementCache.clear();
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
        this.pendingRoomChunks.clear();

        candidateIndex.clear();

        worldSeed        = seed;
        playerSpawnGenerationY = dimensionConfig.generationY();
        dimensionMinGenerationY = dimensionConfig.minY();
        dimensionMaxGenerationY = dimensionConfig.maxY();
        fillSpaceState   = resolveFillSpace(dimensionConfig.fillSpace());
        radiusHorizontal = dimensionConfig.generationRadiusHorizontal();
        radiusVertical   = dimensionConfig.generationRadiusVertical();
        minRooms         = configuredMinimumRooms();
        stepsPerTick     = configuredStepsPerTick();
        roomGenerationDepth = configuredRoomGenerationDepth();

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

                if (entry.initialSpawn()) {
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

            candidateIndex.put(key, schemList);
        }

        liminalness.LOGGER.info("frontier generator - initialization complete in: {} with {} schematics and weights: {}", getDimensionId(), schematics.size(), weightedPool.size());

    }

    public boolean isReady() {
        return !schematics.isEmpty();
    }

    // --- runtime ---

    public void resume() {
        running = true;
        liminalness.LOGGER.info("frontier generator - {}: resumed - frontier: {} rooms: {}", getDimensionId(), frontiers.size(), roomOrigins.size());
    }

    public void pause() {
        running = false;
    }

    public void clearFrontier() {
        this.frontiers.clear();
        this.frontierGroups.clear();
    }

    public void resetStaleChunkTracking() {
        stalePatchedChunks.clear();
        staleChunkQueue.clear();
        queuedStaleChunks.clear();
        pendingRoomChunks.clear();
        pendingChunks.clear();
    }

    public void markChunkStale(long chunkKey) {
        if (stalePatchedChunks.add(chunkKey) && queuedStaleChunks.add(chunkKey)) {
            staleChunkQueue.addLast(chunkKey);
        }
    }

    /** Queues only the unresolved parts of a restored room for chunk repair. */
    public void queueUncommittedRoomChunks(BlockPos origin, SchematicLoader.Schematic schematic) {
        int[] extents = getExtents(schematic);
        int minChunkX = origin.getX() >> 4;
        int maxChunkX = (origin.getX() + extents[0] - 1) >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkZ = (origin.getZ() + extents[2] - 1) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long ck = chunkKey(chunkX, chunkZ);
                if (!committedChunks.contains(ck)) {
                    queueRoomChunk(origin, ck);
                }
            }
        }
    }

    private void queueRoomChunk(BlockPos origin, long chunkKey) {
        pendingRoomChunks
            .computeIfAbsent(chunkKey, ignored -> ConcurrentHashMap.newKeySet())
            .add(origin.immutable());
        committedChunks.remove(chunkKey);
        pendingChunks.add(chunkKey);
        markChunkStale(chunkKey);
    }

    public boolean hasPendingRoomChunk(long chunkKey) {
        Set<BlockPos> origins = pendingRoomChunks.get(chunkKey);
        return origins != null && !origins.isEmpty();
    }

    /** Applies only room data that was queued for this loaded chunk. */
    public boolean repairPendingChunk(int chunkX, int chunkZ) {
        return patchChunk(chunkX, chunkZ);
    }

    public void clearRoomGraph() {
        roomParents.clear();
        roomAdjacency.clear();
    }

    public void clearPlacementJobs() {
        incompleteRooms.clear();
        placementJobs.clear();
        queuedPlacementRooms.clear();
    }

    public void resumeIncompleteRoomPlacement(BlockPos origin, SchematicLoader.Schematic schematic) {
        queueRoomPlacement(origin, schematic, null);
    }

    public void registerRoomRoot(BlockPos origin) {
        roomParents.remove(origin);
        roomAdjacency.computeIfAbsent(origin.immutable(), ignored -> ConcurrentHashMap.newKeySet());
    }

    public void connectRooms(BlockPos parent, BlockPos child) {
        BlockPos stableParent = parent.immutable();
        BlockPos stableChild = child.immutable();
        if (stableParent.equals(stableChild)) return;

        roomParents.put(stableChild, stableParent);
        roomAdjacency.computeIfAbsent(stableParent, ignored -> ConcurrentHashMap.newKeySet()).add(stableChild);
        roomAdjacency.computeIfAbsent(stableChild, ignored -> ConcurrentHashMap.newKeySet()).add(stableParent);
    }

    /** Reconstructs graph edges for saves created before parent links were persisted. */
    public void rebuildRoomGraph() {
        clearRoomGraph();
        Map<BlockPos, List<ConnectionOwner>> connectionsByCorner = new HashMap<>();

        for (var room : roomOrigins.entrySet()) {
            BlockPos origin = room.getKey();
            registerRoomRoot(origin);
            for (SchematicLoader.ConnectionPoint connection : room.getValue().connectionPoints()) {
                BlockPos corner = origin.offset(connection.corner());
                connectionsByCorner.computeIfAbsent(corner, ignored -> new ArrayList<>())
                    .add(new ConnectionOwner(origin, connection));
            }
        }

        for (var room : roomOrigins.entrySet()) {
            BlockPos origin = room.getKey();
            for (SchematicLoader.ConnectionPoint connection : room.getValue().connectionPoints()) {
                BlockPos corner = origin.offset(connection.corner());
                List<ConnectionOwner> neighbors = connectionsByCorner.get(
                    corner.relative(connection.facing())
                );
                if (neighbors == null) continue;

                for (ConnectionOwner neighbor : neighbors) {
                    SchematicLoader.ConnectionPoint other = neighbor.connection();
                    if (!origin.equals(neighbor.roomOrigin())
                            && other.facing() == connection.facing().getOpposite()
                            && other.width() == connection.width()
                            && other.height() == connection.height()) {
                        roomAdjacency.get(origin).add(neighbor.roomOrigin());
                        roomAdjacency.get(neighbor.roomOrigin()).add(origin);
                    }
                }
            }
        }

        // Parent direction is only needed for persistence; traversal uses undirected edges.
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos root : roomOrigins.keySet()) {
            if (!visited.add(root)) continue;
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                BlockPos parent = queue.removeFirst();
                for (BlockPos child : roomAdjacency.getOrDefault(parent, Set.of())) {
                    if (visited.add(child)) {
                        roomParents.put(child, parent);
                        queue.addLast(child);
                    }
                }
            }
        }
    }

    private Set<BlockPos> activeFrontierSources(List<ServerPlayer> players) {
        Set<BlockPos> active = new HashSet<>();
        Map<BlockPos, Integer> bestDistance = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        for (ServerPlayer player : players) {
            BlockPos room = spatialIndex.findContainingRoom(player.blockPosition());
            if (room != null && bestDistance.putIfAbsent(room, 0) == null) {
                queue.addLast(room);
            }
        }

        while (!queue.isEmpty()) {
            BlockPos room = queue.removeFirst();
            int distance = bestDistance.get(room);
            if (distance >= roomGenerationDepth) continue;
            if (!incompleteRooms.contains(room)) active.add(room);

            int nextDistance = distance + 1;
            for (BlockPos neighbor : roomAdjacency.getOrDefault(room, Set.of())) {
                Integer previous = bestDistance.get(neighbor);
                if (previous == null || nextDistance < previous) {
                    bestDistance.put(neighbor, nextDistance);
                    queue.addLast(neighbor);
                }
            }
        }
        return active;
    }

    // --- schematic selection ---

    // new cluster of frontiers on random teleportation
    public void seedFresh() {
        if (this.schematics.isEmpty()) return;
        liminalness.LOGGER.info("seeding new generation for: {}", getDimensionId());

        // pick spawn room from weights with 1, otherwise completely random
        List<SchematicLoader.Schematic> pool = spawnPool.isEmpty() ? weightedPool : spawnPool;
        if (pool.isEmpty()) return;

        long hash = startingRoomHash();
        SchematicLoader.Schematic startSchema = pool.get((int) Long.remainderUnsigned(hash, pool.size()));

        long positionHash = hash;

        int spawnRange = Math.max(radiusHorizontal, configuredTeleportRange());

        positionHash = Long.rotateLeft(positionHash, 17) * 0x94D049BB133111EBL;
        int startCenterX = randomInRange(positionHash, -spawnRange, spawnRange);

        positionHash = Long.rotateLeft(positionHash, 17) * 0x94D049BB133111EBL;
        int startCenterZ = randomInRange(positionHash, -spawnRange, spawnRange);

        placeSeedRoom(startSchema, startCenterX, startCenterZ);
    }

    public void seedAt(int startCenterX, int startCenterZ) {
        if (this.schematics.isEmpty()) return;
        liminalness.LOGGER.info("seeding new generation for: {}", getDimensionId());

        SchematicLoader.Schematic startSchema = selectStartingSchematic();
        if (startSchema == null) return;

        placeSeedRoom(startSchema, startCenterX, startCenterZ);
    }

    // verify portal or bed goes to a spawn location
    public Vec3 ensureLinkedSpawn(int startCenterX, int startCenterZ) {

        SchematicLoader.Schematic startSchema = selectStartingSchematic();
        if (startSchema == null) {
            return new Vec3(0.5, playerSpawnGenerationY, 0.5);
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

    private void placeSeedRoom(SchematicLoader.Schematic startSchema, int startCenterX, int startCenterZ) {
        BlockPos startPos = originForCenter(startSchema, startCenterX, startCenterZ);

        startingRoomOrigin = startPos;
        roomOrigins.put(startPos, startSchema);
        spatialIndex.add(startPos, getExtents(startSchema));
        registerRoomRoot(startPos);
        registerBlockMarkers(startPos, startSchema);
        seedFrontier(startPos, startSchema);
        recordPlacedFamily(startSchema);
        resume();

        liminalness.LOGGER.info("{}: seeded with {} at {}", getDimensionId(), getPathBySchematic(startSchema), startPos);
    }

    // place new disconnected room with immediate new frontier
    private void placeDisconnectedSeed(BlockPos origin, SchematicLoader.Schematic schematic) {
        roomOrigins.put(origin, schematic);
        spatialIndex.add(origin, getExtents(schematic));
        registerRoomRoot(origin);
        recordPlacedFamily(schematic);
        queueRoomPlacement(origin, schematic, null);
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

    // direct room center
    private BlockPos originForCenter(SchematicLoader.Schematic schematic, int centerX, int centerZ) {
        int[] extents = getExtents(schematic);
        return new BlockPos(
            centerX - (extents[0] / 2),
            playerSpawnGenerationY - (extents[1] / 2),
            centerZ - (extents[2] / 2)
        );
    }

    // place player at direct room center
    private Vec3 getSpawnPositionForRoom(BlockPos origin, SchematicLoader.Schematic schematic) {
        int[] extents = getExtents(schematic);
        return new Vec3(
            origin.getX() + (extents[0] / 2.0) + 0.5,
                playerSpawnGenerationY,
            origin.getZ() + (extents[2] / 2.0) + 0.5
        );
    }

    // matching X/Z hashed center
    private BlockPos findRoomContaining(int centerX, int centerZ) {
        BlockPos[] found = new BlockPos[1];
        spatialIndex.anyRoomInChunk(centerX, centerX + 1, centerZ, centerZ + 1, origin -> {
            SchematicLoader.Schematic schematic = roomOrigins.get(origin);
            if (schematic == null) {
                return false;
            }
            int[] extents = getExtents(schematic);

            if (centerX < origin.getX() || centerX >= origin.getX() + extents[0]) {
                return false;
            }
            if (centerZ < origin.getZ() || centerZ >= origin.getZ() + extents[2]) {
                return false;
            }

            found[0] = origin;
            return true;
        });
        return found[0];
    }

    // if overlap currently placed rooms
    private BlockPos findOverlappingRoom(SchematicLoader.Schematic candidate, BlockPos origin) {

        int[] candidateExtents = getExtents(candidate);
        int minX = origin.getX();
        int maxX = origin.getX() + candidateExtents[0];
        int minZ = origin.getZ();
        int maxZ = origin.getZ() + candidateExtents[2];

        BlockPos[] found = new BlockPos[1];
        spatialIndex.anyRoomInChunk(minX, maxX, minZ, maxZ, existingOrigin -> {
            SchematicLoader.Schematic existing = roomOrigins.get(existingOrigin);
            if (existing == null) {
                return false;
            }
            int[] existingExtents = getExtents(existing);
            if (boxesOverlap(origin, candidateExtents, existingOrigin, existingExtents)) {
                found[0] = existingOrigin;
                return true;
            }
            return false;
        });
        return found[0];

    }

    //AABB helper
    private boolean boxesOverlap(BlockPos aOrigin, int[] aExtents, BlockPos bOrigin, int[] bExtents) {
        return aOrigin.getX() < bOrigin.getX() + bExtents[0]
            && aOrigin.getX() + aExtents[0] > bOrigin.getX()
            && aOrigin.getY() < bOrigin.getY() + bExtents[1]
            && aOrigin.getY() + aExtents[1] > bOrigin.getY()
            && aOrigin.getZ() < bOrigin.getZ() + bExtents[2]
            && aOrigin.getZ() + aExtents[2] > bOrigin.getZ();
    }

    // --- restart frontier ---

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
            registerRoomRoot(candidateOrigin);
            recordPlacedFamily(candidate);
            queueRoomPlacement(candidateOrigin, candidate, null);

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
                    playerSpawnGenerationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ()
                );
                case 1 -> new BlockPos(
                    anchorOrigin.getX() - candidateExtents[0] - gap,
                    playerSpawnGenerationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ()
                );
                case 2 -> new BlockPos(
                    anchorOrigin.getX(),
                    playerSpawnGenerationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ() + anchorExtents[2] + gap
                );
                default -> new BlockPos(
                    anchorOrigin.getX(),
                    playerSpawnGenerationY - (candidateExtents[1] / 2),
                    anchorOrigin.getZ() - candidateExtents[2] - gap
                );
            };

            if (candidateOrigin.getY() < dimensionMinGenerationY || candidateOrigin.getY() + candidateExtents[1] > dimensionMaxGenerationY + 1) {
                continue;
            }

            if (!overlapsAny(candidate, candidateOrigin)) {
                return candidateOrigin;
            }
        }

        return null;
    }

    // --- frontier creation ---

    // main driver for frontier expansion
    public void tick() {

        if (serverLevel == null) return;
        List<ServerPlayer> players = serverLevel.players();
        if (players.isEmpty()) return;
        List<BlockPos> playerPositions = players.stream().map(p -> p.blockPosition()).toList();
        if (needsSeed && isReady() && roomOrigins.isEmpty()) {
            needsSeed = false;
            seedFresh();
            return;
        }
        if (!running) return;
        processPlacementJobs();

        if (frontiers.isEmpty() && placementJobs.isEmpty() && !roomOrigins.isEmpty() && isReady()) {
            restartFromDisconnectedSeed();
        }

        if (!stalePatchedChunks.isEmpty()) {
            processStaleChunks(playerPositions);
        }

        // expand frontier processing
        Set<BlockPos> activeSources = activeFrontierSources(players);
        if (activeSources.isEmpty()) return;
        int processed = 0;
        int scanned = 0;
        int scanLimit = Math.min(frontiers.size(), stepsPerTick * 8);
        List<FrontierEntry> deferred = null;

        while (processed < stepsPerTick
                && placementJobs.size() < stepsPerTick
                && scanned < scanLimit
                && !frontiers.isEmpty()) {
            List<FrontierEntry> competingEntries = pollFrontierGroup();
            if (competingEntries == null) {
                continue;
            }
            scanned++;

            FrontierEntry selectedEntry = chooseCompetingFrontier(competingEntries);

            if (claimed.contains(selectedEntry.attachPoint())) continue;

            if (activeSources.contains(selectedEntry.sourceRoomOrigin())) {
                expandFrontier(selectedEntry);
                processed++;
            } else {
                if (deferred == null) deferred = new ArrayList<>();
                deferred.addAll(competingEntries);
            }
        }

        if (deferred != null) enqueueFrontiers(deferred);
    }

    // add exposed connection plains to the frontier queue
    public void seedFrontier(BlockPos origin, SchematicLoader.Schematic schematic) {
        for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {

            BlockPos worldCorner = origin.offset(connectionPoint.corner());
            BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);

            for (int level : getLevelsForSchematic(schematic)) {
                if (!claimed.contains(attachPoint)) {
                    enqueueFrontier(new FrontierEntry(origin, attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.patternHash(), connectionPoint.pattern().clone(), level));
                }
            }

        }
    }

    // rebuild frontier from save
    public void reconstructFrontier() {
        this.frontiers.clear();
        this.frontierGroups.clear();
        for (var entry : this.roomOrigins.entrySet()) {
            BlockPos origin = entry.getKey();
            if (incompleteRooms.contains(origin)) continue;
            SchematicLoader.Schematic schematic = entry.getValue();
            for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {
                BlockPos worldCorner = origin.offset(connectionPoint.corner());
                BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
                for (int level : getLevelsForSchematic(schematic)) {
                    if (!this.claimed.contains(attachPoint)) {
                        enqueueFrontier(new FrontierEntry(origin, attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.patternHash(), connectionPoint.pattern().clone(), level));
                    }
                }
            }
        }
        liminalness.LOGGER.info("frontier generator - {} open connections from {} rooms", frontiers.size(), roomOrigins.size());
    }



    // --- chunk repairs and stale patching ---

    /** Repairs a bounded number of stale chunks that are currently near players. */
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

    // revisit chunks that were generated too early
    private boolean patchChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        Set<BlockPos> origins = pendingRoomChunks.get(key);
        if (origins == null || origins.isEmpty()) {
            return true;
        }

        boolean allResolved = true;
        for (BlockPos origin : List.copyOf(origins)) {
            if (incompleteRooms.contains(origin)) {
                allResolved = false;
                continue;
            }
            SchematicLoader.Schematic schematic = roomOrigins.get(origin);
            if (schematic == null) {
                allResolved = false;
                continue;
            }

            int relativeChunkX = chunkX - (origin.getX() >> 4);
            int relativeChunkZ = chunkZ - (origin.getZ() >> 4);
            for (ChunkBlockPlacement placement : getChunkPlacements(schematic, relativeChunkX, relativeChunkZ)) {
                BlockPos world = origin.offset(placement.localPos());
                if (!serverLevel.isLoaded(world)) {
                    allResolved = false;
                    continue;
                }
                serverLevel.setBlock(world, placement.state(), Block.UPDATE_CLIENTS);
                if (placement.chest()) {
                    scheduleChestFill(world.immutable(), 0);
                }
            }
        }

        if (allResolved) {
            pendingRoomChunks.remove(key);
        }
        return allResolved;
    }

    private List<ChunkBlockPlacement> getChunkPlacements(SchematicLoader.Schematic schematic, int relativeChunkX, int relativeChunkZ) {
        Map<Long, List<ChunkBlockPlacement>> placementsByChunk = chunkPlacementCache.computeIfAbsent(schematic, this::buildChunkPlacementCache);
        return placementsByChunk.getOrDefault(chunkKey(relativeChunkX, relativeChunkZ), List.of());
    }

    private Map<Long, List<ChunkBlockPlacement>> buildChunkPlacementCache(SchematicLoader.Schematic schematic) {
        Map<Long, List<ChunkBlockPlacement>> placementsByChunk = new HashMap<>();

        for (var block : schematic.finalBlocks().entrySet()) {
            BlockPos local = block.getKey();
            long relativeChunkKey = chunkKey(local.getX() >> 4, local.getZ() >> 4);
            placementsByChunk.computeIfAbsent(relativeChunkKey, ignored -> new ArrayList<>()).add(
                new ChunkBlockPlacement(
                    local.immutable(),
                    block.getValue(),
                    schematic.chestPositions().contains(local)
                )
            );
        }

        return placementsByChunk;
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


    private void enqueueFrontier(FrontierEntry entry) {
        List<FrontierEntry> group = frontierGroups.get(entry.attachPoint());
        if (group == null) {
            group = new ArrayList<>();
            frontierGroups.put(entry.attachPoint(), group);
            frontiers.addLast(entry);
        }
        group.add(entry);
    }

    private void enqueueFrontiers(Collection<FrontierEntry> entries) {
        for (FrontierEntry entry : entries) {
            enqueueFrontier(entry);
        }
    }

    private List<FrontierEntry> pollFrontierGroup() {
        while (!frontiers.isEmpty()) {
            FrontierEntry representative = frontiers.pollFirst();
            List<FrontierEntry> group = frontierGroups.remove(representative.attachPoint());
            if (group != null && !group.isEmpty()) {
                return group;
            }
        }
        return null;
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

    // --- candidate selection ---

    // main driver for choosing and placing the room
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

            if (!getLevelsForSchematic(candidate).contains(entry.level())) {
                continue;
            }

            List<SchematicLoader.ConnectionPoint> matches = getMatchingConnectionPoints(candidate, entry);
            if (matches == null) continue;

            for (SchematicLoader.ConnectionPoint matchingConnectionPoint : matches) {
                BlockPos candidateOrigin = entry.attachPoint().subtract(matchingConnectionPoint.corner());

                int[] extents = getExtents(candidate);
                if (candidateOrigin.getY() < dimensionMinGenerationY || candidateOrigin.getY() + extents[1] > dimensionMaxGenerationY + 1) continue;

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

    // pick off of weight
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
        connectRooms(entry.sourceRoomOrigin(), candidateOrigin);
        recordPlacedFamily(candidate);
        queueRoomPlacement(candidateOrigin, candidate, entry.attachPoint());
    }

    /** Reserves a room and builds entrance-first placement slices without exposing its frontiers. */
    private void queueRoomPlacement(BlockPos origin, SchematicLoader.Schematic schematic, BlockPos entranceWorld) {
        BlockPos stableOrigin = origin.immutable();
        incompleteRooms.add(stableOrigin);
        if (!queuedPlacementRooms.add(stableOrigin)) return;

        BlockPos focus = entranceWorld == null
            ? new BlockPos(schematic.extentX() / 2, schematic.extentY() / 2, schematic.extentZ() / 2)
            : entranceWorld.subtract(stableOrigin);
        Comparator<ChunkBlockPlacement> entranceFirst = Comparator
            .comparingInt((ChunkBlockPlacement placement) -> sectionDistance(placement.localPos(), focus))
            .thenComparingInt(placement -> blockDistanceSquared(placement.localPos(), focus));

        List<ChunkBlockPlacement> structural = new ArrayList<>();
        List<ChunkBlockPlacement> functional = new ArrayList<>();
        for (var block : schematic.finalBlocks().entrySet()) {
            BlockPos local = block.getKey().immutable();
            ChunkBlockPlacement placement = new ChunkBlockPlacement(
                local,
                block.getValue(),
                schematic.chestPositions().contains(local)
            );
            if (schematic.blockEntityData().containsKey(local) || block.getValue().hasBlockEntity()) {
                functional.add(placement);
            } else {
                structural.add(placement);
            }
        }
        structural.sort(entranceFirst);
        functional.sort(entranceFirst);
        placementJobs.addLast(new RoomPlacementJob(stableOrigin, schematic, structural, functional));
    }

    private static int sectionDistance(BlockPos pos, BlockPos focus) {
        return Math.abs((pos.getX() >> 4) - (focus.getX() >> 4))
            + Math.abs((pos.getY() >> 4) - (focus.getY() >> 4))
            + Math.abs((pos.getZ() >> 4) - (focus.getZ() >> 4));
    }

    private static int blockDistanceSquared(BlockPos pos, BlockPos focus) {
        int dx = pos.getX() - focus.getX();
        int dy = pos.getY() - focus.getY();
        int dz = pos.getZ() - focus.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Advances placement jobs under both a block-count cap and a wall-clock tick budget. */
    private void processPlacementJobs() {
        if (placementJobs.isEmpty()) return;

        long deadline = System.nanoTime() + BLOCK_PLACEMENT_BUDGET_NANOS;
        int remainingBlocks = BLOCK_PLACEMENTS_PER_TICK;

        while (!placementJobs.isEmpty()) {
            RoomPlacementJob job = placementJobs.peekFirst();
            if (job.stage == PlacementStage.STRUCTURE || job.stage == PlacementStage.FUNCTIONAL_BLOCKS) {
                List<ChunkBlockPlacement> placements = job.stage == PlacementStage.STRUCTURE
                    ? job.structuralBlocks
                    : job.functionalBlocks;
                while (job.cursor < placements.size() && remainingBlocks > 0 && System.nanoTime() < deadline) {
                    writePlacementBlock(job, placements.get(job.cursor++));
                    remainingBlocks--;
                }
                if (job.cursor < placements.size()) return;
                job.cursor = 0;
                job.stage = job.stage == PlacementStage.STRUCTURE
                    ? PlacementStage.FUNCTIONAL_BLOCKS
                    : PlacementStage.BLOCK_ENTITIES;
                if (remainingBlocks == 0 || System.nanoTime() >= deadline) return;
                continue;
            }

            if (job.stage == PlacementStage.BLOCK_ENTITIES) {
                applyBlockEntities(job.origin, job.schematic);
                for (BlockPos local : job.schematic.chestPositions()) {
                    BlockPos world = job.origin.offset(local).immutable();
                    if (serverLevel.isLoaded(world)) scheduleChestFill(world, 0);
                }
                job.stage = PlacementStage.MARKERS;
                continue;
            }

            if (job.stage == PlacementStage.MARKERS) {
                registerBlockMarkers(job.origin, job.schematic);
                job.stage = PlacementStage.FINALIZE;
                continue;
            }

            // ServerLevel#setBlock has already queued light and client changes. Commit
            // bookkeeping only after all visible and functional room data exists.
            for (long chunkKey : job.touchedChunks) {
                if (!hasPendingRoomChunk(chunkKey)) {
                    committedChunks.add(chunkKey);
                    pendingChunks.remove(chunkKey);
                }
            }
            incompleteRooms.remove(job.origin);
            queuedPlacementRooms.remove(job.origin);
            seedFrontier(job.origin, job.schematic);
            placementJobs.removeFirst();
        }
    }

    private void writePlacementBlock(RoomPlacementJob job, ChunkBlockPlacement placement) {
        BlockPos world = job.origin.offset(placement.localPos());
        int chunkX = world.getX() >> 4;
        int chunkZ = world.getZ() >> 4;
        long chunkKey = chunkKey(chunkX, chunkZ);
        if (!serverLevel.hasChunk(chunkX, chunkZ)) {
            queueRoomChunk(job.origin, chunkKey);
            return;
        }

        serverLevel.setBlock(world, placement.state(), Block.UPDATE_CLIENTS);
        job.touchedChunks.add(chunkKey);
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

    // --- write to chunks literal world ---

    // main driver for writing the blocks to the world
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager,
                                                        ChunkAccess chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxX = minX + 16;
        int maxZ = minZ + 16;

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int fillMinY = Math.max(dimensionMinGenerationY, chunk.getMinBuildHeight());
        int fillMaxY = Math.min(dimensionMaxGenerationY, chunk.getMaxBuildHeight() - 1);

        if (fillMinY <= fillMaxY) {
            for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                int sectionMinY = sectionY << 4;
                int sectionMaxY = sectionMinY + 15;
                int localMinY = Math.max(fillMinY, sectionMinY) - sectionMinY;
                int localMaxY = Math.min(fillMaxY, sectionMaxY) - sectionMinY;
                if (localMinY > localMaxY) continue;

                LevelChunkSection current = chunk.getSection(sectionIndex);
                if (localMinY == 0 && localMaxY == 15) {
                    PalettedContainer<BlockState> states = new PalettedContainer<>(
                        Block.BLOCK_STATE_REGISTRY,
                        fillSpaceState,
                        PalettedContainer.Strategy.SECTION_STATES
                    );
                    chunk.getSections()[sectionIndex] = new LevelChunkSection(states, current.getBiomes());
                    continue;
                }

                current.acquire();
                try {
                    PalettedContainer<BlockState> states = current.getStates();
                    for (int localY = localMinY; localY <= localMaxY; localY++) {
                        for (int localZ = 0; localZ < 16; localZ++) {
                            for (int localX = 0; localX < 16; localX++) {
                                states.getAndSetUnchecked(localX, localY, localZ, fillSpaceState);
                            }
                        }
                    }
                } finally {
                    current.release();
                }
                current.recalcBlockCounts();
            }
            chunk.setUnsaved(true);
        }

        boolean[] allResolved = {true};

        spatialIndex.anyRoomInChunk(minX, maxX, minZ, maxZ, origin -> {
            SchematicLoader.Schematic schematic = roomOrigins.get(origin);
            if (schematic == null) {
                allResolved[0] = false;
                return false;
            }

            for (var block : schematic.finalBlocks().entrySet()) {
                BlockPos world = origin.offset(block.getKey());
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                mutable.set(world);
                chunk.setBlockState(mutable, block.getValue(), false);
            }
            return false;
        });

        Heightmap.primeHeightmaps(chunk, EnumSet.of(
            Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.WORLD_SURFACE_WG
        ));

        long ck = chunkKey(chunk.getPos().x, chunk.getPos().z);
        if (allResolved[0]) {
            committedChunks.add(ck);
            pendingChunks.remove(ck);
            pendingRoomChunks.remove(ck);
            stalePatchedChunks.remove(ck);
        } else {
            pendingChunks.add(ck);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // retry filling chest during generation in case block entity race condition
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

    // specific reserved blocks with custom function and interactions
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

    // fill block entities from schematics
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

    // --- schematic property restrictions ---

    // whether can connect itself from config
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

    // schematic level property
    private Set<Integer> getLevelsForSchematic(SchematicLoader.Schematic schematic) {
        return schematicLevels.getOrDefault(schematic, Set.of(1));
    }

    // filter a rooms connections points down to those compatible with frontier
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

    // canonical match if opposite facing placed in real world
    private boolean requiresLiteralMatch(BlockPos sourceRoomOrigin, SchematicLoader.Schematic candidate) {
        SchematicLoader.Schematic source = roomOrigins.get(sourceRoomOrigin);
        boolean sourceLiteralMatch = source != null && schematicLiteralMatches.getOrDefault(source, true);
        boolean candidateLiteralMatch = schematicLiteralMatches.getOrDefault(candidate, true);
        return sourceLiteralMatch || candidateLiteralMatch;
    }

    // connection plane transformtion for literal match test
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

    // calculate penalize schematic weight
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

    // record placed family into history record
    private void recordPlacedFamily(SchematicLoader.Schematic schematic) {
        String family = schematicFamilies.get(schematic);
        if (family == null) {
            return;
        }

        recentPlacedFamilies.addLast(family);
        recentFamilyCounts.merge(family, 1, Integer::sum);

        while (recentPlacedFamilies.size() > recencyWindow()) {
            String expired = recentPlacedFamilies.removeFirst();
            recentFamilyCounts.computeIfPresent(expired, (ignored, count) -> count > 1 ? count - 1 : null);
        }
    }

    private String schematicFamily(String path) {
        int variantSeparator = path.indexOf('#');
        return variantSeparator >= 0 ? path.substring(0, variantSeparator) : path;
    }

    // --- utils ---

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

    // range selection
    private int randomInRange(long hash, int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        long span = (long) maxInclusive - minInclusive + 1L;
        return minInclusive + (int) Long.remainderUnsigned(hash, span);
    }

    // fill space chunk
    private BlockState resolveFillSpace(String fillSpace) {
        ResourceLocation blockId = ResourceLocation.tryParse(fillSpace);
        if (blockId == null) {
            liminalness.LOGGER.warn("frontier generator - invalid fill_space '{}' for {}, defaulting to minecraft:smooth_sandstone", fillSpace, getDimensionId());
            return Blocks.AIR.defaultBlockState();
        }

        Block block = BuiltInRegistries.BLOCK.get(blockId);
        if (block == Blocks.AIR && !blockId.equals(BuiltInRegistries.BLOCK.getKey(Blocks.AIR))) {
            liminalness.LOGGER.warn("frontier generator - unknown fill_space '{}' for {}, defaulting to minecraft:smooth_sandstone", fillSpace, getDimensionId());
            return Blocks.AIR.defaultBlockState();
        }

        return block.defaultBlockState();
    }

    // starting spawn position
    public Vec3 getStartingSpawnPosition() {
        BlockPos origin = startingRoomOrigin;
        if (origin == null) {
            return new Vec3(0.5, playerSpawnGenerationY, 0.5);
        }

        SchematicLoader.Schematic schematic = roomOrigins.get(origin);
        if (schematic == null) {
            return new Vec3(origin.getX() + 0.5, playerSpawnGenerationY, origin.getZ() + 0.5);
        }

        return getSpawnPositionForRoom(origin, schematic);
    }

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
        return playerSpawnGenerationY;
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

