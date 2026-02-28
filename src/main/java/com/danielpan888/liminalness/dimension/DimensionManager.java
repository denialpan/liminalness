package com.danielpan888.liminalness.dimension;

import com.danielpan888.liminalness.liminalness;
import com.danielpan888.liminalness.util.DimensionConfig;
import com.danielpan888.liminalness.util.DimensionConfigLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DimensionManager {

    private static final Set<ResourceLocation> registeredIds = ConcurrentHashMap.newKeySet();
    private static final Map<ResourceLocation, ChunkGenerator> instances = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, DimensionConfig> pendingConfigs = new HashMap<>();
    private static long worldSeed = 0;

    // register dimensions
    public static void register(ResourceLocation dimensionId) {
        registeredIds.add(dimensionId);
        liminalness.LOGGER.info("dimension manager - registered: {}", dimensionId);
    }

    // load dimension configs
    public static void loadConfigs(long seed) {
        worldSeed = seed;
        liminalness.LOGGER.info("dimension manager - load config for dimensions: {}", registeredIds);

        for (ResourceLocation dimId : registeredIds) {
            String configPath = "/data/" + dimId.getNamespace() + "/dimension_mod_specific/" + dimId.getPath() + ".json";
            liminalness.LOGGER.info("dimension manager - attempting to load config at {}", configPath);

            try {
                DimensionConfig config = DimensionConfigLoader.load(configPath);
                pendingConfigs.put(dimId, config);
                liminalness.LOGGER.info("dimension manager - config loaded for {} with {} schematics", dimId, config.schematics().size());
            } catch (Exception e) {
                liminalness.LOGGER.error("dimension manager - failed to load config for {} at {}: {}", dimId, configPath, e);
            }
        }
    }

    // --- events ---

    public static void onServerTick(MinecraftServer server) {

        for (ResourceLocation dimId : registeredIds) {

            // if level exists
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
            FrontierChunkGenerator generator = (FrontierChunkGenerator) instances.get(dimId);
            // If level exists but no instance yet, then get it
            if (level != null && generator == null) {
                ChunkGenerator chunkGen = level.getChunkSource().getGenerator();
                if (chunkGen instanceof FrontierChunkGenerator frontier) {
                    instances.put(dimId, frontier);
                    generator = frontier;
                    generator.serverLevel = level;
                    liminalness.LOGGER.info("dimension manager - found generator for {}", dimId);
                }
            }

            if (generator == null) continue;

            // initialize dimension on server start
            if (!generator.initialized) {

                // wait for server tick where configs are loaded.
                DimensionConfig config = pendingConfigs.get(dimId);
                if (config == null) continue;

                generator.initialize(config, worldSeed);
                generator.initialized = true;

                // load saved dungeon state with frontiers
                if (level != null) {

                    FrontierSavedData data = FrontierSavedData.load(level, generator);
                    data.applyTo(generator);

                    if (!generator.roomOrigins.isEmpty()) {
                        generator.reconstructFrontier();
                        liminalness.LOGGER.info("dimension manager - restored {} and its {} rooms", dimId, generator.roomOrigins.size());
                        generator.resume();
                    } else {
                        // FIRST time dimension generated and started in the WORLD
                        liminalness.LOGGER.info("dimension manager - creating {} for the first time", dimId);
                        generator.seedFresh();
                    }
                }
            }

            if (generator.serverLevel == null && level != null) {
                generator.serverLevel = level;
            }

            generator.tick();
        }
    }

    public static void onLevelLoad(ServerLevel level) {
        ResourceLocation dimId = level.dimension().location();
        if (!isRegistered(dimId)) return;

        ChunkGenerator chunkGen = level.getChunkSource().getGenerator();
        if (!(chunkGen instanceof FrontierChunkGenerator gen)) return;

        instances.put(dimId, gen);
        gen.serverLevel = level;

        liminalness.LOGGER.info("dimension manager - loaded {}", dimId);
    }

    public static void onLevelUnload(ServerLevel level) {
        ResourceLocation dimId = level.dimension().location();
        if (!isRegistered(dimId)) return;

        FrontierChunkGenerator gen = (FrontierChunkGenerator) instances.get(dimId);
        if (gen == null) return;

        gen.pause();
        FrontierSavedData.saveNow(level, gen);
        gen.serverLevel = null;
        instances.remove(dimId);

        liminalness.LOGGER.info("dimension manager - unloaded {}", dimId);
    }

    public static void onServerStop(MinecraftServer server) {
        for (var entry : instances.entrySet()) {
            FrontierChunkGenerator gen = (FrontierChunkGenerator) entry.getValue();
            gen.pause();
            gen.clearFrontier();
            if (gen.serverLevel != null) {
                FrontierSavedData.saveNow(gen.serverLevel, gen);
            }
            gen.serverLevel = null;
        }
        instances.clear();
        liminalness.LOGGER.info("dimension manager - server stopped, all dimensions saved");
    }

    // --- util ---

    public static boolean isRegistered(ResourceLocation dimensionId) {
        return registeredIds.contains(dimensionId);
    }

    public static ChunkGenerator getInstance(ResourceLocation dimensionId) {
        return instances.get(dimensionId);
    }

    public static Map<ResourceLocation, ChunkGenerator> getInstances() {
        return Collections.unmodifiableMap(instances);
    }


}
