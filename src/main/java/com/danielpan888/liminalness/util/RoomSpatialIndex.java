package com.danielpan888.liminalness.util;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomSpatialIndex {

    private static final int CELL_SIZE = 64;
    private final Map<Long, Set<BlockPos>> cellToRooms = new ConcurrentHashMap<>();

    private final Map<BlockPos, int[]> roomAABBs = new ConcurrentHashMap<>();

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

        int cellMinX = toCell(minX), cellMaxX = toCell(maxX);
        int cellMinZ = toCell(minZ), cellMaxZ = toCell(maxZ);

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

        int cellMinX = toCell(cMinX), cellMaxX = toCell(cMaxX);
        int cellMinZ = toCell(cMinZ), cellMaxZ = toCell(cMaxZ);

        Set<BlockPos> checked = new HashSet<>();

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
