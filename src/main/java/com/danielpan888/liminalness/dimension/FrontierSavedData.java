package com.danielpan888.liminalness.dimension;

import com.danielpan888.liminalness.liminalness;
import com.danielpan888.liminalness.util.SchematicLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FrontierSavedData extends SavedData {

    private final List<RoomRecord> rooms   = new ArrayList<>();
    private final Set<BlockPos> claimed = new HashSet<>();
    private final Set<Long> committed = new HashSet<>();
    private record RoomRecord(BlockPos origin, String schematicPath) {}
    private final Set<BlockPos> portalPositions = new HashSet<>();
    private final Set<BlockPos> consumedChests = new HashSet<>();

    private static String dataName(FrontierChunkGenerator gen) {
        return "frontier_" + gen.getDimensionId().toString().replace(":", "_");
    }

    // --- load ---

    public static FrontierSavedData load(ServerLevel level, FrontierChunkGenerator gen) {
        FrontierSavedData data = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(FrontierSavedData::new, (tag, provider) -> fromNbt(tag)), dataName(gen));
        liminalness.LOGGER.info("frontier saved data - {}: loaded save — {} rooms, {} claimed", gen.getDimensionId(), data.rooms.size(), data.claimed.size());
        return data;
    }

    private static FrontierSavedData fromNbt(CompoundTag tag) {
        FrontierSavedData data = new FrontierSavedData();

        ListTag roomsTag = tag.getList("rooms", Tag.TAG_COMPOUND);
        for (int i = 0; i < roomsTag.size(); i++) {
            CompoundTag r = roomsTag.getCompound(i);
            data.rooms.add(new RoomRecord(new BlockPos(r.getInt("x"), r.getInt("y"), r.getInt("z")), r.getString("schematic")));
        }

        ListTag claimedTag = tag.getList("claimed", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimedTag.size(); i++) {
            CompoundTag c = claimedTag.getCompound(i);
            data.claimed.add(new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z")));
        }

        ListTag committedTag = tag.getList("committed_chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < committedTag.size(); i++) {
            data.committed.add(committedTag.getCompound(i).getLong("ck"));
        }

        ListTag portalsTag = tag.getList("portal_positions", Tag.TAG_COMPOUND);
        for (int i = 0; i < portalsTag.size(); i++) {
            CompoundTag p = portalsTag.getCompound(i);
            data.portalPositions.add(new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z")));
        }

        ListTag consumedTag = tag.getList("consumed_chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < consumedTag.size(); i++) {
            CompoundTag c = consumedTag.getCompound(i);
            data.consumedChests.add(new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z")));
        }

        liminalness.LOGGER.info("frontier saved data — rooms={} claimed={} portals={}", data.rooms.size(), data.claimed.size(), data.portalPositions.size());

        return data;
    }

    // --- give to generator ---

    public void applyTo(FrontierChunkGenerator gen) {
        gen.roomOrigins.clear();
        gen.claimed.clear();
        gen.portalPositions.clear();
        gen.consumedChests.clear();
        gen.spatialIndex.clear();
        gen.persistedRooms.clear();
        gen.committedChunks.clear();
        gen.resetStaleChunkTracking();

        for (RoomRecord rr : rooms) {
            SchematicLoader.Schematic schematic = gen.getSchematicByPath(rr.schematicPath());
            if (schematic != null) {
                gen.roomOrigins.put(rr.origin(), schematic);
                gen.spatialIndex.add(rr.origin(), gen.getExtents(schematic));
                gen.persistedRooms.add(rr.origin());
            } else {
                liminalness.LOGGER.warn("frontier saved data - {}: could not resolve schematic path: {}", gen.getDimensionId(), rr.schematicPath());
            }
        }

        gen.claimed.addAll(claimed);
        gen.committedChunks.addAll(committed);
        gen.portalPositions.addAll(portalPositions);
        gen.consumedChests.addAll(consumedChests);

        for (BlockPos origin : gen.persistedRooms) {
            SchematicLoader.Schematic schematic = gen.roomOrigins.get(origin);
            if (schematic == null) continue;
            int[] extents = gen.getExtents(schematic);
            int minCX = origin.getX() >> 4, maxCX = (origin.getX() + extents[0]) >> 4;
            int minCZ = origin.getZ() >> 4, maxCZ = (origin.getZ() + extents[2]) >> 4;
            for (int cx = minCX; cx <= maxCX; cx++)
                for (int cz = minCZ; cz <= maxCZ; cz++)
                    gen.markChunkStale(FrontierChunkGenerator.chunkKey(cx, cz));
        }

        // Rebuild frontier queues from restored state
        gen.reconstructFrontier();

        liminalness.LOGGER.info("frontier saved data - {}: applied save — {} rooms, {} claimed", gen.getDimensionId(), gen.roomOrigins.size(), gen.claimed.size());
    }

    public void syncFrom(FrontierChunkGenerator gen) {
        rooms.clear();
        claimed.clear();
        portalPositions.clear();
        consumedChests.clear();
        committed.clear();

        for (var entry : gen.roomOrigins.entrySet()) {
            rooms.add(new RoomRecord(
                    entry.getKey(),
                    gen.getPathBySchematic(entry.getValue())
            ));
        }

        claimed.addAll(gen.claimed);
        portalPositions.addAll(gen.portalPositions);
        consumedChests.addAll(gen.consumedChests);
        committed.addAll(gen.committedChunks);

    }

    public static void saveNow(ServerLevel level, FrontierChunkGenerator gen) {

        if (gen.roomOrigins.isEmpty()) {
            liminalness.LOGGER.info("frontier saved data - {}: skipping save — no rooms generated", gen.getDimensionId());
            return;
        }

        try {
            FrontierSavedData data = level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                    FrontierSavedData::new, (tag, provider) -> fromNbt(tag)
                ),
                dataName(gen)
            );

            data.syncFrom(gen);

            liminalness.LOGGER.info("{}: saving — rooms={} claimed={}", gen.getDimensionId(), gen.roomOrigins.size(), gen.claimed.size());

            data.setDirty();
            level.getDataStorage().save();

            liminalness.LOGGER.info("frontier saved data - {}: saved — {} rooms, {} claimed", gen.getDimensionId(), gen.roomOrigins.size(), gen.claimed.size());
        } catch (Exception e) {
            liminalness.LOGGER.error("frontier saved data - {}: failed to save", gen.getDimensionId(), e);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag roomsTag = new ListTag();
        for (RoomRecord rr : rooms) {
            CompoundTag r = new CompoundTag();
            r.putInt("x", rr.origin().getX());
            r.putInt("y", rr.origin().getY());
            r.putInt("z", rr.origin().getZ());
            r.putString("schematic", rr.schematicPath());
            roomsTag.add(r);
        }
        tag.put("rooms", roomsTag);

        ListTag claimedTag = new ListTag();
        for (BlockPos pos : claimed) {
            CompoundTag c = new CompoundTag();
            c.putInt("x", pos.getX());
            c.putInt("y", pos.getY());
            c.putInt("z", pos.getZ());
            claimedTag.add(c);
        }
        tag.put("claimed", claimedTag);

        ListTag committedTag = new ListTag();
        for (long ck : committed) {
            CompoundTag c = new CompoundTag();
            c.putLong("ck", ck);
            committedTag.add(c);
        }
        tag.put("committed_chunks", committedTag);

        ListTag portalsTag = new ListTag();
        for (BlockPos pos : portalPositions) {
            CompoundTag p = new CompoundTag();
            p.putInt("x", pos.getX());
            p.putInt("y", pos.getY());
            p.putInt("z", pos.getZ());
            portalsTag.add(p);
        }
        tag.put("portal_positions", portalsTag);

        ListTag consumedTag = new ListTag();
        for (BlockPos pos : consumedChests) {
            CompoundTag c = new CompoundTag();
            c.putInt("x", pos.getX());
            c.putInt("y", pos.getY());
            c.putInt("z", pos.getZ());
            consumedTag.add(c);
        }
        tag.put("consumed_chests", consumedTag);

        liminalness.LOGGER.info("save — rooms={} claimed={} portals={}", rooms.size(), claimed.size(), portalPositions.size());

        return tag;
    }
}
