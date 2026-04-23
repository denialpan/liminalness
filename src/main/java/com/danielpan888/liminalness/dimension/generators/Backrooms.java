package com.danielpan888.liminalness.dimension.generators;

import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.biome.BiomeSource;

public class Backrooms extends FrontierChunkGenerator {

    public static final MapCodec<Backrooms> CODEC =
        BiomeSource.CODEC.fieldOf("biome_source").xmap(
            Backrooms::new,
            g -> g.getBiomeSource()
        );

    public Backrooms(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends FrontierChunkGenerator> codec() {
        return CODEC;
    }


}
