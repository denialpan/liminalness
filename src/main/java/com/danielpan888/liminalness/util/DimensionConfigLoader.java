package com.danielpan888.liminalness.util;

import com.danielpan888.liminalness.liminalness;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DimensionConfigLoader {

    public static DimensionConfig load(String configPath) throws Exception {
        InputStream stream = liminalness.class.getResourceAsStream(configPath);
        if (stream == null) throw new Exception("dimension config - config not found: " + configPath);
        return load(stream, configPath, "liminalness", null);
    }

    public static DimensionConfig load(ResourceLocation configId, ResourceManager resourceManager) throws Exception {
        Optional<Resource> resource = resourceManager.getResource(configId);
        if (resource.isEmpty()) throw new Exception("dimension config - config not found: " + configId);
        try (InputStream stream = resource.get().open()) {
            return load(stream, configId.toString(), configId.getNamespace(), resourceManager);
        }
    }

    private static DimensionConfig load(InputStream stream, String sourceName, String defaultNamespace, ResourceManager resourceManager) throws Exception {
        JsonObject json = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();

        String dimension     = json.get("dimension").getAsString();
        int generationY      = json.get("generation_y").getAsInt();
        int minY             = json.get("min_y").getAsInt();
        int maxY             = json.get("max_y").getAsInt();
        String fillSpace     = json.has("fill_space") ? json.get("fill_space").getAsString() : "minecraft:smooth_sandstone";
        int radiusHorizontal = json.get("generation_radius_horizontal").getAsInt();
        int radiusVertical   = json.get("generation_radius_vertical").getAsInt();
        int stepsPerTick     = json.get("steps_per_tick").getAsInt();
        int minRooms         = json.has("min_rooms") ? json.get("min_rooms").getAsInt() : 100;

        JsonArray schematicsJson = json.getAsJsonArray("schematics");
        List<DimensionConfig.SchematicEntry> entries = new ArrayList<>();

        for (var elem : schematicsJson) {
            JsonObject obj = elem.getAsJsonObject();
            String path = obj.get("path").getAsString();
            int weight = obj.get("weight").getAsInt();

            try (InputStream schematicStream = openSchematic(path, defaultNamespace, resourceManager)) {
                if (schematicStream == null) {
                    liminalness.LOGGER.error("dimension config - schematic not found: {} referenced by {}", path, sourceName);
                    continue;
                }

                SchematicLoader.Schematic schematic = SchematicLoader.load(schematicStream);
                entries.add(new DimensionConfig.SchematicEntry(path, weight, schematic));
                liminalness.LOGGER.info("dimension config - loaded schematic: {} weight={}", path, weight);
            }
        }

        return new DimensionConfig(
                dimension, generationY, minY, maxY, fillSpace,
                radiusHorizontal, radiusVertical, stepsPerTick, minRooms, entries
        );
    }

    private static InputStream openSchematic(String path, String defaultNamespace, ResourceManager resourceManager) throws Exception {
        if (resourceManager == null) {
            String fullPath = "/data/" + defaultNamespace + "/" + path;
            return liminalness.class.getResourceAsStream(fullPath);
        }

        ResourceLocation schematicId = path.contains(":")
                ? ResourceLocation.parse(path)
                : ResourceLocation.fromNamespaceAndPath(defaultNamespace, path);

        Optional<Resource> resource = resourceManager.getResource(schematicId);
        return resource.map(r -> {
            try {
                return r.open();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);
    }
}
