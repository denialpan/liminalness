package com.danielpan888.liminalness.util;

import com.danielpan888.liminalness.liminalness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static com.danielpan888.liminalness.dimension.FrontierChunkGenerator.connectionSignature;

public class SchematicLoader {

    public enum SchematicVariant {
        BASE_SCHEMATIC("base", Rotation.NONE, null),
        ROTATION_90("rot90", Rotation.CLOCKWISE_90, null),
        ROTATION_180("rot180", Rotation.CLOCKWISE_180, null),
        ROTATION_270("rot270", Rotation.COUNTERCLOCKWISE_90, null),
        MIRROR_X("mirror_x", Rotation.NONE, Mirror.FRONT_BACK),
        MIRROR_Z("mirror_z", Rotation.NONE, Mirror.LEFT_RIGHT),
        MIRROR_X_ROTATION_90("mirror_x_rot90", Rotation.CLOCKWISE_90, Mirror.FRONT_BACK),
        MIRROR_Z_ROTATION_90("mirror_z_rot90", Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT);

        private final String suffix;
        private final Rotation rotation;
        private final Mirror mirror;

        SchematicVariant(String suffix, Rotation rotation, Mirror mirror) {
            this.suffix = suffix;
            this.rotation = rotation;
            this.mirror = mirror;
        }

        public String suffix() {
            return suffix;
        }

        public Rotation rotation() {
            return rotation;
        }

        public Mirror mirror() {
            return mirror;
        }
    }

    public record ConnectionPoint(BlockPos corner, Direction facing, int width, int height, Block markerBlock) {
        @Override
        public String toString() {
            return String.format("connection point {corner=%s, facing=%s, w=%d, h=%d}", corner, facing, width, height);
        }
    }

    public record Schematic(
        Map<BlockPos, BlockState> blocks,
        List<ConnectionPoint> connectionPoints,
        Set<BlockPos> markers,
        int extentX,
        int extentY,
        int extentZ,

        // preresolve final schematic states
        Map<BlockPos, BlockState> finalBlocks,
        Set<BlockPos> portalPositions,
        Set<BlockPos> jigsawPortalPositions,
        Set<BlockPos> structurePortalPositions,
        Set<BlockPos> chestPositions,

        Map<BlockPos, CompoundTag> blockEntityData,

        Map<Long, List<SchematicLoader.ConnectionPoint>> connectionPointIndex

    ) {}

    public static final Set<Block> MARKER_BLOCKS = Set.of(
        Blocks.LIGHT_GRAY_STAINED_GLASS,
        Blocks.GRAY_STAINED_GLASS,
        Blocks.BLACK_STAINED_GLASS,
        Blocks.BROWN_STAINED_GLASS,
        Blocks.RED_STAINED_GLASS,
        Blocks.ORANGE_STAINED_GLASS,
        Blocks.YELLOW_STAINED_GLASS,
        Blocks.LIME_STAINED_GLASS,
        Blocks.GREEN_STAINED_GLASS,
        Blocks.CYAN_STAINED_GLASS,
        Blocks.LIGHT_BLUE_STAINED_GLASS,
        Blocks.BLUE_STAINED_GLASS,
        Blocks.PURPLE_STAINED_GLASS,
        Blocks.MAGENTA_STAINED_GLASS,
        Blocks.PINK_STAINED_GLASS
    );

    public static List<Map.Entry<String, Schematic>> createHorizontalVariants(String basePath, Schematic base) {
        List<Map.Entry<String, Schematic>> variants = new ArrayList<>();
        for (SchematicVariant transform : SchematicVariant.values()) {
            String variantPath = transform == SchematicVariant.BASE_SCHEMATIC
                    ? basePath
                    : basePath + "#" + transform.suffix();
            variants.add(Map.entry(variantPath, transform(base, transform)));
        }
        return variants;
    }

    public static Schematic load(InputStream stream) throws Exception {

        // holy parse schematic files
        CompoundTag nbt = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        if (nbt.contains("Schematic")) nbt = nbt.getCompound("Schematic");

        int width = nbt.getShort("Width");
        int height = nbt.getShort("Height");
        int length = nbt.getShort("Length");

        int[] offset = {0, 0, 0};
        if (nbt.contains("Offset")) {
            int[] arr = nbt.getIntArray("Offset");
            if (arr.length == 3) offset = arr;
        }

        CompoundTag blocksTag = nbt.contains("Blocks", Tag.TAG_COMPOUND) ? nbt.getCompound("Blocks") : null;
        CompoundTag paletteTag;
        byte[] blockData;

        // support worldedit amd axiom schematics formats, since for some reason theyre different
        // worldedit schem export
        if (blocksTag != null && blocksTag.contains("Palette", Tag.TAG_COMPOUND) && blocksTag.contains("Data", Tag.TAG_BYTE_ARRAY)) {
            paletteTag = blocksTag.getCompound("Palette");
            blockData = blocksTag.getByteArray("Data");
        // axiom
        } else if (nbt.contains("Palette", Tag.TAG_COMPOUND) && nbt.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            paletteTag = nbt.getCompound("Palette");
            blockData = nbt.getByteArray("BlockData");
        } else {
            throw new IllegalArgumentException("unsupported schematic format: where did you export this from?");
        }

        // block palette
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (String key : paletteTag.getAllKeys()) {
            palette[paletteTag.getInt(key)] = parseBlockState(key);
        }

        Map<BlockPos, BlockState> rawBlocks  = new HashMap<>();
        Set<BlockPos> rawMarkers = new HashSet<>();
        Map<BlockPos, Block> markerBlockTypes = new HashMap<>();

        for (int i = 0; i < blockData.length; i++) {
            int x = (i % width)            + offset[0];
            int y = (i / (width * length)) + offset[1];
            int z = ((i / width) % length) + offset[2];

            BlockState state = palette[blockData[i] & 0xFF];
            BlockPos pos = new BlockPos(x, y, z);

            if (MARKER_BLOCKS.contains(state.getBlock())) {
                rawMarkers.add(pos);
                markerBlockTypes.put(pos, state.getBlock());
            } else if (!state.isAir()) {
                rawBlocks.put(pos, state);
            }
        }

        // normalize to 0,0,0
        Set<BlockPos> all = new HashSet<>(rawBlocks.keySet());
        all.addAll(rawMarkers);

        int minX = all.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int minY = all.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int minZ = all.stream().mapToInt(BlockPos::getZ).min().orElse(0);

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Set<BlockPos> markers = new HashSet<>();

        for (var e : rawBlocks.entrySet()) {
            blocks.put(normalize(e.getKey(), minX, minY, minZ), e.getValue());
        }

        Map<BlockPos, Block> normalizedMarkerBlockTypes = new HashMap<>();
        for (BlockPos p : rawMarkers) {
            BlockPos normalized = normalize(p, minX, minY, minZ);
            markers.add(normalized);
            normalizedMarkerBlockTypes.put(normalized, markerBlockTypes.get(p));
        }

        Map<BlockPos, BlockState> finalBlocks = new HashMap<>();
        Set<BlockPos> portalPositions = new HashSet<>();
        Set<BlockPos> jigsawPortalPositions = new HashSet<>();
        Set<BlockPos> structurePortalPositions = new HashSet<>();
        Set<BlockPos> chestPositions  = new HashSet<>();

        // parse chest contents
        Map<BlockPos, CompoundTag> blockEntityData = new HashMap<>();
        if (blocksTag != null && blocksTag.contains("BlockEntities")) {

            ListTag beList = blocksTag.getList("BlockEntities", Tag.TAG_COMPOUND);
            for (int i = 0; i < beList.size(); i++) {
                CompoundTag be = beList.getCompound(i);
                int[] pos = be.getIntArray("Pos");
                if (pos.length != 3) continue;

                // normalize chest
                BlockPos rawPos = new BlockPos(
                    pos[0] + offset[0],
                    pos[1] + offset[1],
                    pos[2] + offset[2]
                );
                BlockPos normalizedPos = normalize(rawPos, minX, minY, minZ);
                CompoundTag data = extractBlockEntityPayload(be);
                blockEntityData.put(normalizedPos, data);
                liminalness.LOGGER.info("schematic loader - block entity at normalized={} items={}", normalizedPos, data.contains("Items") ? data.getList("Items", 10).size() : 0);
            }

        } else if (nbt.contains("BlockEntities", Tag.TAG_LIST)) {
            ListTag beList = nbt.getList("BlockEntities", Tag.TAG_COMPOUND);
            for (int i = 0; i < beList.size(); i++) {
                CompoundTag be = beList.getCompound(i);
                int[] pos = be.getIntArray("Pos");
                if (pos.length != 3) continue;

                // same normalize
                BlockPos rawPos = new BlockPos(
                    pos[0] + offset[0],
                    pos[1] + offset[1],
                    pos[2] + offset[2]
                );
                BlockPos normalizedPos = normalize(rawPos, minX, minY, minZ);
                CompoundTag data = extractBlockEntityPayload(be);
                blockEntityData.put(normalizedPos, data);
                liminalness.LOGGER.info("schematic loader - block entity at normalized={} items={}", normalizedPos, data.contains("Items") ? data.getList("Items", 10).size() : 0);
            }
        }
        liminalness.LOGGER.info("schematic loader - parsed {} block entities", blockEntityData.size());


        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            if (MARKER_BLOCKS.contains(state.getBlock())) {
                finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
            } else if (state.getBlock() == Blocks.END_PORTAL_FRAME) {
                portalPositions.add(pos);
                finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
            } else if (state.getBlock() == Blocks.JIGSAW) {
                jigsawPortalPositions.add(pos);
                finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
            } else if (state.getBlock() == Blocks.STRUCTURE_BLOCK) {
                structurePortalPositions.add(pos);
                finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
            } else if (state.getBlock() == Blocks.ORANGE_WOOL) {
                chestPositions.add(pos);
                finalBlocks.put(pos, Blocks.CHEST.defaultBlockState());
            } else {
                finalBlocks.put(pos, state);
            }
        }

        for (BlockPos marker : markers) {
            finalBlocks.put(marker, Blocks.AIR.defaultBlockState());
        }

        // fill interior air
        int maxX = blocks.keySet().stream().mapToInt(BlockPos::getX).max().orElse(0);
        int maxY = blocks.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0);
        int maxZ = blocks.keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0);

        for (int x = 0; x <= maxX; x++) {
            for (int y = 0; y <= maxY; y++) {
                for (int z = 0; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!blocks.containsKey(pos) && !markers.contains(pos)) {
                        finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }


        // find connection points
        List<ConnectionPoint> connectionPoints = detectConnectionPoints(markers, normalizedMarkerBlockTypes, blocks);

        Map<Long, List<SchematicLoader.ConnectionPoint>> connectionPointIndex = new HashMap<>();
        for (ConnectionPoint connectionPoint : connectionPoints) {
            long sig = connectionSignature(connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock());
            connectionPointIndex.computeIfAbsent(sig, k -> new ArrayList<>()).add(connectionPoint);
        }

        liminalness.LOGGER.info("schematic loader - loaded schematic: {} blocks ({} solid + air), {} connection points", blocks.size(), rawBlocks.size(), connectionPoints.size());
        for (ConnectionPoint connectionPoint : connectionPoints) {
            liminalness.LOGGER.info("|---{}", connectionPoint);
        }

        return new Schematic(
            blocks,
            connectionPoints,
            markers,
            Math.max(0, width - 1),
            Math.max(0, height - 1),
            Math.max(0, length - 1),
            finalBlocks,
            portalPositions,
            jigsawPortalPositions,
            structurePortalPositions,
            chestPositions,
            blockEntityData,
            connectionPointIndex
        );
    }

    private static BlockPos normalize(BlockPos p, int minX, int minY, int minZ) {
        return new BlockPos(p.getX() - minX, p.getY() - minY, p.getZ() - minZ);
    }

    private static CompoundTag extractBlockEntityPayload(CompoundTag blockEntityTag) {
        if (blockEntityTag.contains("Data", Tag.TAG_COMPOUND)) {
            return blockEntityTag.getCompound("Data").copy();
        }

        CompoundTag payload = blockEntityTag.copy();
        payload.remove("Pos");
        return payload;
    }

    private static Schematic transform(Schematic base, SchematicVariant transform) {
        int maxX = getMaxCoordinate(base.finalBlocks().keySet(), BlockPos::getX);
        int maxZ = getMaxCoordinate(base.finalBlocks().keySet(), BlockPos::getZ);
        int rotatedExtentX = transform.rotation() == Rotation.CLOCKWISE_90 || transform.rotation() == Rotation.COUNTERCLOCKWISE_90 ? base.extentZ() : base.extentX();
        int rotatedExtentY = base.extentY();
        int rotatedExtentZ = transform.rotation() == Rotation.CLOCKWISE_90 || transform.rotation() == Rotation.COUNTERCLOCKWISE_90 ? base.extentX() : base.extentZ();

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (var entry : base.blocks().entrySet()) {
            blocks.put(
                transformPos(entry.getKey(), maxX, maxZ, transform),
                transformState(entry.getValue(), transform)
            );
        }

        Map<BlockPos, BlockState> finalBlocks = new HashMap<>();
        for (var entry : base.finalBlocks().entrySet()) {
            finalBlocks.put(
                transformPos(entry.getKey(), maxX, maxZ, transform),
                transformState(entry.getValue(), transform)
            );
        }

        Set<BlockPos> markers = transformPositions(base.markers(), maxX, maxZ, transform);
        Set<BlockPos> portalPositions = transformPositions(base.portalPositions(), maxX, maxZ, transform);
        Set<BlockPos> jigsawPortalPositions = transformPositions(base.jigsawPortalPositions(), maxX, maxZ, transform);
        Set<BlockPos> structurePortalPositions = transformPositions(base.structurePortalPositions(), maxX, maxZ, transform);
        Set<BlockPos> chestPositions = transformPositions(base.chestPositions(), maxX, maxZ, transform);

        Map<BlockPos, CompoundTag> blockEntityData = new HashMap<>();
        for (var entry : base.blockEntityData().entrySet()) {
            blockEntityData.put(
                transformPos(entry.getKey(), maxX, maxZ, transform),
                entry.getValue().copy()
            );
        }

        List<ConnectionPoint> connectionPoints = new ArrayList<>();
        for (ConnectionPoint connectionPoint : base.connectionPoints()) {
            connectionPoints.add(transformConnectionPoint(connectionPoint, maxX, maxZ, transform));
        }

        return renormalize(
            blocks,
            finalBlocks,
            markers,
            rotatedExtentX,
            rotatedExtentY,
            rotatedExtentZ,
            portalPositions,
            jigsawPortalPositions,
            structurePortalPositions,
            chestPositions,
            blockEntityData,
            connectionPoints
        );
    }

    private static int getMaxCoordinate(Collection<BlockPos> positions, java.util.function.ToIntFunction<BlockPos> getter) {
        return positions.stream().mapToInt(getter).max().orElse(0);
    }

    private static Set<BlockPos> transformPositions(Set<BlockPos> positions, int maxX, int maxZ, SchematicVariant transform) {
        Set<BlockPos> transformed = new HashSet<>();
        for (BlockPos pos : positions) {
            transformed.add(transformPos(pos, maxX, maxZ, transform));
        }
        return transformed;
    }

    private static BlockPos transformPos(BlockPos pos, int maxX, int maxZ, SchematicVariant transform) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        if (transform.mirror() == Mirror.FRONT_BACK) {
            x = maxX - x;
        } else if (transform.mirror() == Mirror.LEFT_RIGHT) {
            z = maxZ - z;
        }

        return switch (transform.rotation()) {
            case NONE -> new BlockPos(x, y, z);
            case CLOCKWISE_90 -> new BlockPos(maxZ - z, y, x);
            case CLOCKWISE_180 -> new BlockPos(maxX - x, y, maxZ - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, maxX - x);
        };
    }

    private static BlockState transformState(BlockState state, SchematicVariant transform) {
        if (transform.mirror() != null) {
            state = state.mirror(transform.mirror());
        }
        return state.rotate(transform.rotation());
    }

    private static Direction transformFacing(Direction facing, SchematicVariant transform) {
        if (transform.mirror() != null) {
            facing = transform.mirror().mirror(facing);
        }
        return transform.rotation().rotate(facing);
    }

    private static ConnectionPoint transformConnectionPoint(ConnectionPoint connectionPoint, int maxX, int maxZ, SchematicVariant transform) {
        Set<BlockPos> transformedPlane = new HashSet<>();
        for (BlockPos pos : getConnectionPlane(connectionPoint)) {
            transformedPlane.add(transformPos(pos, maxX, maxZ, transform));
        }

        int minX = transformedPlane.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int maxPlaneX = transformedPlane.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minY = transformedPlane.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int maxPlaneY = transformedPlane.stream().mapToInt(BlockPos::getY).max().orElse(0);
        int minZ = transformedPlane.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxPlaneZ = transformedPlane.stream().mapToInt(BlockPos::getZ).max().orElse(0);

        Direction facing = transformFacing(connectionPoint.facing(), transform);
        BlockPos corner = new BlockPos(minX, minY, minZ);

        int width;
        int height;

        if (facing == Direction.UP || facing == Direction.DOWN) {
            width = maxPlaneX - minX + 1;
            height = maxPlaneZ - minZ + 1;
        } else if (facing == Direction.WEST || facing == Direction.EAST) {
            width = maxPlaneZ - minZ + 1;
            height = maxPlaneY - minY + 1;
        } else {
            width = maxPlaneX - minX + 1;
            height = maxPlaneY - minY + 1;
        }

        return new ConnectionPoint(corner, facing, width, height, connectionPoint.markerBlock());
    }

    private static Set<BlockPos> getConnectionPlane(ConnectionPoint connectionPoint) {
        Set<BlockPos> plane = new HashSet<>();
        BlockPos corner = connectionPoint.corner();

        if (connectionPoint.facing() == Direction.UP || connectionPoint.facing() == Direction.DOWN) {
            for (int dx = 0; dx < connectionPoint.width(); dx++) {
                for (int dz = 0; dz < connectionPoint.height(); dz++) {
                    plane.add(corner.offset(dx, 0, dz));
                }
            }
        } else if (connectionPoint.facing() == Direction.WEST || connectionPoint.facing() == Direction.EAST) {
            for (int dy = 0; dy < connectionPoint.height(); dy++) {
                for (int dz = 0; dz < connectionPoint.width(); dz++) {
                    plane.add(corner.offset(0, dy, dz));
                }
            }
        } else {
            for (int dx = 0; dx < connectionPoint.width(); dx++) {
                for (int dy = 0; dy < connectionPoint.height(); dy++) {
                    plane.add(corner.offset(dx, dy, 0));
                }
            }
        }

        return plane;
    }

    private static Schematic renormalize(
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, BlockState> finalBlocks,
        Set<BlockPos> markers,
        int extentX,
        int extentY,
        int extentZ,
        Set<BlockPos> portalPositions,
        Set<BlockPos> jigsawPortalPositions,
        Set<BlockPos> structurePortalPositions,
        Set<BlockPos> chestPositions,
        Map<BlockPos, CompoundTag> blockEntityData,
        List<ConnectionPoint> connectionPoints
    ) {
        Set<BlockPos> all = new HashSet<>();
        all.addAll(blocks.keySet());
        all.addAll(finalBlocks.keySet());
        all.addAll(markers);
        all.addAll(portalPositions);
        all.addAll(jigsawPortalPositions);
        all.addAll(structurePortalPositions);
        all.addAll(chestPositions);
        all.addAll(blockEntityData.keySet());
        for (ConnectionPoint connectionPoint : connectionPoints) {
            all.add(connectionPoint.corner());
        }

        if (all.isEmpty()) {
            return new Schematic(Map.of(), List.of(), Set.of(), extentX, extentY, extentZ, Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of());
        }

        int minX = all.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int minY = all.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int minZ = all.stream().mapToInt(BlockPos::getZ).min().orElse(0);

        Map<BlockPos, BlockState> normalizedBlocks = shiftMap(blocks, minX, minY, minZ, false);
        Map<BlockPos, BlockState> normalizedFinalBlocks = shiftMap(finalBlocks, minX, minY, minZ, false);
        Set<BlockPos> normalizedMarkers = shiftSet(markers, minX, minY, minZ);
        Set<BlockPos> normalizedPortalPositions = shiftSet(portalPositions, minX, minY, minZ);
        Set<BlockPos> normalizedJigsawPortalPositions = shiftSet(jigsawPortalPositions, minX, minY, minZ);
        Set<BlockPos> normalizedStructurePortalPositions = shiftSet(structurePortalPositions, minX, minY, minZ);
        Set<BlockPos> normalizedChestPositions = shiftSet(chestPositions, minX, minY, minZ);
        Map<BlockPos, CompoundTag> normalizedBlockEntityData = shiftMap(blockEntityData, minX, minY, minZ, true);

        List<ConnectionPoint> normalizedConnectionPoints = new ArrayList<>();
        for (ConnectionPoint connectionPoint : connectionPoints) {
            normalizedConnectionPoints.add(new ConnectionPoint(
                normalize(connectionPoint.corner(), minX, minY, minZ),
                connectionPoint.facing(),
                connectionPoint.width(),
                connectionPoint.height(),
                connectionPoint.markerBlock()
            ));
        }

        Map<Long, List<SchematicLoader.ConnectionPoint>> connectionPointIndex = new HashMap<>();
        for (ConnectionPoint connectionPoint : normalizedConnectionPoints) {
            long sig = connectionSignature(connectionPoint.facing(), connectionPoint.width(), connectionPoint.height(), connectionPoint.markerBlock());
            connectionPointIndex.computeIfAbsent(sig, k -> new ArrayList<>()).add(connectionPoint);
        }

        return new Schematic(
            normalizedBlocks,
            normalizedConnectionPoints,
            normalizedMarkers,
            extentX,
            extentY,
            extentZ,
            normalizedFinalBlocks,
            normalizedPortalPositions,
            normalizedJigsawPortalPositions,
            normalizedStructurePortalPositions,
            normalizedChestPositions,
            normalizedBlockEntityData,
            connectionPointIndex
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<BlockPos, T> shiftMap(Map<BlockPos, T> input, int minX, int minY, int minZ, boolean copyCompoundTags) {
        Map<BlockPos, T> shifted = new HashMap<>();
        for (var entry : input.entrySet()) {
            T value = entry.getValue();
            if (copyCompoundTags && value instanceof CompoundTag tag) {
                value = (T) tag.copy();
            }
            shifted.put(normalize(entry.getKey(), minX, minY, minZ), value);
        }
        return shifted;
    }

    private static Set<BlockPos> shiftSet(Set<BlockPos> input, int minX, int minY, int minZ) {
        Set<BlockPos> shifted = new HashSet<>();
        for (BlockPos pos : input) {
            shifted.add(normalize(pos, minX, minY, minZ));
        }
        return shifted;
    }

    private static List<ConnectionPoint> detectConnectionPoints(Set<BlockPos> markers, Map<BlockPos, Block> markerBlockTypes, Map<BlockPos, BlockState> blocks) {

        List<ConnectionPoint> result = new ArrayList<>();

        Set<BlockPos> all = new HashSet<>(blocks.keySet());
        all.addAll(markers);

        int schMinX = all.stream().mapToInt(BlockPos::getX).min().getAsInt();
        int schMaxX = all.stream().mapToInt(BlockPos::getX).max().getAsInt();
        int schMinY = all.stream().mapToInt(BlockPos::getY).min().getAsInt();
        int schMaxY = all.stream().mapToInt(BlockPos::getY).max().getAsInt();
        int schMinZ = all.stream().mapToInt(BlockPos::getZ).min().getAsInt();
        int schMaxZ = all.stream().mapToInt(BlockPos::getZ).max().getAsInt();

        for (Set<BlockPos> plane : findConnectedComponents(markers)) {

            Set<Block> typesInPlane = plane.stream().map(markerBlockTypes::get).collect(Collectors.toSet());
            if (typesInPlane.size() > 1) {
                liminalness.LOGGER.info("schematic loader - mixed types in marker plane, skipping...");
                continue;
            }

            Block markerBlock = typesInPlane.iterator().next();

            int minX = plane.stream().mapToInt(BlockPos::getX).min().getAsInt();
            int maxX = plane.stream().mapToInt(BlockPos::getX).max().getAsInt();
            int minY = plane.stream().mapToInt(BlockPos::getY).min().getAsInt();
            int maxY = plane.stream().mapToInt(BlockPos::getY).max().getAsInt();
            int minZ = plane.stream().mapToInt(BlockPos::getZ).min().getAsInt();
            int maxZ = plane.stream().mapToInt(BlockPos::getZ).max().getAsInt();

            int spanX = maxX - minX;
            int spanY = maxY - minY;
            int spanZ = maxZ - minZ;

            Direction facing;

            if (spanX == 0 && spanY == 0 && spanZ == 0) {
                List<Direction> possibleFacings = new ArrayList<>();

                if (minY == schMinY) possibleFacings.add(Direction.DOWN);
                if (minY == schMaxY) possibleFacings.add(Direction.UP);
                if (minX == schMinX) possibleFacings.add(Direction.WEST);
                if (minX == schMaxX) possibleFacings.add(Direction.EAST);
                if (minZ == schMinZ) possibleFacings.add(Direction.NORTH);
                if (minZ == schMaxZ) possibleFacings.add(Direction.SOUTH);

                if (possibleFacings.size() != 1) {
                    liminalness.LOGGER.warn("schematic loader - ambiguous 1x1 marker plane at {} possibleFacings={}, skipping", plane.iterator().next(), possibleFacings);
                    continue;
                }

                facing = possibleFacings.getFirst();
                BlockPos corner = new BlockPos(minX, minY, minZ);
                result.add(new ConnectionPoint(corner, facing, 1, 1, markerBlock));
                liminalness.LOGGER.info("schematic loader - connection point: {} facing={} w=1 h=1 markertype={}", corner, facing, markerBlock);
                continue;
            }

            // marker plane flat on y axis (up or down)
            if (spanY == 0 && spanX > 0 && spanZ > 0) {

                if (minY == schMinY)  {
                    facing = Direction.DOWN;
                } else if (minY == schMaxY) {
                    facing = Direction.UP;
                } else {
                    liminalness.LOGGER.warn("schematic loader - horizontal marker plane at Y={} not on schematic Y edge [{},{}], skipping", minY, schMinY, schMaxY);
                    continue;
                }

                int planeWidth  = maxX - minX + 1;
                int planeHeight = maxZ - minZ + 1;
                BlockPos corner = new BlockPos(minX, minY, minZ);
                result.add(new ConnectionPoint(corner, facing, planeWidth, planeHeight, markerBlock));
                liminalness.LOGGER.info("schematic loader - connection point: {} facing={} w={} h={} markertype={}", corner, facing, planeWidth, planeHeight, markerBlock);

                // marker plane flat on x axis (east or west)
            } else if (spanX == 0 && spanZ > 0) {

                if (minX == schMinX) {
                    facing = Direction.WEST;
                } else if (minX == schMaxX) {
                    facing = Direction.EAST;
                } else {
                    liminalness.LOGGER.warn("schematic loader - vertical marker plane plane at X={} not on schematic X edge [{},{}], skipping", minX, schMinX, schMaxX);
                    continue;
                }

                int planeWidth  = maxZ - minZ + 1;
                int planeHeight = maxY - minY + 1;
                BlockPos corner = new BlockPos(minX, minY, minZ);
                result.add(new ConnectionPoint(corner, facing, planeWidth, planeHeight, markerBlock));
                liminalness.LOGGER.info("schematic loader - connection point: {} facing={} w={} h={} markertype={}", corner, facing, planeWidth, planeHeight, markerBlock);

                // marker plane flat on z axis (north or south)
            } else if (spanZ == 0 && spanX > 0) {

                if (minZ == schMinZ) {
                    facing = Direction.NORTH;
                } else if (minZ == schMaxZ) {
                    facing = Direction.SOUTH;
                } else {
                    liminalness.LOGGER.warn("schematic loader - vertical marker plane at Z={} not on schematic Z edge [{},{}], skipping", minZ, schMinZ, schMaxZ);
                    continue;
                }

                int planeWidth  = maxX - minX + 1;
                int planeHeight = maxY - minY + 1;
                BlockPos corner = new BlockPos(minX, minY, minZ);
                result.add(new ConnectionPoint(corner, facing, planeWidth, planeHeight, markerBlock));
                liminalness.LOGGER.info("schematic loader - connection point: {} facing={} w={} h={} markertype={}", corner, facing, planeWidth, planeHeight, markerBlock);

            } else {
                liminalness.LOGGER.warn("schematic loader - skipping, no marker plane on spanX={} spanY={} spanZ={}", spanX, spanY, spanZ);
            }
        }

        return result;
    }

    private static List<Set<BlockPos>> findConnectedComponents(Set<BlockPos> input) {
        List<Set<BlockPos>> components = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos start : input) {
            if (visited.contains(start)) continue;
            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                if (!visited.add(pos)) continue;
                component.add(pos);
                for (Direction dir : Direction.values()) {
                    BlockPos n = pos.relative(dir);
                    if (input.contains(n) && !visited.contains(n)) queue.add(n);
                }
            }
            components.add(component);
        }
        return components;
    }

    private static BlockState parseBlockState(String key) {
        try {
            String blockName = key.contains("[") ? key.substring(0, key.indexOf('[')) : key;
            Map<String, String> props = new HashMap<>();

            if (key.contains("[")) {
                String inner = key.substring(key.indexOf('[') + 1, key.lastIndexOf(']'));
                for (String kv : inner.split(",")) {
                    String[] parts = kv.split("=");
                    if (parts.length == 2) props.put(parts[0].trim(), parts[1].trim());
                }
            }

            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockName));
            BlockState state = block.defaultBlockState();

            for (var e : props.entrySet()) {
                state = applyProperty(state, e.getKey(), e.getValue()); // must reassign
            }

            return state;
        } catch (Exception e) {
            liminalness.LOGGER.warn("Failed to parse block state '{}': {}", key, e.getMessage());
            return Blocks.AIR.defaultBlockState();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, String name, String value) {
        for (Property<?> prop : state.getBlock().getStateDefinition().getProperties()) {
            if (prop.getName().equals(name)) {
                Optional<?> val = prop.getValue(value);
                if (val.isPresent()) {
                    state = state.setValue((Property) prop, (Comparable) val.get()); // result assigned
                }
                break;
            }
        }
        return state;
    }
}
