package com.danielpan888.liminalness.util;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class RoomSpatialIndex {

    private static final int CELL_SIZE = 64;
    private final Map<Long, Set<BlockPos>> cellToRooms = new ConcurrentHashMap<>();

    private final Map<BlockPos, int[]> roomAABBs = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<BlockPos>> scratchRooms = ThreadLocal.withInitial(HashSet::new);

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static int toCell(int coord) {
        return Math.floorDiv(coord, CELL_SIZE);
    }

    public void add(BlockPos origin, int[] extents) {
        int minX = origin.getX(), maxX = minX + extents[0];
        int minY = origin.getY(), maxY = minY + extents[1];
        int minZ = origin.getZ(), maxZ = minZ + extents[2];

        roomAABBs.put(origin, new int[]{minX, minY, minZ, maxX, maxY, maxZ});

        int cellMinX = toCell(minX), cellMaxX = toCell(Math.max(minX, maxX - 1));
        int cellMinZ = toCell(minZ), cellMaxZ = toCell(Math.max(minZ, maxZ - 1));

        for (int cx = cellMinX; cx <= cellMaxX; cx++) {
            for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                cellToRooms.computeIfAbsent(cellKey(cx, cz), k -> ConcurrentHashMap.newKeySet()).add(origin);
            }
        }
    }

    public boolean overlapsAny(BlockPos origin, int[] extents) {
        int cMinX = origin.getX(), cMaxX = cMinX + extents[0];
        int cMinY = origin.getY(), cMaxY = cMinY + extents[1];
        int cMinZ = origin.getZ(), cMaxZ = cMinZ + extents[2];

        int cellMinX = toCell(cMinX), cellMaxX = toCell(Math.max(cMinX, cMaxX - 1));
        int cellMinZ = toCell(cMinZ), cellMaxZ = toCell(Math.max(cMinZ, cMaxZ - 1));

        Set<BlockPos> checked = scratchRooms.get();
        checked.clear();

        try {
            for (int cx = cellMinX; cx <= cellMaxX; cx++) {
                for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                    Set<BlockPos> cell = cellToRooms.get(cellKey(cx, cz));
                    if (cell == null) continue;

                    for (BlockPos roomOrigin : cell) {
                        if (!checked.add(roomOrigin)) continue;

                        int[] r = roomAABBs.get(roomOrigin);
                        if (r == null) continue;

                        if (cMinX < r[3] && cMaxX > r[0]
                                && cMinY < r[4] && cMaxY > r[1]
                                && cMinZ < r[5] && cMaxZ > r[2]) {
                            return true;
                        }
                    }
                }
            }
        } finally {
            checked.clear();
        }

        return false;
    }

    public boolean anyRoomInChunk(int minX, int maxX, int minZ, int maxZ, Predicate<BlockPos> visitor) {
        int cellMinX = toCell(minX), cellMaxX = toCell(maxX);
        int cellMinZ = toCell(minZ), cellMaxZ = toCell(maxZ);

        Set<BlockPos> visited = scratchRooms.get();
        visited.clear();

        try {
            for (int cx = cellMinX; cx <= cellMaxX; cx++) {
                for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                    Set<BlockPos> cell = cellToRooms.get(cellKey(cx, cz));
                    if (cell == null) continue;

                    for (BlockPos roomOrigin : cell) {
                        if (!visited.add(roomOrigin)) continue;
                        if (visitor.test(roomOrigin)) {
                            return true;
                        }
                    }
                }
            }
        } finally {
            visited.clear();
        }

        return false;
    }

    public Set<BlockPos> getRoomsInChunk(int minX, int maxX, int minZ, int maxZ) {
        int cellMinX = toCell(minX), cellMaxX = toCell(maxX);
        int cellMinZ = toCell(minZ), cellMaxZ = toCell(maxZ);

        Set<BlockPos> result = new HashSet<>();

        for (int cx = cellMinX; cx <= cellMaxX; cx++) {
            for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                Set<BlockPos> cell = cellToRooms.get(cellKey(cx, cz));
                if (cell != null) result.addAll(cell);
            }
        }

        return result;
    }

    public void clear() {
        cellToRooms.clear();
        roomAABBs.clear();
    }

}
