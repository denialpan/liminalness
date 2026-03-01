package com.danielpan888.liminalness.dimension;

import com.danielpan888.liminalness.liminalness;
import com.danielpan888.liminalness.util.ChestLootHandler;
import com.danielpan888.liminalness.util.DimensionConfig;
import com.danielpan888.liminalness.util.SchematicLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    public static final Block PORTAL_MARKER = Blocks.END_PORTAL_FRAME;
    public final Set<BlockPos> chestPositions = ConcurrentHashMap.newKeySet();
    public static final Block CHEST_MARKER = Blocks.ORANGE_WOOL;

    public final Set<BlockPos> consumedChests = ConcurrentHashMap.newKeySet();

    public final Map<BlockPos, SchematicLoader.Schematic> roomOrigins = new ConcurrentHashMap<>();
    public final Set<BlockPos> claimed = ConcurrentHashMap.newKeySet();
    public final ArrayDeque<FrontierEntry> frontiers = new ArrayDeque<>();
    public final Map<SchematicLoader.Schematic, int[]> extentsCache = new ConcurrentHashMap<>();
    public final Set<Long> patchedChunks = ConcurrentHashMap.newKeySet();

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
        int height
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
        registerBlockMarkers(startPos, startSchema);
        seedFrontier(startPos, startSchema);
        resume();

        liminalness.LOGGER.info("{}: seeded with {} at {}", getDimensionId(), getPathBySchematic(startSchema), startPos);
    }

    public void seedFrontier(BlockPos origin, SchematicLoader.Schematic schematic) {
        for (SchematicLoader.ConnectionPoint connectionPoint : schematic.connectionPoints()) {
            BlockPos worldCorner = origin.offset(connectionPoint.corner());
            BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
            if (!this.claimed.contains(attachPoint)) {
                this.frontiers.add(new FrontierEntry(attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height()));
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
                    this.frontiers.add(new FrontierEntry(
                            attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height()
                    ));
                }
            }
        }

        liminalness.LOGGER.info("frontier generator - {} open connections from {} rooms", this.frontiers.size(), roomOrigins.size());
    }


    public void tick() {

        if (!running || serverLevel == null) return;
        if (needsSeed && isReady() && roomOrigins.isEmpty()) {
            needsSeed = false;
            seedFresh();
            return;
        }

        List<BlockPos> playerPositions = serverLevel.players().stream().map(p -> p.blockPosition()).toList();
        if (playerPositions.isEmpty()) return;

        int checked   = 0;
        int processed = 0;
        int queueSize = frontiers.size();

        while (!frontiers.isEmpty() && processed < stepsPerTick && checked < queueSize) {
            FrontierEntry entry = frontiers.poll();
            checked++;
            if (claimed.contains(entry.attachPoint())) continue;

            boolean inRange = roomOrigins.size() < minRooms || isInRange(entry.attachPoint(), playerPositions);

            if (inRange) {
                expandFrontier(entry);
                processed++;
            } else {
                frontiers.add(entry);
            }
        }

    }

    private boolean isInRange(BlockPos pos, List<BlockPos> players) {
        for (BlockPos player : players) {
            if (
                Math.abs(pos.getX() - player.getX()) <= radiusHorizontal &&
                Math.abs(pos.getZ() - player.getZ()) <= radiusHorizontal &&
                Math.abs(pos.getY() - player.getY()) <= radiusVertical)
            {
                return true;
            }
        }
        return false;
    }

    private void expandFrontier(FrontierEntry entry) {

        if (claimed.contains(entry.attachPoint())) return;

        int attachY = entry.attachPoint().getY();
        if (attachY < minGenerationY || attachY > maxGenerationY) {
            claimed.add(entry.attachPoint());
            return;
        }

        // all candidates that match this connection
        List<SchematicLoader.Schematic> candidates = weightedPool.stream()
            .filter(s -> s.connectionPoints().stream()
            .anyMatch(connectionPoint -> connectionPoint.facing() == entry.incomingFacing().getOpposite()
                && connectionPoint.width() == entry.width()
                && connectionPoint.height() == entry.height()))
            .toList();

        if (candidates.isEmpty()) {
            claimed.add(entry.attachPoint());
            return;
        }

        long hash = worldSeed;
        hash ^= (long) entry.attachPoint().getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) entry.attachPoint().getY() * 0x6C62272E07BB0142L;
        hash ^= (long) entry.attachPoint().getZ() * 0xD2A98B26625EEE7BL;
        hash  = Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;

        List<SchematicLoader.Schematic> shuffled = new ArrayList<>(candidates);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            hash = Long.rotateLeft(hash, 17) * 0x94D049BB133111EBL;
            int j = (int) Long.remainderUnsigned(hash, i + 1);
            SchematicLoader.Schematic tmp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, tmp);
        }

        for (SchematicLoader.Schematic candidate : shuffled) {
            SchematicLoader.ConnectionPoint matchingConnectionPoint = candidate.connectionPoints().stream()
                .filter(connectionPoint -> connectionPoint.facing()  == entry.incomingFacing().getOpposite()
                    && connectionPoint.width()   == entry.width()
                    && connectionPoint.height()  == entry.height())
                .findFirst().orElse(null);

            if (matchingConnectionPoint == null) continue;

            BlockPos candidateOrigin = entry.attachPoint().subtract(matchingConnectionPoint.corner());

            int[] extents = getExtents(candidate);
            if (candidateOrigin.getY() < minGenerationY ||
                    candidateOrigin.getY() + extents[1] > maxGenerationY) continue;

            if (overlapsAny(candidate, candidateOrigin)) continue;

            if (roomOrigins.size() < minRooms) {
                int newConnections = countNewConnections(candidate, candidateOrigin);
                if (newConnections == 0) {
                    continue;
                }
            }

            claimed.add(entry.attachPoint());
            roomOrigins.put(candidateOrigin, candidate);
            writeToWorld(candidateOrigin, candidate);
            registerBlockMarkers(candidateOrigin, candidate);

            for (SchematicLoader.ConnectionPoint connectionPoint : candidate.connectionPoints()) {
                BlockPos worldCorner = candidateOrigin.offset(connectionPoint.corner());
                BlockPos attachPoint = worldCorner.relative(connectionPoint.facing(), 1);
                if (!claimed.contains(attachPoint)) {
                    frontiers.add(new FrontierEntry(
                            attachPoint, connectionPoint.facing(), connectionPoint.width(), connectionPoint.height()));
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

        // clear patched status for all chunks this room overlaps
        // onChunkWatch repatch them with the new room
        int[] extents = getExtents(schematic);
        int minChunkX = (origin.getX()) >> 4;
        int maxChunkX = (origin.getX() + extents[0]) >> 4;
        int minChunkZ = (origin.getZ()) >> 4;
        int maxChunkZ = (origin.getZ() + extents[2]) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                patchedChunks.remove(chunkKey(cx, cz));
            }
        }

        for (var block : schematic.blocks().entrySet()) {
            BlockPos world = origin.offset(block.getKey());
            if (serverLevel.isLoaded(world))
                serverLevel.setBlock(world, block.getValue(), Block.UPDATE_CLIENTS);
        }

        for (BlockPos marker : schematic.markers()) {
            BlockPos world = origin.offset(marker);
            if (serverLevel.isLoaded(world))
                serverLevel.setBlock(world, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        for (var block : schematic.blocks().entrySet()) {
            if (block.getKey() == null || block.getValue() == null) continue;
            BlockPos world = origin.offset(block.getKey());
            if (world == null) continue;
            if (!serverLevel.isLoaded(world)) continue;

            if (block.getValue().is(PORTAL_MARKER)) {
                serverLevel.setBlock(world, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (block.getValue().is(CHEST_MARKER)) {
                ChestLootHandler.fillChest(serverLevel, world, worldSeed);
                chestPositions.add(world);
                consumedChests.add(world);
            }
        }
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
                    chunk.setBlockState(mutable,
                            Blocks.SMOOTH_SANDSTONE.defaultBlockState(), false);
                }

        // generate rooms
        for (var entry : roomOrigins.entrySet()) {
            BlockPos origin = entry.getKey();
            SchematicLoader.Schematic schematic = entry.getValue();
            int[] e = getExtents(schematic);

            if (origin.getX() + e[0] < minX || origin.getX() >= maxX) continue;
            if (origin.getZ() + e[2] < minZ || origin.getZ() >= maxZ) continue;

            for (var block : schematic.blocks().entrySet()) {
                BlockPos world = origin.offset(block.getKey());
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                mutable.set(world);
                chunk.setBlockState(mutable, block.getValue(), false);
            }

            for (BlockPos marker : schematic.markers()) {
                BlockPos world = origin.offset(marker);
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                mutable.set(world);
                chunk.setBlockState(mutable, Blocks.AIR.defaultBlockState(), false);
            }

            for (var block : schematic.blocks().entrySet()) {
                if (block.getKey() == null || block.getValue() == null) continue;
                BlockPos world = origin.offset(block.getKey());
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                mutable.set(world);

                if (block.getValue().is(FrontierChunkGenerator.PORTAL_MARKER)) {
                    liminalness.LOGGER.debug("frontier generator - portal marker at {} -> air", world);
                    chunk.setBlockState(mutable, Blocks.AIR.defaultBlockState(), false);
                } else if (block.getValue().is(FrontierChunkGenerator.CHEST_MARKER)) {
                    liminalness.LOGGER.debug("frontier generator - chest marker at {} -> air", world);
                    chunk.setBlockState(mutable, Blocks.AIR.defaultBlockState(), false);
                } else {
                    chunk.setBlockState(mutable, block.getValue(), false);
                }
            }
        }

        patchedChunks.add(chunkKey(chunk.getPos().x, chunk.getPos().z));

        return CompletableFuture.completedFuture(chunk);
    }

    private SchematicLoader.Schematic pickCandidate(BlockPos attachPoint, Direction incomingFacing, int width, int height) {

        // filter weighted pool only matching schematics
        List<SchematicLoader.Schematic> matching = weightedPool.stream()
            .filter(s -> s.connectionPoints().stream()
            .anyMatch(connectionPoint -> connectionPoint.facing() == incomingFacing.getOpposite()
                            && connectionPoint.width()   == width
                            && connectionPoint.height()  == height))
            .toList();

        if (matching.isEmpty()) return null;

        long hash = worldSeed;
        hash ^= (long) attachPoint.getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) attachPoint.getY() * 0x6C62272E07BB0142L;
        hash ^= (long) attachPoint.getZ() * 0xD2A98B26625EEE7BL;
        hash  = Long.rotateLeft(hash, 31)  * 0x94D049BB133111EBL;

        return matching.get((int) Long.remainderUnsigned(hash, matching.size()));
    }

    private void registerBlockMarkers(BlockPos origin, SchematicLoader.Schematic schematic) {
        for (var block : schematic.blocks().entrySet()) {
            if (block.getValue() == null) continue;
            BlockPos world = origin.offset(block.getKey());

            if (block.getValue().is(PORTAL_MARKER)) {
                portalPositions.add(world);
                liminalness.LOGGER.debug("frontier generator - register portal at {}", world);
            } else if (block.getValue().is(CHEST_MARKER)) {
                chestPositions.add(world);
                liminalness.LOGGER.debug("frontier generator - register chest at {}", world);
            }
        }
    }

    // --- utils ---

    private boolean overlapsAny(SchematicLoader.Schematic candidate, BlockPos origin) {
        int[] ce = getExtents(candidate);
        int cMinX = origin.getX(), cMaxX = cMinX + ce[0];
        int cMinY = origin.getY(), cMaxY = cMinY + ce[1];
        int cMinZ = origin.getZ(), cMaxZ = cMinZ + ce[2];

        for (var entry : roomOrigins.entrySet()) {
            BlockPos o = entry.getKey();
            int[] pe = getExtents(entry.getValue());
            if (cMinX < o.getX() + pe[0] && cMaxX > o.getX()
                    && cMinY < o.getY() + pe[1] && cMaxY > o.getY()
                    && cMinZ < o.getZ() + pe[2] && cMaxZ > o.getZ()) return true;
        }
        return false;
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
