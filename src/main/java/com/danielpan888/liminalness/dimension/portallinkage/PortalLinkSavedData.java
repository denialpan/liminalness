package com.danielpan888.liminalness.dimension.portallinkage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PortalLinkSavedData extends SavedData {

    private static final String DATA_NAME = "liminalness_portal_links";

    private final Map<PortalLinkHandler.PortalKey, PortalLinkHandler.PortalLink> links = new HashMap<>();
    private final Map<UUID, PortalLinkHandler.ReturnPoint> returnPoints = new HashMap<>();

    public static PortalLinkSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(new SavedData.Factory<>(PortalLinkSavedData::new, (tag, provider) -> fromNbt(tag)), DATA_NAME);
    }

    public PortalLinkHandler.PortalLink getLink(PortalLinkHandler.PortalKey key) {
        return links.get(key);
    }

    public void putLink(PortalLinkHandler.PortalKey key, PortalLinkHandler.PortalLink link) {
        links.put(key, link);
        setDirty();
    }

    public PortalLinkHandler.ReturnPoint getReturnPoint(UUID playerId) {
        return returnPoints.get(playerId);
    }

    public PortalLinkHandler.ReturnPoint removeReturnPoint(UUID playerId) {

        PortalLinkHandler.ReturnPoint point = returnPoints.remove(playerId);
        if (point != null) {
            setDirty();
        }

        return point;
    }

    public void putReturnPoint(UUID playerId, PortalLinkHandler.ReturnPoint point) {
        returnPoints.put(playerId, point);
        setDirty();
    }

    private static PortalLinkSavedData fromNbt(CompoundTag tag) {

        PortalLinkSavedData data = new PortalLinkSavedData();

        ListTag linksTag = tag.getList("links", Tag.TAG_COMPOUND);
        for (int i = 0; i < linksTag.size(); i++) {
            CompoundTag entry = linksTag.getCompound(i);
            ResourceLocation sourceDimension = ResourceLocation.parse(entry.getString("source_dimension"));
            ResourceLocation targetDimension = entry.contains("target_dimension") ? ResourceLocation.parse(entry.getString("target_dimension")) : null;

            PortalLinkHandler.PortalKey key = new PortalLinkHandler.PortalKey(
                sourceDimension,
                new net.minecraft.core.BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"))
            );

            PortalLinkHandler.PortalLink link = new PortalLinkHandler.PortalLink(
                entry.getBoolean("return_origin"),
                targetDimension,
                entry.getInt("target_center_x"),
                entry.getInt("target_center_z")
            );

            data.links.put(key, link);
        }

        ListTag returnTag = tag.getList("return_points", Tag.TAG_COMPOUND);
        for (int i = 0; i < returnTag.size(); i++) {

            CompoundTag entry = returnTag.getCompound(i);
            UUID playerId = entry.getUUID("player_id");
            ResourceLocation dimensionId = ResourceLocation.parse(entry.getString("dimension"));

            PortalLinkHandler.ReturnPoint point = new PortalLinkHandler.ReturnPoint(
                dimensionId,
                entry.getDouble("x"),
                entry.getDouble("y"),
                entry.getDouble("z"),
                entry.getFloat("y_rot"),
                entry.getFloat("x_rot")
            );
            data.returnPoints.put(playerId, point);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag linksTag = new ListTag();
        for (Map.Entry<PortalLinkHandler.PortalKey, PortalLinkHandler.PortalLink> entry : links.entrySet()) {
            CompoundTag linkTag = new CompoundTag();
            PortalLinkHandler.PortalKey key = entry.getKey();
            PortalLinkHandler.PortalLink link = entry.getValue();

            linkTag.putString("source_dimension", key.dimensionId().toString());
            linkTag.putInt("x", key.portalPos().getX());
            linkTag.putInt("y", key.portalPos().getY());
            linkTag.putInt("z", key.portalPos().getZ());
            linkTag.putBoolean("return_origin", link.returnOrigin());

            if (link.targetDimensionId() != null) {
                linkTag.putString("target_dimension", link.targetDimensionId().toString());
            }

            linkTag.putInt("target_center_x", link.targetCenterX());
            linkTag.putInt("target_center_z", link.targetCenterZ());
            linksTag.add(linkTag);
        }

        tag.put("links", linksTag);

        ListTag returnTag = new ListTag();
        for (Map.Entry<UUID, PortalLinkHandler.ReturnPoint> entry : returnPoints.entrySet()) {
            CompoundTag pointTag = new CompoundTag();
            PortalLinkHandler.ReturnPoint point = entry.getValue();

            pointTag.putUUID("player_id", entry.getKey());
            pointTag.putString("dimension", point.dimensionId().toString());
            pointTag.putDouble("x", point.x());
            pointTag.putDouble("y", point.y());
            pointTag.putDouble("z", point.z());
            pointTag.putFloat("y_rot", point.yRot());
            pointTag.putFloat("x_rot", point.xRot());
            returnTag.add(pointTag);
        }

        tag.put("return_points", returnTag);

        return tag;
    }
}
