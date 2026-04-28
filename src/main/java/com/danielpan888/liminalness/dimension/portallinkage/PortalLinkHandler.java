package com.danielpan888.liminalness.dimension.portallinkage;

import com.danielpan888.liminalness.dimension.DimensionManager;
import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PortalLinkHandler {

    public record PortalKey(ResourceLocation dimensionId, BlockPos portalPos) {}

    public record PortalLink(
        boolean returnOrigin,
        ResourceLocation targetDimensionId,
        int targetCenterX,
        int targetCenterZ
    ) {}

    public record ReturnPoint(
        ResourceLocation dimensionId,
        double x,
        double y,
        double z,
        float yRot,
        float xRot
    ) {}

    public record PortalTeleportTarget(
        ServerLevel level,
        Vec3 position,
        float yRot,
        float xRot
    ) {}

    private static final long MIX_CONSTANT = 0x94D049BB133111EBL;

    private PortalLinkHandler() {}

    public static void rememberReturnPoint(ServerPlayer player, ServerLevel sourceLevel, BlockPos bedPos) {

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        PortalLinkSavedData data = PortalLinkSavedData.get(server);
        data.putReturnPoint(player.getUUID(), new ReturnPoint(
            sourceLevel.dimension().location(),
            bedPos.getX() + 0.5,
            bedPos.getY() + 0.6,
            bedPos.getZ() + 0.5,
            player.getYRot(),
            player.getXRot()

        ));
    }

    public static Optional<PortalTeleportTarget> resolveDestination(ServerPlayer player, ResourceLocation fromDimension, BlockPos portalPos) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }

        PortalLinkSavedData data = PortalLinkSavedData.get(server);
        PortalKey key = new PortalKey(fromDimension, portalPos.immutable());

        PortalLink link = data.getLink(key);
        if (link == null) {
            link = createLink(player, fromDimension, portalPos);
            if (link == null) {
                return Optional.empty();
            }
            data.putLink(key, link);
        }

        return Optional.of(resolveTarget(server, player, link, data));
    }

    public static Optional<PortalTeleportTarget> resolveDirectReturn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }

        PortalLinkSavedData data = PortalLinkSavedData.get(server);
        return Optional.of(resolveReturnTarget(server, player, data));
    }

    private static PortalLink createLink(ServerPlayer player, ResourceLocation fromDimension, BlockPos portalPos) {

        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }

        List<ResourceLocation> availableDimensions = getAvailableManagedDimensions(server, fromDimension);
        if (availableDimensions.isEmpty() || player.getRandom().nextBoolean()) {
            return new PortalLink(true, null, 0, 0);
        }

        ResourceLocation targetDimension = availableDimensions.get(player.getRandom().nextInt(availableDimensions.size()));
        FrontierChunkGenerator generator = resolveManagedGenerator(server, targetDimension);
        if (generator == null || !generator.isReady()) {
            return new PortalLink(true, null, 0, 0);
        }

        long worldSeed = server.getWorldData().worldGenOptions().seed();
        long locationHash = mixPortalHash(worldSeed, fromDimension, portalPos, targetDimension);
        int spawnRange = Math.max(generator.radiusHorizontal, 8192);

        locationHash = Long.rotateLeft(locationHash, 17) * MIX_CONSTANT;
        int targetCenterX = randomInRange(locationHash, -spawnRange, spawnRange);

        locationHash = Long.rotateLeft(locationHash, 17) * MIX_CONSTANT;
        int targetCenterZ = randomInRange(locationHash, -spawnRange, spawnRange);

        return new PortalLink(false, targetDimension, targetCenterX, targetCenterZ);

    }

    private static PortalTeleportTarget resolveTarget(MinecraftServer server, ServerPlayer player, PortalLink link, PortalLinkSavedData data) {

        if (link.returnOrigin()) {
            return resolveReturnTarget(server, player, data);

        }

        ResourceLocation targetDimension = link.targetDimensionId();
        ServerLevel targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, targetDimension));
        FrontierChunkGenerator generator = resolveManagedGenerator(server, targetDimension);
        if (targetLevel == null || generator == null || !generator.isReady()) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            Vec3 fallback = new Vec3(
                overworld.getSharedSpawnPos().getX() + 0.5,
                overworld.getSharedSpawnPos().getY(),
                overworld.getSharedSpawnPos().getZ() + 0.5
            );

            return new PortalTeleportTarget(overworld, fallback, player.getYRot(), player.getXRot());

        }

        Vec3 spawnPos = generator.ensureLinkedSpawn(link.targetCenterX(), link.targetCenterZ());
        return new PortalTeleportTarget(targetLevel, spawnPos, player.getYRot(), player.getXRot());
    }

    private static PortalTeleportTarget resolveReturnTarget(MinecraftServer server, ServerPlayer player, PortalLinkSavedData data) {
        ReturnPoint point = data.removeReturnPoint(player.getUUID());

        if (point != null) {
            ServerLevel returnLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, point.dimensionId()));
            if (returnLevel != null) {
                return new PortalTeleportTarget(
                    returnLevel,
                    new Vec3(point.x(), point.y(), point.z()),
                    point.yRot(),
                    point.xRot()
                );
            }
        }

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        Vec3 fallback = new Vec3(
            overworld.getSharedSpawnPos().getX() + 0.5,
            overworld.getSharedSpawnPos().getY(),
            overworld.getSharedSpawnPos().getZ() + 0.5
        );
        return new PortalTeleportTarget(overworld, fallback, player.getYRot(), player.getXRot());
    }

    private static FrontierChunkGenerator resolveManagedGenerator(MinecraftServer server, ResourceLocation dimensionId) {

        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (level == null) {
            return null;
        }

        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        FrontierChunkGenerator generator = chunkGenerator instanceof FrontierChunkGenerator frontier ? frontier : (FrontierChunkGenerator) DimensionManager.getInstance(dimensionId);


        if (generator != null) {
            generator.serverLevel = level;
            generator.dimensionId = dimensionId;
        }

        return generator;
    }

    private static List<ResourceLocation> getAvailableManagedDimensions(MinecraftServer server, ResourceLocation fromDimension) {

        List<ResourceLocation> dimensions = new ArrayList<>();

        for (ResourceLocation dimensionId : DimensionManager.getRegisteredIds()) {
            if (dimensionId.equals(fromDimension)) {
                continue;
            }

            FrontierChunkGenerator generator = resolveManagedGenerator(server, dimensionId);
            if (generator != null && generator.isReady()) {
                dimensions.add(dimensionId);
            }
        }
        dimensions.sort(Comparator.comparing(ResourceLocation::toString));
        return dimensions;
    }

    private static long mixPortalHash(long worldSeed, ResourceLocation fromDimension, BlockPos portalPos, ResourceLocation targetDimension) {

        long hash = worldSeed;
        hash ^= fromDimension.toString().hashCode();
        hash ^= (long) portalPos.getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) portalPos.getY() * 0x6C62272E07BB0142L;
        hash ^= (long) portalPos.getZ() * 0xD2A98B26625EEE7BL;
        hash ^= Long.rotateLeft(targetDimension.toString().hashCode(), 19);
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
