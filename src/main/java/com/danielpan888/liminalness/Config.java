package com.danielpan888.liminalness;

import java.util.List;

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

    public static final ModConfigSpec.IntValue LIMINALNESS_CHEST_UNIQUE_ITEMS = BUILDER
            .comment("Number of unique item stacks that can spawn in a generated chest.")
            .defineInRange("chest_unique_items", 12, 1, 27);

    public static final ModConfigSpec.IntValue LIMINALNESS_CHEST_ITEM_COUNT = BUILDER
            .comment("Maximum count rolled for each generated chest item stack.")
            .defineInRange("chest_item_count", 20, 1, 64);

    public static final ModConfigSpec.IntValue LIMINALNESS_CHEST_ENCHANTMENT_COUNT = BUILDER
            .comment("Maximum number of enchantments that can be applied to one generated item stack.")
            .defineInRange("chest_enchantment_count", 5, 1, 40);

    public static final ModConfigSpec.IntValue LIMINALNESS_CHEST_ENCHANTMENT_ROLL_CHANCE = BUILDER
            .comment("Percent chance that a generated item stack will roll enchantments.")
            .defineInRange("chest_enchantment_roll_chance", 20, 1, 100);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> LIMINALNESS_BLACKLISTED_MOD_IDS = BUILDER
            .comment("Mod ids whose items should be excluded from generated chest loot.")
            .defineListAllowEmpty(
                    "blacklisted_mod_ids",
                    List.of(),
                    () -> "",
                    value -> value instanceof String s && !s.isBlank()
            );



    static final ModConfigSpec SPEC = BUILDER.build();
}
