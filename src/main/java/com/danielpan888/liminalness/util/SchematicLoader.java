package com.danielpan888.liminalness.util;

import com.danielpan888.liminalness.liminalness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.InputStream;
import java.util.*;

public class SchematicLoader {

    public record ConnectionPoint(BlockPos corner, Direction facing, int width, int height) {
        @Override
        public String toString() {
            return String.format("connection point {corner=%s, facing=%s, w=%d, h=%d}", corner, facing, width, height);
        }
    }

    public record Schematic(
        Map<BlockPos, BlockState> blocks,
        List<ConnectionPoint> connectionPoints,
        Set<BlockPos> markers,

        // preresolve this
        Map<BlockPos, BlockState> finalBlocks,
        Set<BlockPos> portalPositions,
        Set<BlockPos> chestPositions

    ) {}

    public static Schematic load(InputStream stream) throws Exception {

        // holy parse schematic files
        CompoundTag nbt = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        if (nbt.contains("Schematic")) nbt = nbt.getCompound("Schematic");

        int width = nbt.getShort("Width");
        int length = nbt.getShort("Length");

        int[] offset = {0, 0, 0};
        if (nbt.contains("Offset")) {
            int[] arr = nbt.getIntArray("Offset");
            if (arr.length == 3) offset = arr;
        }

        CompoundTag blocksTag  = nbt.getCompound("Blocks");
        CompoundTag paletteTag = blocksTag.getCompound("Palette");
        byte[] blockData  = blocksTag.getByteArray("Data");

        // block palette
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (String key : paletteTag.getAllKeys()) {
            palette[paletteTag.getInt(key)] = parseBlockState(key);
        }

        Map<BlockPos, BlockState> rawBlocks  = new HashMap<>();
        Set<BlockPos> rawMarkers = new HashSet<>();

        for (int i = 0; i < blockData.length; i++) {
            int x = (i % width)            + offset[0];
            int y = (i / (width * length)) + offset[1];
            int z = ((i / width) % length) + offset[2];

            BlockState state = palette[blockData[i] & 0xFF];
            BlockPos pos = new BlockPos(x, y, z);

            if (state.is(Blocks.LIME_WOOL)) {
                rawMarkers.add(pos);
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

        for (BlockPos p : rawMarkers) {
            markers.add(normalize(p, minX, minY, minZ));
        }

        Map<BlockPos, BlockState> finalBlocks = new HashMap<>();
        Set<BlockPos> portalPositions = new HashSet<>();
        Set<BlockPos> chestPositions  = new HashSet<>();


        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            if (state.is(Blocks.LIME_WOOL)) {
                finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
            } else if (state.getBlock() == Blocks.END_PORTAL_FRAME) {
                portalPositions.add(pos);
                finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
            } else if (state.getBlock() == Blocks.ORANGE_WOOL) {
                chestPositions.add(pos);
                finalBlocks.put(pos, Blocks.AIR.defaultBlockState());
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
        List<ConnectionPoint> connectionPoints = detectConnectionPoints(markers, blocks);

        liminalness.LOGGER.info("schematic loader - loaded schematic: {} blocks ({} solid + air), {} connection points", blocks.size(), rawBlocks.size(), connectionPoints.size());
        for (ConnectionPoint connectionPoint : connectionPoints) {
            liminalness.LOGGER.info("|---{}", connectionPoint);
        }

        return new Schematic(blocks, connectionPoints, markers, finalBlocks, portalPositions, chestPositions);
    }

    private static BlockPos normalize(BlockPos p, int minX, int minY, int minZ) {
        return new BlockPos(p.getX() - minX, p.getY() - minY, p.getZ() - minZ);
    }

    private static List<ConnectionPoint> detectConnectionPoints(Set<BlockPos> markers, Map<BlockPos, BlockState> blocks) {

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
                result.add(new ConnectionPoint(corner, facing, planeWidth, planeHeight));
                liminalness.LOGGER.info("schematic loader - connection point: {} facing={} w={} h={}", corner, facing, planeWidth, planeHeight);

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
                result.add(new ConnectionPoint(corner, facing, planeWidth, planeHeight));
                liminalness.LOGGER.info("schematic loader - connection point: {} facing={} w={} h={}", corner, facing, planeWidth, planeHeight);

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
                result.add(new ConnectionPoint(corner, facing, planeWidth, planeHeight));
                liminalness.LOGGER.info("schematic loader - connection point: {} facing={} w={} h={}", corner, facing, planeWidth, planeHeight);

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