package com.danielpan888.liminalness;

import com.danielpan888.liminalness.dimension.DimensionManager;
import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;
import com.danielpan888.liminalness.dimension.RegisterChunkGenerator;
import com.danielpan888.liminalness.dimension.bedlinkage.BedLinkDestination;
import com.danielpan888.liminalness.dimension.bedlinkage.BedLinkHandler;
import com.danielpan888.liminalness.util.SchematicLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
// TODO: fix reenter dimension fallback 0 128 0

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
        DimensionManager.loadConfigs(event.getServer(), seed);
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

        if (gen.committedChunks.contains(key)) return;

        int minX = chunkPosition.getMinBlockX(), maxX = minX + 16;
        int minZ = chunkPosition.getMinBlockZ(), maxZ = minZ + 16;

        Set<BlockPos> nearbyRooms = gen.spatialIndex.getRoomsInChunk(minX, maxX, minZ, maxZ);
        boolean allResolved = true;

        for (BlockPos origin : nearbyRooms) {
            SchematicLoader.Schematic schematic = gen.roomOrigins.get(origin);
            if (schematic == null) { allResolved = false; continue; }

            int[] e = gen.getExtents(schematic);
            if (origin.getX() + e[0] < minX || origin.getX() >= maxX) continue;
            if (origin.getZ() + e[2] < minZ || origin.getZ() >= maxZ) continue;

            for (var block : schematic.finalBlocks().entrySet()) {
                BlockPos world = origin.offset(block.getKey());
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;

                BlockState target = block.getValue();
                gen.serverLevel.setBlock(world, target, Block.UPDATE_CLIENTS);
            }

        }

        if (allResolved) {
            gen.committedChunks.add(key);
        } else {
            gen.markChunkStale(key);
        }

    }

    private void checkPortals(MinecraftServer server) {


        for (var entry : DimensionManager.getInstances().entrySet()) {
            ResourceLocation dimId = entry.getKey();
            FrontierChunkGenerator gen = (FrontierChunkGenerator) entry.getValue();

            if (gen.serverLevel == null || gen.portalPositions.isEmpty()) continue;

            List<ServerPlayer> players = new ArrayList<>(gen.serverLevel.players());

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
                        4,
                        0.3,
                        0.3,
                        0.3,
                        0.0
                    );

                }
            }


            for (ServerPlayer player : players) {
                BlockPos feet = player.blockPosition();
                if (gen.portalPositions.contains(feet)) {
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

        if (!(state.getBlock() instanceof BedBlock)) {
            return;
        }

        Optional<BedLinkDestination> destination = BedLinkHandler.resolveDestination(player, pos);
        if (destination.isEmpty()) {
            return;
        }

        BedLinkDestination link = destination.get();
        Vec3 spawnPos = link.spawnPos();
        ServerLevel targetLevel = link.level();

        savedPositions.put(player.getUUID(), new OverworldPosition(
            player.getX(),
            player.getY(),
            player.getZ(),
            player.getYRot(),
            player.getXRot()
        ));

        player.teleportTo(
            targetLevel,
            spawnPos.x,
            spawnPos.y,
            spawnPos.z,
            player.getYRot(),
            player.getXRot()
        );

        server.execute(() -> {
            player.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), player.getXRot());
            player.connection.teleport(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), player.getXRot());
        });
    }
}
