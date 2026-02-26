package com.danielpan888.liminalness;

import com.danielpan888.liminalness.dimension.DimensionManager;
import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;
import com.danielpan888.liminalness.dimension.RegisterChunkGenerator;
import com.danielpan888.liminalness.util.SchematicLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(liminalness.MODID)
public class liminalness {

    public static final String MODID = "liminalness";
    public static final Logger LOGGER = LogUtils.getLogger();

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
    }

    // intercept server to update client chunks, prevents client race condition
    @SubscribeEvent
    public void onChunkWatch(ChunkWatchEvent.Watch event) {

        ResourceLocation dimId = event.getLevel().dimension().location();
        FrontierChunkGenerator gen = (FrontierChunkGenerator) DimensionManager.getInstance(dimId);
        if (gen == null || gen.serverLevel == null) return;

        ChunkPos chunkPosition = event.getPos();
        long key = FrontierChunkGenerator.chunkKey(chunkPosition.x, chunkPosition.z);

        // Only patch once — after that Minecraft's own chunk saving handles persistence
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
        }
    }

}
