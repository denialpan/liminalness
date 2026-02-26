package com.danielpan888.liminalness.dimension;

import com.danielpan888.liminalness.dimension.generators.Backrooms;
import com.danielpan888.liminalness.liminalness;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// registers chunk generators linked to specific dimension
public class RegisterChunkGenerator {

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(BuiltInRegistries.CHUNK_GENERATOR, liminalness.MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<Backrooms>> BACKROOMS =
            CHUNK_GENERATORS.register("gen_backrooms", () -> Backrooms.CODEC);

    public static void register(IEventBus bus) {
        CHUNK_GENERATORS.register(bus);
    }

    static {
        DimensionManager.register(
            ResourceLocation.parse("liminalness:dim_backrooms")
        );
    }

}