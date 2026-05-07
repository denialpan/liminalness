package com.danielpan888.liminalness;

import com.danielpan888.liminalness.dimension.DimensionManager;
import com.danielpan888.liminalness.dimension.FrontierChunkGenerator;
import com.danielpan888.liminalness.dimension.RegisterChunkGenerator;
import com.danielpan888.liminalness.dimension.bedlinkage.BedLinkDestination;
import com.danielpan888.liminalness.dimension.bedlinkage.BedLinkHandler;
import com.danielpan888.liminalness.dimension.portallinkage.PortalLinkHandler;
import com.danielpan888.liminalness.util.SchematicLoader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.*;
import java.util.concurrent.CompletableFuture;

// TODO: organize schematic marker and portals?
// TODO: fix reenter dimension fallback 0 128 0

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(liminalness.MODID)
public class liminalness {

    public static final String MODID = "liminalness";
    public static final Logger LOGGER = LogUtils.getLogger();

    public liminalness(IEventBus modEventBus, ModContainer modContainer) {
//        modEventBus.addListener(this::commonSetup);
        RegisterChunkGenerator.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

//    private void commonSetup(FMLCommonSetupEvent event) {
//        DimensionManager.register(ResourceLocation.parse("liminalness:dim_backrooms"));
//        LOGGER.info("Dimensions registered");
//    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        DimensionManager.loadConfigs(event.getServer(), seed);
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        DimensionManager.onLevelLoad(level);
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        DimensionManager.onLevelUnload(level);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        DimensionManager.onServerStop(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        DimensionManager.onServerTick(event.getServer());
        checkPortals(event.getServer());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerDebugCommand(event.getDispatcher());
    }

    // intercept server to update client chunks, prevents client race condition
    @SubscribeEvent
    public void onChunkWatch(ChunkWatchEvent.Watch event) {

        ResourceLocation dimId = event.getLevel().dimension().location();
        FrontierChunkGenerator gen = (FrontierChunkGenerator) DimensionManager.getInstance(dimId);
        if (gen == null || gen.serverLevel == null) return;

        ChunkPos chunkPosition = event.getPos();
        long key = FrontierChunkGenerator.chunkKey(chunkPosition.x, chunkPosition.z);

        if (gen.committedChunks.contains(key)) return;

        int minX = chunkPosition.getMinBlockX(), maxX = minX + 16;
        int minZ = chunkPosition.getMinBlockZ(), maxZ = minZ + 16;

        boolean[] allResolved = {true};

        gen.spatialIndex.anyRoomInChunk(minX, maxX, minZ, maxZ, origin -> {
            SchematicLoader.Schematic schematic = gen.roomOrigins.get(origin);
            if (schematic == null) {
                allResolved[0] = false;
                return false;
            }

            int[] e = gen.getExtents(schematic);

            if (origin.getX() + e[0] <= minX || origin.getX() >= maxX) return false;
            if (origin.getZ() + e[2] <= minZ || origin.getZ() >= maxZ) return false;

            for (var block : schematic.finalBlocks().entrySet()) {
                BlockPos world = origin.offset(block.getKey());
                if (world.getX() < minX || world.getX() >= maxX) continue;
                if (world.getZ() < minZ || world.getZ() >= maxZ) continue;

                BlockState target = block.getValue();
                gen.serverLevel.setBlock(world, target, Block.UPDATE_CLIENTS);
            }
            return false;
        });

        if (allResolved[0]) {
            gen.committedChunks.add(key);
        } else {
            gen.markChunkStale(key);
        }

    }

    // enter dimension
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = event.getEntity().getServer();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof BedBlock)) {
            return;
        }

        Optional<BedLinkDestination> destination = BedLinkHandler.resolveDestination(player, pos);
        if (destination.isEmpty()) {
            return;
        }

        BedLinkDestination link = destination.get();
        Vec3 spawnPos = link.spawnPos();
        ServerLevel targetLevel = link.level();

        PortalLinkHandler.rememberReturnPoint(player, level, pos);

        player.teleportTo(
                targetLevel,
                spawnPos.x,
                spawnPos.y,
                spawnPos.z,
                player.getYRot(),
                player.getXRot()
        );

        server.execute(() -> {
            player.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), player.getXRot());
            player.connection.teleport(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), player.getXRot());
        });
    }

    private void checkPortals(MinecraftServer server) {

        for (var entry : DimensionManager.getInstances().entrySet()) {
            ResourceLocation dimId = entry.getKey();
            FrontierChunkGenerator gen = (FrontierChunkGenerator) entry.getValue();

            if (gen.serverLevel == null) continue;

            List<ServerPlayer> players = new ArrayList<>(gen.serverLevel.players());

            sendPortalParticles(gen.serverLevel, players, gen.portalPositions, new Vector3f(0.27f, 0.0f, 0.58f));
            sendPortalParticles(gen.serverLevel, players, gen.structurePortalPositions, new Vector3f(0.45f, 0.24f, 0.10f));
            sendPortalParticles(gen.serverLevel, players, gen.jigsawPortalPositions, new Vector3f(0.10f, 0.65f, 0.20f));


            for (ServerPlayer player : players) {
                BlockPos feet = player.blockPosition();
                if (gen.portalPositions.contains(feet)) {
                    handlePortalTrigger(player, dimId, feet, false);
                } else if (gen.structurePortalPositions.contains(feet) || gen.jigsawPortalPositions.contains(feet)) {
                    handlePortalTrigger(player, dimId, feet, true);
                }
            }
        }
    }

    private void sendPortalParticles(ServerLevel level, List<ServerPlayer> players, Set<BlockPos> portals, Vector3f startColor) {
        if (portals.isEmpty()) {
            return;
        }

        ParticleOptions portalParticle = new DustColorTransitionOptions(
            startColor,
            new Vector3f(0.0f, 0.0f, 0.0f),
            1.5f
        );

        for (BlockPos portalPos : portals) {
            for (ServerPlayer player : players) {
                if (player.blockPosition().distSqr(portalPos) > 16 * 16) continue;

                level.sendParticles(
                    portalParticle,
                    portalPos.getX() + 0.5,
                    portalPos.getY() + 0.5,
                    portalPos.getZ() + 0.5,
                    4,
                    0.3,
                    0.3,
                    0.3,
                    0.0
                );
            }
        }
    }

    private void handlePortalTrigger(ServerPlayer player, ResourceLocation fromDim, BlockPos portalPos, boolean directReturn) {
        Optional<PortalLinkHandler.PortalTeleportTarget> destination = directReturn
                ? PortalLinkHandler.resolveDirectReturn(player)
                : PortalLinkHandler.resolveDestination(player, fromDim, portalPos);
        if (destination.isEmpty()) {
            return;
        }

        PortalLinkHandler.PortalTeleportTarget target = destination.get();
        Vec3 position = target.position();
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        player.teleportTo(target.level(), position.x, position.y, position.z, target.yRot(), target.xRot());
        server.execute(() -> player.connection.teleport(position.x, position.y, position.z, target.yRot(), target.xRot()));
    }

    private void registerDebugCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal(MODID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                    .then(Commands.argument("dimension", ResourceLocationArgument.id())
                        .suggests(this::suggestManagedDimensions)
                        .executes(context -> executeDebugTeleport(
                            context.getSource(),
                            ResourceLocationArgument.getId(context, "dimension")
                        ))
                    )
                )
        );
    }

    private CompletableFuture<Suggestions> suggestManagedDimensions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(DimensionManager.getRegisteredIds(), builder);
    }

    private int executeDebugTeleport(CommandSourceStack source, ResourceLocation dimensionId) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("must be player to use this command"));
            return 0;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("server unavailable"));
            return 0;
        }

        ServerLevel targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (targetLevel == null) {
            source.sendFailure(Component.literal("dimension not found: " + dimensionId));
            return 0;
        }

        if (!DimensionManager.isRegistered(dimensionId)) {
            source.sendFailure(Component.literal("dimension is not managed by liminalness: " + dimensionId));
            return 0;
        }

        FrontierChunkGenerator generator = (FrontierChunkGenerator) DimensionManager.getInstance(dimensionId);
        if (generator == null) {
            if (!(targetLevel.getChunkSource().getGenerator() instanceof FrontierChunkGenerator frontier)) {
                source.sendFailure(Component.literal("dimension does not (yet) use the frontier chunk generator: " + dimensionId));
                return 0;
            }
            generator = frontier;
            generator.serverLevel = targetLevel;
            generator.dimensionId = dimensionId;
        }

        if (!generator.initialized) {
            source.sendFailure(Component.literal("generator is not initialized yet for some reason: " + dimensionId));
            return 0;
        }

        BlockPos playerPos = player.blockPosition();
        if (generator.roomOrigins.isEmpty()) {
            generator.seedAt(playerPos.getX(), playerPos.getZ());
        }

        Vec3 spawnPos = generator.ensureLinkedSpawn(playerPos.getX(), playerPos.getZ());
        player.teleportTo(targetLevel, spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), player.getXRot());
        server.execute(() -> player.connection.teleport(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), player.getXRot()));

        source.sendSuccess(() -> Component.literal("teleported to " + dimensionId + " at " + spawnPos), false);
        return 1;
    }

}
