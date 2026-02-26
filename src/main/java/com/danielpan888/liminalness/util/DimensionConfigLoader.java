package com.danielpan888.liminalness.util;

import com.danielpan888.liminalness.liminalness;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class DimensionConfigLoader {

    public static DimensionConfig load(String configPath) throws Exception {
        InputStream stream = liminalness.class.getResourceAsStream(configPath);
        if (stream == null) throw new Exception("dimension config - config not found: " + configPath);

        JsonObject json = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();

        String dimension     = json.get("dimension").getAsString();
        int generationY      = json.get("generation_y").getAsInt();
        int minY             = json.get("min_y").getAsInt();
        int maxY             = json.get("max_y").getAsInt();
        int radiusHorizontal = json.get("generation_radius_horizontal").getAsInt();
        int radiusVertical   = json.get("generation_radius_vertical").getAsInt();
        int stepsPerTick     = json.get("steps_per_tick").getAsInt();

        JsonArray schematicsJson = json.getAsJsonArray("schematics");
        List<DimensionConfig.SchematicEntry> entries = new ArrayList<>();

        for (var elem : schematicsJson) {
            JsonObject obj = elem.getAsJsonObject();
            String path    = obj.get("path").getAsString();
            int weight     = obj.get("weight").getAsInt();

            String fullPath = "/data/liminalness/" + path;
            InputStream schematicStream = liminalness.class.getResourceAsStream(fullPath);

            if (schematicStream == null) {
                liminalness.LOGGER.error("dimension config - schematic not found: {}", fullPath);
                continue;
            }

            SchematicLoader.Schematic schematic = SchematicLoader.load(schematicStream);
            entries.add(new DimensionConfig.SchematicEntry(path, weight, schematic));
            liminalness.LOGGER.info("dimension config - loaded schematic: {} weight={}", path, weight);
        }

        return new DimensionConfig(
            dimension, generationY, minY, maxY,
            radiusHorizontal, radiusVertical, stepsPerTick,
            entries
        );
    }
}