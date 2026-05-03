package com.danielpan888.liminalness.util;

import java.util.List;
import java.util.Set;

public record DimensionConfig(
    String dimension,
    int generationY,
    int minY,
    int maxY,
    String fillSpace,
    int generationRadiusHorizontal,
    int generationRadiusVertical,
    int stepsPerTick,
    int minRooms,
    List<SchematicEntry> schematics
) {
    public record SchematicEntry(
        String path,
        int weight,
        boolean mirroredVariants,
        boolean canConnectItselfVertically,
        boolean canConnectItselfHorizontally,
        int weightPenalty,
        Set<Integer> levels,
        SchematicLoader.Schematic schematic  // populated after loading
    ) {}
}
