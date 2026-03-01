package com.danielpan888.liminalness.util;

import com.danielpan888.liminalness.liminalness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ChestLootHandler {

    // TODO: item tagging, mod categories?
    private static final List<Item> BLACKLIST = List.of(
        Items.AIR,
        Items.BARRIER,
        Items.COMMAND_BLOCK,
        Items.CHAIN_COMMAND_BLOCK,
        Items.REPEATING_COMMAND_BLOCK,
        Items.COMMAND_BLOCK_MINECART,
        Items.STRUCTURE_BLOCK,
        Items.STRUCTURE_VOID,
        Items.JIGSAW,
        Items.DEBUG_STICK,
        Items.LIGHT
    );

    private static List<Item> cachedItemPool = null;

    // build item pool once from the registry
    public static List<Item> getItemPool() {
        if (cachedItemPool != null) return cachedItemPool;

        cachedItemPool = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BLACKLIST.contains(item)) continue;
            if (item.getDefaultInstance().isEmpty()) continue;
            cachedItemPool.add(item);
        }

        liminalness.LOGGER.info("chest loot handler - built item pool with {} items", cachedItemPool.size());
        return cachedItemPool;
    }

    public static void fillChest(ServerLevel level, BlockPos pos, long worldSeed) {

        if (level == null || pos == null) return;
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);

        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) return;

        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);

        List<Item> pool = getItemPool();
        if (pool.isEmpty()) return;

        // Deterministic random based on position and world seed
        long hash = worldSeed;
        hash ^= (long) pos.getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) pos.getY() * 0x6C62272E07BB0142L;
        hash ^= (long) pos.getZ() * 0xD2A98B26625EEE7BL;
        hash  = Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;
        Random rand = new Random(hash);

        // 3 - 10 items
        int itemCount = 3 + rand.nextInt(8);
        List<Integer> slots = new ArrayList<>();

        for (int i = 0; i < 27; i++) slots.add(i);

        List<Holder<Enchantment>> enchantmentPool = getEnchantmentPool(level);

        for (int i = 0; i < itemCount && !slots.isEmpty(); i++) {
            int slotIndex = rand.nextInt(slots.size());
            int slot = slots.remove(slotIndex);

            Item item = pool.get(rand.nextInt(pool.size()));
            int count = 1 + rand.nextInt(Math.min(item.getDefaultMaxStackSize(), 8));
            ItemStack stack = new ItemStack(item, count);

            if (!enchantmentPool.isEmpty() && rand.nextFloat() < 0.20f) {
                int enchantCount = 1 + rand.nextInt(5);
                for (int e = 0; e < enchantCount; e++) {
                    Holder<Enchantment> enchantment =
                            enchantmentPool.get(rand.nextInt(enchantmentPool.size()));
                    int maxLevel = enchantment.value().getMaxLevel();
                    int enchantmentLevel = 1 + rand.nextInt(maxLevel);
                    stack.enchant(enchantment, enchantmentLevel);
                }
            }

            chest.setItem(slot, stack);
        }

        liminalness.LOGGER.debug("chest loot handler - chest at {} with {} items", pos, itemCount);
    }

    private static List<Holder<Enchantment>> getEnchantmentPool(ServerLevel level) {
        List<Holder<Enchantment>> pool = new ArrayList<>();
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        registry.listElements().forEach(pool::add);
        return pool;
    }

}
