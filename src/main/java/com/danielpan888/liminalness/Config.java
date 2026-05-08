package com.danielpan888.liminalness;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue LIMINALNESS_RECENCY_WINDOW = BUILDER
            .comment("Recency window for schematic history to affect schematic weight penalties. This helps to prevent one schematic to dominate generation.")
            .defineInRange("recency_window", 10, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue LIMINALNESS_MINIMUM_ROOMS = BUILDER
            .comment("Minimum amount of rooms expected. This aggressively lets the initial rooms have many connections to expand upon.")
            .defineInRange("minimum_rooms", 500, 10, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue LIMINALNESS_STEPS_PER_TICK = BUILDER
            .comment("Number of rooms to generate per tick. Increase this value to allow the mod to generate the number of rooms faster, reduce to prevent CPU load.")
            .defineInRange("steps_per_tick", 10, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue LIMINALNESS_TELEPORT_RANGE = BUILDER
            .comment("Max radius of blocks from 0, 0 that a player can end up on random teleportation.")
            .defineInRange("teleport_range", 2560000, 2000, Integer.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue LIMINALNESS_ENABLE_ENCHANTMENTS = BUILDER
            .comment("Random chest loot can spawn with enchantments.")
            .define("enable_enchantments", true);

    public static final ModConfigSpec.BooleanValue LIMINALNESS_ILLEGAL_ENCHANTMENTS = BUILDER
            .comment("random chest loot can spawn generate illegal enchantment combinations.")
            .define("illegal_enchantments", true);



    static final ModConfigSpec SPEC = BUILDER.build();
}
