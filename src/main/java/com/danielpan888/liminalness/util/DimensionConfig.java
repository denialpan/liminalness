package com.danielpan888.liminalness.util;

import java.util.List;

public record DimensionConfig(
    String dimension,
    int generationY,
    int minY,
    int maxY,
    int generationRadiusHorizontal,
    int generationRadiusVertical,
    int stepsPerTick,
    List<SchematicEntry> schematics
) {
    public record SchematicEntry(
        String path,
        int weight,
        SchematicLoader.Schematic schematic  // populated after loading
    ) {}
}