package com.danielpan888.liminalness.dimension.generators;

import com.danielpan888.liminalness.dimension.DimensionManager;
import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;

import com.mojang.serialization.MapCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;

public class Backrooms extends FrontierChunkGenerator {

    public static final ResourceLocation ID = ResourceLocation.parse("liminalness:dim_backrooms");

    public static final MapCodec<Backrooms> CODEC =
        BiomeSource.CODEC.fieldOf("biome_source")
            .xmap(
                biomeSource -> {
                    Backrooms instance = new Backrooms(biomeSource);
                    DimensionManager.register(ID);
                    return instance;
                },
                g -> g.getBiomeSource()
            );

    public Backrooms(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    public ResourceLocation getDimensionId() {
        return ID;
    }

    @Override
    protected MapCodec<? extends FrontierChunkGenerator> codec() {
        return CODEC;
    }


}
