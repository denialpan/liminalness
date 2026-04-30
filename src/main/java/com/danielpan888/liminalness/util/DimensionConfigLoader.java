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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DimensionConfigLoader {

    private record SchematicSettings(
        int weight,
        boolean canConnectItself
    ) {}

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
        int defaultWeight    = json.has("default_weight") ? json.get("default_weight").getAsInt() : 1;
        boolean defaultCanConnectItself = !json.has("default_can_connect_itself") || json.get("default_can_connect_itself").getAsBoolean();
        String defaultSchematicsDir = json.has("default_schematics_dir") ? json.get("default_schematics_dir").getAsString() : "schematics";

        JsonArray schematicsJson = json.has("schematics") ? json.getAsJsonArray("schematics") : new JsonArray();
        Map<String, SchematicSettings> schematicSettings = discoverSchematics(
            defaultSchematicsDir,
            defaultNamespace,
            resourceManager,
            defaultWeight,
            defaultCanConnectItself
        );

        for (var elem : schematicsJson) {
            JsonObject obj = elem.getAsJsonObject();
            String path = obj.has("path") ? obj.get("path").getAsString() : resolveSchematicPath(obj.get("name").getAsString(), defaultSchematicsDir);
            int weight = obj.has("weight") ? obj.get("weight").getAsInt() : defaultWeight;
            boolean canConnectItself = !obj.has("can_connect_itself") || obj.get("can_connect_itself").getAsBoolean();
            schematicSettings.put(path, new SchematicSettings(weight, canConnectItself));
        }

        List<DimensionConfig.SchematicEntry> entries = new ArrayList<>();

        for (var entry : schematicSettings.entrySet()) {
            String path = entry.getKey();
            SchematicSettings settings = entry.getValue();
            try (InputStream schematicStream = openSchematic(path, defaultNamespace, resourceManager)) {
                if (schematicStream == null) {
                    liminalness.LOGGER.error("dimension config - schematic not found: {} referenced by {}", path, sourceName);
                    continue;
                }

                SchematicLoader.Schematic schematic = SchematicLoader.load(schematicStream);
                entries.add(new DimensionConfig.SchematicEntry(path, settings.weight(), settings.canConnectItself(), schematic));
                liminalness.LOGGER.info("dimension config - loaded schematic: {} weight={} can_connect_itself={}", path, settings.weight(), settings.canConnectItself());
            } catch (Exception e) {
                liminalness.LOGGER.error("dimension config - failed to load schematic: {} referenced by {}: {}", path, sourceName, e.toString());
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

    private static String resolveSchematicPath(String value, String defaultSchematicsDir) {
        String prefix = defaultSchematicsDir.endsWith("/") ? defaultSchematicsDir : defaultSchematicsDir + "/";
        return prefix + value;
    }

    private static Map<String, SchematicSettings> discoverSchematics(
        String defaultSchematicsDir,
        String defaultNamespace,
        ResourceManager resourceManager,
        int defaultWeight,
        boolean defaultCanConnectItself
    ) {
        Map<String, SchematicSettings> discovered = new LinkedHashMap<>();
        if (resourceManager == null) {
            return discovered;
        }

        String prefix = defaultSchematicsDir.endsWith("/") ? defaultSchematicsDir : defaultSchematicsDir + "/";
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(
            defaultSchematicsDir,
            id -> id.getNamespace().equals(defaultNamespace) && id.getPath().endsWith(".schem")
        );

        resources.keySet().stream()
            .map(ResourceLocation::getPath)
            .sorted()
            .forEach(path -> discovered.put(
                path.startsWith(prefix) ? path : prefix + path,
                new SchematicSettings(defaultWeight, defaultCanConnectItself)
            ));

        return discovered;
    }
}
