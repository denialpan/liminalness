package com.danielpan888.liminalness.util;

import com.danielpan888.liminalness.Config;
import com.danielpan888.liminalness.liminalness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private record ItemProfile(int maxStackSize, List<Holder<Enchantment>> validEnchantments) {}

    private static List<Item> cachedItemPool;
    private static Set<String> cachedBlacklistedModIds = Set.of();
    private static HolderLookup.RegistryLookup<Enchantment> cachedEnchantmentRegistry;
    private static List<Holder<Enchantment>> cachedEnchantmentPool = List.of();
    private static final Map<Item, ItemProfile> ITEM_PROFILES = new HashMap<>();

    // build item pool once from the registry
    public static synchronized List<Item> getItemPool() {
        Set<String> blacklistedModIds = Set.copyOf(Config.LIMINALNESS_BLACKLISTED_MOD_IDS.get());
        if (cachedItemPool != null && cachedBlacklistedModIds.equals(blacklistedModIds)) {
            return cachedItemPool;
        }

        List<Item> itemPool = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BLACKLIST.contains(item)) continue;
            if (item.getDefaultInstance().isEmpty()) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (blacklistedModIds.contains(itemId.getNamespace())) continue;
            itemPool.add(item);
        }

        cachedBlacklistedModIds = blacklistedModIds;
        cachedItemPool = List.copyOf(itemPool);
        ITEM_PROFILES.clear();

        liminalness.LOGGER.info("chest loot handler - built item pool with {} items", cachedItemPool.size());
        return cachedItemPool;
    }

    public static boolean fillChest(ServerLevel level, BlockPos pos, long worldSeed) {
        if (level == null || pos == null) return false;

        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            return false;
        }

        List<Item> pool = getItemPool();
        if (pool.isEmpty()) return false;

        long hash = worldSeed;
        hash ^= (long) pos.getX() * 0x9E3779B97F4A7C15L;
        hash ^= (long) pos.getY() * 0x6C62272E07BB0142L;
        hash ^= (long) pos.getZ() * 0xD2A98B26625EEE7BL;
        hash  = Long.rotateLeft(hash, 31) * 0x94D049BB133111EBL;
        Random rand = new Random(hash);

        int maxUniqueItems = Config.LIMINALNESS_CHEST_UNIQUE_ITEMS.get();
        int itemCount = 1 + rand.nextInt(maxUniqueItems);
        int[] slots = new int[27];
        for (int i = 0; i < slots.length; i++) slots[i] = i;

        List<Holder<Enchantment>> enchantmentPool = getEnchantmentPool(level);
        boolean enableEnchantments = Config.LIMINALNESS_ENABLE_ENCHANTMENTS.get();
        boolean illegalEnchantments = Config.LIMINALNESS_ILLEGAL_ENCHANTMENTS.get();
        int maxItemCount = Config.LIMINALNESS_CHEST_ITEM_COUNT.get();
        int maxEnchantmentCount = Config.LIMINALNESS_CHEST_ENCHANTMENT_COUNT.get();
        float enchantmentRollChance = Config.LIMINALNESS_CHEST_ENCHANTMENT_ROLL_CHANCE.get() / 100.0f;

        for (int i = 0; i < itemCount && i < slots.length; i++) {
            int slotIndex = i + rand.nextInt(slots.length - i);
            int slot = slots[slotIndex];
            slots[slotIndex] = slots[i];
            slots[i] = slot;

            Item item = pool.get(rand.nextInt(pool.size()));
            ItemProfile profile = getItemProfile(item, enchantmentPool);
            int stackCap = Math.min(profile.maxStackSize(), maxItemCount);
            int count = 1 + rand.nextInt(stackCap);
            ItemStack stack = new ItemStack(item, count);

            if (enableEnchantments && !enchantmentPool.isEmpty() && rand.nextFloat() < enchantmentRollChance) {
                List<Holder<Enchantment>> applicableEnchantments = illegalEnchantments
                    ? enchantmentPool
                    : profile.validEnchantments();

                if (!applicableEnchantments.isEmpty()) {
                    int enchantCount = Math.min(1 + rand.nextInt(maxEnchantmentCount), applicableEnchantments.size());
                    applyRandomEnchantments(stack, applicableEnchantments, enchantCount, illegalEnchantments, rand);
                }
            }

            chest.setItem(slot, stack);
        }

        liminalness.LOGGER.debug("chest loot handler - filled chest at {} with {} items", pos, itemCount);
        return true;
    }

    private static synchronized List<Holder<Enchantment>> getEnchantmentPool(ServerLevel level) {
        HolderLookup.RegistryLookup<Enchantment> registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        if (registry != cachedEnchantmentRegistry) {
            cachedEnchantmentRegistry = registry;
            List<Holder<Enchantment>> enchantments = new ArrayList<>();
            registry.listElements().forEach(enchantments::add);
            cachedEnchantmentPool = List.copyOf(enchantments);
            ITEM_PROFILES.clear();
            liminalness.LOGGER.debug("chest loot handler - cached {} enchantments", cachedEnchantmentPool.size());
        }
        return cachedEnchantmentPool;
    }

    private static synchronized ItemProfile getItemProfile(Item item, List<Holder<Enchantment>> enchantmentPool) {
        ItemProfile existing = ITEM_PROFILES.get(item);
        if (existing != null) return existing;

        ItemStack defaultStack = item.getDefaultInstance();
        List<Holder<Enchantment>> valid = new ArrayList<>();
        for (Holder<Enchantment> enchantment : enchantmentPool) {
            if (enchantment.value().canEnchant(defaultStack)) {
                valid.add(enchantment);
            }
        }
        ItemProfile profile = new ItemProfile(item.getDefaultMaxStackSize(), List.copyOf(valid));
        ITEM_PROFILES.put(item, profile);
        return profile;
    }

    private static void applyRandomEnchantments(ItemStack stack,
                                                List<Holder<Enchantment>> applicable,
                                                int requestedCount,
                                                boolean allowIncompatible,
                                                Random random) {
        List<Holder<Enchantment>> remaining = new ArrayList<>(applicable);
        List<Holder<Enchantment>> selected = new ArrayList<>(requestedCount);
        Map<Holder<Enchantment>, Integer> levels = new HashMap<>();

        while (selected.size() < requestedCount && !remaining.isEmpty()) {
            int index = random.nextInt(remaining.size());
            Holder<Enchantment> candidate = remaining.get(index);
            Collections.swap(remaining, index, remaining.size() - 1);
            remaining.removeLast();

            if (!allowIncompatible && !isCompatibleWithAll(candidate, selected)) {
                continue;
            }

            selected.add(candidate);
            levels.put(candidate, 1 + random.nextInt(candidate.value().getMaxLevel()));
        }

        if (!levels.isEmpty()) {
            EnchantmentHelper.updateEnchantments(stack, mutable -> levels.forEach(mutable::set));
        }
    }

    private static boolean isCompatibleWithAll(Holder<Enchantment> candidate,
                                               List<Holder<Enchantment>> selected) {
        for (Holder<Enchantment> existing : selected) {
            if (!Enchantment.areCompatible(existing, candidate)) return false;
        }
        return true;
    }

}
