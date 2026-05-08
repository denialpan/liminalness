package com.danielpan888.liminalness.util;

import com.danielpan888.liminalness.Config;
import com.danielpan888.liminalness.liminalness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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

        Set<String> blacklistedModIds = new HashSet<>(Config.LIMINALNESS_BLACKLISTED_MOD_IDS.get());
        cachedItemPool = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BLACKLIST.contains(item)) continue;
            if (item.getDefaultInstance().isEmpty()) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (blacklistedModIds.contains(itemId.getNamespace())) continue;
            cachedItemPool.add(item);
        }

        liminalness.LOGGER.info("chest loot handler - built item pool with {} items", cachedItemPool.size());
        return cachedItemPool;
    }

    public static void fillChest(ServerLevel level, BlockPos pos, long worldSeed) {
        if (level == null || pos == null) return;

        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            liminalness.LOGGER.warn("chest loot handler - no chest block entity at {}, found: {}",
                    pos, level.getBlockState(pos));
            return;
        }

        List<Item> pool = getItemPool();
        if (pool.isEmpty()) return;

        long hash = worldSeed;
        hash ^= (long) pos.getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) pos.getY() * 0x6C62272E07BB0142L;
        hash ^= (long) pos.getZ() * 0xD2A98B26625EEE7BL;
        hash  = Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;
        Random rand = new Random(hash);

        int maxUniqueItems = Config.LIMINALNESS_CHEST_UNIQUE_ITEMS.get();
        int itemCount = 1 + rand.nextInt(maxUniqueItems);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(i);

        List<Holder<Enchantment>> enchantmentPool = getEnchantmentPool(level);
        boolean enableEnchantments = Config.LIMINALNESS_ENABLE_ENCHANTMENTS.get();
        boolean illegalEnchantments = Config.LIMINALNESS_ILLEGAL_ENCHANTMENTS.get();
        int maxItemCount = Config.LIMINALNESS_CHEST_ITEM_COUNT.get();
        int maxEnchantmentCount = Config.LIMINALNESS_CHEST_ENCHANTMENT_COUNT.get();
        float enchantmentRollChance = Config.LIMINALNESS_CHEST_ENCHANTMENT_ROLL_CHANCE.get() / 100.0f;

        for (int i = 0; i < itemCount && !slots.isEmpty(); i++) {
            int slotIndex = rand.nextInt(slots.size());
            int slot = slots.remove(slotIndex);

            Item item = pool.get(rand.nextInt(pool.size()));
            int stackCap = Math.min(item.getDefaultMaxStackSize(), maxItemCount);
            int count = 1 + rand.nextInt(stackCap);
            ItemStack stack = new ItemStack(item, count);

            if (enableEnchantments && !enchantmentPool.isEmpty() && rand.nextFloat() < enchantmentRollChance) {
                List<Holder<Enchantment>> applicableEnchantments = !illegalEnchantments ? getValidEnchantmentsForStack(stack, enchantmentPool) : enchantmentPool;

                if (applicableEnchantments.isEmpty()) {
                    chest.setItem(slot, stack);
                    continue;
                }

                int enchantCount = 1 + rand.nextInt(maxEnchantmentCount);
                for (int e = 0; e < enchantCount; e++) {
                    Holder<Enchantment> enchantment = applicableEnchantments.get(rand.nextInt(applicableEnchantments.size()));
                    int maxLevel = enchantment.value().getMaxLevel();
                    int enchantmentLevel = 1 + rand.nextInt(maxLevel);
                    stack.enchant(enchantment, enchantmentLevel);
                }
            }

            chest.setItem(slot, stack);
        }

        liminalness.LOGGER.debug("chest loot handler - filled chest at {} with {} items", pos, itemCount);
    }

    private static List<Holder<Enchantment>> getEnchantmentPool(ServerLevel level) {
        List<Holder<Enchantment>> pool = new ArrayList<>();
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        registry.listElements().forEach(pool::add);
        return pool;
    }

    private static List<Holder<Enchantment>> getValidEnchantmentsForStack(ItemStack stack, List<Holder<Enchantment>> enchantmentPool) {
        List<Holder<Enchantment>> valid = new ArrayList<>();
        for (Holder<Enchantment> enchantment : enchantmentPool) {
            if (enchantment.value().canEnchant(stack)) {
                valid.add(enchantment);
            }
        }
        return valid;
    }

}
