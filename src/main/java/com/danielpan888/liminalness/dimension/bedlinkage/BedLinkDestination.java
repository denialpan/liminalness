package com.danielpan888.liminalness.dimension.bedlinkage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public record BedLinkDestination(
        ServerLevel level,
        Vec3 spawnPos
) {}
