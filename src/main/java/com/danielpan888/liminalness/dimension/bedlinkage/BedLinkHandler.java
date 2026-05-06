package com.danielpan888.liminalness.dimension.bedlinkage;

import com.danielpan888.liminalness.dimension.DimensionManager;
import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

public final class BedLinkHandler {

    private static final long MIX_CONSTANT = 0x94D049BB133111EBL;

    private BedLinkHandler() {

    }

    public static Optional<BedLinkDestination> resolveDestination(ServerPlayer player, BlockPos bedPos) {
        if (!(player.level() instanceof ServerLevel sourceLevel)) {
            return Optional.empty();
        }

        if (!canTriggerTeleport(sourceLevel, bedPos)) {
            return Optional.empty();
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }

        List<ResourceLocation> availableDimensions = getAvailableManagedDimensions(server);
        if (availableDimensions.isEmpty()) {
            return Optional.empty();
        }

        long worldSeed = server.getWorldData().worldGenOptions().seed();
        ResourceLocation sourceDimension = sourceLevel.dimension().location();
        long selectionHash = mixBedHash(worldSeed, sourceDimension, bedPos, null);
        ResourceLocation targetDimension = availableDimensions.get(
                (int) Long.remainderUnsigned(selectionHash, availableDimensions.size())
        );

        ServerLevel targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, targetDimension));
        if (targetLevel == null) {
            return Optional.empty();
        }

        FrontierChunkGenerator generator = resolveGenerator(targetLevel, targetDimension);
        if (generator == null || !generator.isReady()) {
            return Optional.empty();
        }

        long locationHash = mixBedHash(worldSeed, sourceDimension, bedPos, targetDimension);
        int spawnRange = Math.max(generator.radiusHorizontal, 2560000);

        locationHash = Long.rotateLeft(locationHash, 17) * MIX_CONSTANT;
        int startCenterX = randomInRange(locationHash, -spawnRange, spawnRange);

        locationHash = Long.rotateLeft(locationHash, 17) * MIX_CONSTANT;
        int startCenterZ = randomInRange(locationHash, -spawnRange, spawnRange);

        if (generator.roomOrigins.isEmpty() && generator.needsSeed) {
            generator.needsSeed = false;
            generator.seedAt(startCenterX, startCenterZ);
        }

        Vec3 spawnPos = generator.ensureLinkedSpawn(startCenterX, startCenterZ);

        return Optional.of(new BedLinkDestination(targetLevel, spawnPos));
    }

    public static boolean canTriggerTeleport(ServerLevel level, BlockPos bedPos) {

        BlockState state = level.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return false;
        }
        if (level.getBrightness(LightLayer.SKY, bedPos) > 0) {
            return false;
        }

        // darkness max radius check
        int radius = 5;
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(bedPos);
        visited.add(bedPos);

        while (!queue.isEmpty()) {

            BlockPos current = queue.poll();
            BlockState currentState = level.getBlockState(current);

            if (!currentState.isAir()) {
                if (level.getBrightness(LightLayer.BLOCK, current) > 0) {
                    return false;
                }
                continue;
            }

            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (Math.abs(neighbor.getX() - bedPos.getX()) > radius) {
                    continue;
                }
                if (Math.abs(neighbor.getY() - bedPos.getY()) > radius) {
                    continue;
                }
                if (Math.abs(neighbor.getZ() - bedPos.getZ()) > radius) {
                    continue;
                }

                visited.add(neighbor);
                queue.add(neighbor);
            }

        }

        return true;
    }

    private static List<ResourceLocation> getAvailableManagedDimensions(MinecraftServer server) {

        List<ResourceLocation> dimensions = new ArrayList<>();

        for (ResourceLocation dimensionId : DimensionManager.getRegisteredIds()) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
            if (level != null) {
                dimensions.add(dimensionId);
            }
        }

        dimensions.sort(Comparator.comparing(ResourceLocation::toString));
        return dimensions;

    }

    private static FrontierChunkGenerator resolveGenerator(ServerLevel level, ResourceLocation dimensionId) {

        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        FrontierChunkGenerator generator = chunkGenerator instanceof FrontierChunkGenerator frontier
                ? frontier
                : (FrontierChunkGenerator) DimensionManager.getInstance(dimensionId);

        if (generator != null) {
            generator.serverLevel = level;
            generator.dimensionId = dimensionId;
        }

        return generator;

    }

    private static long mixBedHash(long worldSeed, ResourceLocation sourceDimension, BlockPos bedPos, ResourceLocation targetDimension) {

        long hash = worldSeed;
        hash ^= sourceDimension.toString().hashCode();
        hash ^= (long) bedPos.getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) bedPos.getY() * 0x6C62272E07BB0142L;
        hash ^= (long) bedPos.getZ() * 0xD2A98B26625EEE7BL;

        if (targetDimension != null) {
            hash ^= Long.rotateLeft(targetDimension.toString().hashCode(), 19);
        }
        return Long.rotateLeft(hash, 31) * MIX_CONSTANT;

    }

    private static int randomInRange(long hash, int minInclusive, int maxInclusive) {

        if (maxInclusive <= minInclusive) {
            return minInclusive;
        }
        long span = (long) maxInclusive - minInclusive + 1L;
        return minInclusive + (int) Long.remainderUnsigned(hash, span);

    }
}
