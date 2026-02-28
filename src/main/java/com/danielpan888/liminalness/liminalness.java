package com.danielpan888.liminalness;

import com.danielpan888.liminalness.dimension.DimensionManager;
import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;
import com.danielpan888.liminalness.dimension.RegisterChunkGenerator;
import com.danielpan888.liminalness.util.SchematicLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.*;

// TODO: organize schematic marker and portals?

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(liminalness.MODID)
public class liminalness {

    public static final String MODID = "liminalness";
    public static final Logger LOGGER = LogUtils.getLogger();


    private record OverworldPosition(
        double x, double y, double z,
        float yRot, float xRot
    ) {}

    private static final Map<UUID, OverworldPosition> savedPositions = new HashMap<>();

    private int particleTick = 0;
    private static final int PARTICLE_INTERVAL = 20; // every 10 ticks = 0.5 seconds

    public liminalness(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        RegisterChunkGenerator.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        DimensionManager.register(ResourceLocation.parse("liminalness:dim_backrooms"));
        LOGGER.info("Dimensions registered");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        DimensionManager.loadConfigs(seed);
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        DimensionManager.onLevelLoad(level);
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        DimensionManager.onLevelUnload(level);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        DimensionManager.onServerStop(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        DimensionManager.onServerTick(event.getServer());
        checkPortals(event.getServer());
    }

    // intercept server to update client chunks, prevents client race condition
    @SubscribeEvent
    public void onChunkWatch(ChunkWatchEvent.Watch event) {

        ResourceLocation dimId = event.getLevel().dimension().location();
        FrontierChunkGenerator gen = (FrontierChunkGenerator) DimensionManager.getInstance(dimId);
        if (gen == null || gen.serverLevel == null) return;

        ChunkPos chunkPosition = event.getPos();
        long key = FrontierChunkGenerator.chunkKey(chunkPosition.x, chunkPosition.z);

        if (gen.patchedChunks.contains(key)) return;
        gen.patchedChunks.add(key);

        int minX = chunkPosition.getMinBlockX(), maxX = minX + 16;
        int minZ = chunkPosition.getMinBlockZ(), maxZ = minZ + 16;

        for (var entry : gen.roomOrigins.entrySet()) {
            BlockPos origin = entry.getKey();
            SchematicLoader.Schematic schematic = entry.getValue();
            int[] e = gen.getExtents(schematic);

            if (origin.getX() + e[0] < minX || origin.getX() >= maxX) continue;
            if (origin.getZ() + e[2] < minZ || origin.getZ() >= maxZ) continue;

            for (var block : schematic.blocks().entrySet()) {
                BlockPos world = origin.offset(block.getKey());
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;

                BlockState existing = gen.serverLevel.getBlockState(world);
                if (!existing.is(Blocks.SMOOTH_SANDSTONE)) continue;
                gen.serverLevel.setBlock(world, block.getValue(), Block.UPDATE_CLIENTS);
            }

            for (BlockPos marker : schematic.markers()) {
                BlockPos world = origin.offset(marker);
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;

                BlockState existing = gen.serverLevel.getBlockState(world);
                if (!existing.is(Blocks.SMOOTH_SANDSTONE)) continue;
                gen.serverLevel.setBlock(world, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }

            // After replacing connection markers with air
            for (var block : schematic.blocks().entrySet()) {
                if (block.getValue().is(FrontierChunkGenerator.PORTAL_MARKER)) {
                    BlockPos world = origin.offset(block.getKey());
                    if (world.getX() < minX || world.getX() >= maxX) continue;
                    if (world.getZ() < minZ || world.getZ() >= maxZ) continue;
                    gen.serverLevel.setBlock(world, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }


        }
    }

    private void checkPortals(MinecraftServer server) {

        particleTick += 1;

        for (var entry : DimensionManager.getInstances().entrySet()) {
            ResourceLocation dimId = entry.getKey();
            FrontierChunkGenerator gen = (FrontierChunkGenerator) entry.getValue();

            if (gen.serverLevel == null || gen.portalPositions.isEmpty()) continue;

            List<ServerPlayer> players = new ArrayList<>(gen.serverLevel.players());

            if (particleTick >= PARTICLE_INTERVAL) {
                for (BlockPos portalPos : gen.portalPositions) {
                    for (ServerPlayer player : players) {
                        if (player.blockPosition().distSqr(portalPos) > 16 * 16) continue;

                        ParticleOptions portalParticle = new DustColorTransitionOptions(
                            new Vector3f(0.27f, 0.0f, 0.58f), // purple
                            new Vector3f(0.0f, 0.0f, 0.0f), // black
                            1.5f
                        );

                        gen.serverLevel.sendParticles(
                            portalParticle,
                            portalPos.getX() + 0.5,
                            portalPos.getY() + 0.5,
                            portalPos.getZ() + 0.5,
                            2,
                            0.3,
                            0.3,
                            0.3,
                            0.0
                        );

                    }
                }
            }

            for (ServerPlayer player : players) {
                BlockPos feet = player.blockPosition();
                if (gen.portalPositions.contains(feet) || gen.portalPositions.contains(feet.below())) {
                    handlePortalTrigger(player, dimId, server);
                }
            }
        }
    }

    private void handlePortalTrigger(ServerPlayer player, ResourceLocation fromDim, MinecraftServer server) {

        // backrooms -> overworld

        // TODO: more dimensions? more schematic themes etc
        if (fromDim.equals(ResourceLocation.parse("liminalness:dim_backrooms"))) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) return;

            OverworldPosition saved = savedPositions.remove(player.getUUID());

            double x = saved != null ? saved.x() : overworld.getSharedSpawnPos().getX() + 0.5;
            double y = saved != null ? saved.y() : overworld.getSharedSpawnPos().getY();
            double z = saved != null ? saved.z() : overworld.getSharedSpawnPos().getZ() + 0.5;
            float yRot = saved != null ? saved.yRot() : player.getYRot();
            float xRot = saved != null ? saved.xRot() : player.getXRot();

            player.teleportTo(overworld, x, y, z, Set.of(), yRot, xRot);

            final double fx = x, fy = y, fz = z;
            final float fyRot = yRot, fxRot = xRot;
            server.execute(() -> player.connection.teleport(fx, fy, fz, fyRot, fxRot));
        }
    }

    // enter dimension
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = event.getEntity().getServer();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        // is bed and no skylight and bfs has no light anywhere 5 radius
        if (state.getBlock() instanceof BedBlock) {

            if (level.getBrightness(LightLayer.SKY, pos) > 0) return;

            int radius = 5;
            boolean canTp = true;
            Queue<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> visited = new HashSet<>();

            queue.add(pos);
            visited.add(pos);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();

                BlockState checkState = level.getBlockState(current);


                if (!checkState.isAir()) {
                    if (level.getBrightness(LightLayer.BLOCK, current) > 0) {
                        canTp = false;
                        break;
                    }
                    continue;
                }

                for (Direction dir : Direction.values()) {
                    BlockPos neighbour = current.relative(dir);

                    if (visited.contains(neighbour)) continue;

                    if (Math.abs(neighbour.getX() - pos.getX()) > radius) continue;
                    if (Math.abs(neighbour.getY() - pos.getY()) > radius) continue;
                    if (Math.abs(neighbour.getZ() - pos.getZ()) > radius) continue;

                    visited.add(neighbour);
                    queue.add(neighbour);
                }
            }

            // teleport to dimension
            if (canTp) {
                ResourceKey<Level> backroomsKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("liminalness:dim_backrooms"));

                ServerLevel backrooms = server.getLevel(backroomsKey);
                if (backrooms == null) {
                    return;
                }

                savedPositions.put(player.getUUID(), new OverworldPosition(
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        player.getYRot(),
                        player.getXRot()
                ));

                player.teleportTo(
                        backrooms,
                        0, 0, 0,
                        player.getYRot(),
                        player.getXRot()
                );

                server.execute(() -> {
                    player.moveTo(0.5, 128.0, 0.5, player.getYRot(), player.getXRot());
                    player.connection.teleport(0.5, 128.0, 0.5, player.getYRot(), player.getXRot());
                });

            }
        }
    }
}
