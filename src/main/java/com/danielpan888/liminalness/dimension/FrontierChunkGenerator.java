package com.danielpan888.liminalness.dimension;

import com.danielpan888.liminalness.liminalness;
import com.danielpan888.liminalness.util.ChestLootHandler;
import com.danielpan888.liminalness.util.DimensionConfig;
import com.danielpan888.liminalness.util.RoomSpatialIndex;
import com.danielpan888.liminalness.util.SchematicLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class FrontierChunkGenerator extends ChunkGenerator {

    public abstract ResourceLocation getDimensionId();
    public volatile ServerLevel serverLevel;
    public volatile boolean running = false;
    public boolean needsSeed = false;
    public long worldSeed;
    public boolean initialized = false;

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

    public record FrontierEntry(
            BlockPos attachPoint,
            Direction incomingFacing,
            int width,
            int height,
            Block markerBlock
    ) {}

    public FrontierChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
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
        this.weightedPool.clear();
        this.spatialIndex.clear();

        candidateIndex.clear();
        candidateCumulativeWeights.clear();

        worldSeed        = seed;
        generationY      = dimensionConfig.generationY();
        minGenerationY   = dimensionConfig.minY();
        maxGenerationY   = dimensionConfig.maxY();
        radiusHorizontal = dimensionConfig.generationRadiusHorizontal();
        radiusVertical   = dimensionConfig.generationRadiusVertical();
        stepsPerTick     = dimensionConfig.stepsPerTick();
        minRooms         = dimensionConfig.minRooms();

        for (DimensionConfig.SchematicEntry entry : dimensionConfig.schematics()) {
            SchematicLoader.Schematic s = entry.schematic();
            schematics.add(s);
            schematicPaths.put(s, entry.path());
            pathToSchematic.put(entry.path(), s);

            if (entry.weight() == 0) {
                continue;
            }

            if (entry.weight() == 1) {
                spawnPool.add(s);
            }

            for (int i = 0; i < entry.weight(); i++) {
                this.weightedPool.add(s);
            }
        }

        spatialIndex.clear();
        for (var entry : roomOrigins.entrySet()) {
            spatialIndex.add(entry.getKey(), getExtents(entry.getValue()));
        }

        Map<Long, Map<SchematicLoader.Schematic, Integer>> uniqueByKey = new HashMap<>();

        for (DimensionConfig.SchematicEntry entry : dimensionConfig.schematics()) {
            if (entry.weight() == 0) continue;
            SchematicLoader.Schematic s = entry.schematic();
            for (SchematicLoader.ConnectionPoint connectionPoint : s.connectionPoints()) {
                long key = connectionSignature(connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock());
                uniqueByKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(s, entry.weight(), Math::max);
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
        BlockPos startPos = new BlockPos(
            -(extents[0] / 2),
            generationY - (extents[1] / 2),
            -(extents[2] / 2)
        );

        roomOrigins.put(startPos, startSchema);
        spatialIndex.add(startPos, getExtents(startSchema));
        registerBlockMarkers(startPos, startSchema);
        seedFrontier(startPos, startSchema);
        resume();

        liminalness.LOGGER.info("{}: seeded with {} at {}", getDimensionId(), getPathBySchematic(startSchema), startPos);
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

        if (!running || serverLevel == null) return;
        if (needsSeed && isReady() && roomOrigins.isEmpty()) {
            needsSeed = false;
            seedFresh();
            return;
        }

        List<BlockPos> playerPositions = serverLevel.players().stream()
                .map(p -> p.blockPosition()).toList();
        if (playerPositions.isEmpty()) return;

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
                                BlockPos world = origin.offset(block.getKey());
                                if (world.getX() < minX || world.getX() >= maxX) continue;
                                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                                if (!serverLevel.isLoaded(world)) { allResolved = false; continue; }
                                serverLevel.setBlock(world, block.getValue(), Block.UPDATE_CLIENTS);
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
        int[] cumulative = candidateCumulativeWeights.get(key);

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
        int size = candidates.size();
        int totalWeight = cumulative[size - 1];

        hash = Long.rotateLeft(hash, 17) * 0x94D049BB133111EBL;
        int roll = (int) Long.remainderUnsigned(hash, totalWeight);
        int startIndex = Arrays.binarySearch(cumulative, roll);
        if (startIndex < 0) startIndex = ~startIndex;

        for (int i = 0; i < size; i++) {

            SchematicLoader.Schematic candidate = candidates.get((startIndex + i) % size);


            List<SchematicLoader.ConnectionPoint> matches = candidate.connectionPointIndex().get(key);
            if (matches == null) continue;

            for (SchematicLoader.ConnectionPoint matchingConnectionPoint : matches) {
                BlockPos candidateOrigin = entry.attachPoint().subtract(matchingConnectionPoint.corner());

                int[] extents = getExtents(candidate);
                if (candidateOrigin.getY() < minGenerationY ||
                        candidateOrigin.getY() + extents[1] > maxGenerationY) continue;

                if (overlapsAny(candidate, candidateOrigin)) continue;

                if (needsConnections && countNewConnections(candidate, candidateOrigin) == 0) continue;

                claimed.add(entry.attachPoint());
                roomOrigins.put(candidateOrigin, candidate);
                spatialIndex.add(candidateOrigin, getExtents(candidate));
                writeToWorld(candidateOrigin, candidate);
                registerBlockMarkers(candidateOrigin, candidate);

                for (SchematicLoader.ConnectionPoint connectionPoint : candidate.connectionPoints()) {
                    BlockPos worldCorner = candidateOrigin.offset(connectionPoint.corner());
                    BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
                    if (!claimed.contains(attachPoint)) {
                        frontiers.add(new FrontierEntry(attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock()));
                    }
                }
            }

            return;
        }

        claimed.add(entry.attachPoint());
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
            BlockPos world = origin.offset(block.getKey());
            if (!serverLevel.isLoaded(world)) continue;
            serverLevel.setBlock(world, block.getValue(), Block.UPDATE_CLIENTS);
        }

        for (BlockPos local : schematic.chestPositions()) {
            BlockPos world = origin.offset(local).immutable();
            if (consumedChests.contains(world)) continue;
            consumedChests.add(world);
            scheduleChestFill(world, 0);
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
                    chunk.setBlockState(mutable, Blocks.SMOOTH_SANDSTONE.defaultBlockState(), false);
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

