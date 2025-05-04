package shimo_first_mod_chunkloader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.ChatFormatting;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

@Mod.EventBusSubscriber(modid = ChunkLoaderMod.MODID)
public class ChunkHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Set<Entity> LOADERS = new HashSet<>();
    private static final Map<Entity, Set<ChunkPos>> CHUNKS = new HashMap<>();
    private static final String CHUNKLOADER_TAG = "chunkloadermod:chunkloader";
    private static MinecraftServer server;
    private static int scanCounter = 0;
    private static final int SCAN_INTERVAL = 1200; // 60秒（20ティック/秒 × 60）
    private static final int BATCH_SIZE = 10; // 1フレームにロードするチャンク数

    private static void addChunkLoaderTag(Entity entity) {
        CompoundTag tag = entity.getPersistentData();
        tag.putBoolean(CHUNKLOADER_TAG, true);
        entity.setPos(entity.position());
    }

    private static void removeChunkLoaderTag(Entity entity) {
        CompoundTag tag = entity.getPersistentData();
        tag.remove(CHUNKLOADER_TAG);
        entity.setPos(entity.position());
    }

    private static boolean hasChunkLoaderTag(Entity entity) {
        return entity.getPersistentData().getBoolean(CHUNKLOADER_TAG);
    }

    private static void scanChunkLoaders() {
        if (server == null) {
            LOGGER.warn("Server is null in scanChunkLoaders");
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            level.getEntities().getAll().forEach(entity -> {
                if (hasChunkLoaderTag(entity) && entity.isAlive() && !entity.isRemoved() && !LOADERS.contains(entity)) {
                    LOADERS.add(entity);
                    ChunkLoaderSavedData data = ChunkLoaderSavedData.get(level);
                    ChunkLoaderSavedData.LoaderData loader = data.getLoaders().get(entity.getUUID());
                    int range = loader != null ? loader.range : ChunkLoaderConfig.getChunkLoadRange();
                    CHUNKS.computeIfAbsent(entity, k -> new HashSet<>(range * range));
                }
            });
        }
    }

    private static void loadChunksForge(ServerLevel level, Entity entity, Set<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            ForgeChunkManager.forceChunk(level, ChunkLoaderMod.MODID, entity.getUUID(), chunk.x, chunk.z, true, true);
        }
    }

    private static void unloadChunksForge(ServerLevel level, Entity entity, Set<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            ForgeChunkManager.forceChunk(level, ChunkLoaderMod.MODID, entity.getUUID(), chunk.x, chunk.z, false, true);
        }
    }

    private static void loadSavedChunks(ServerLevel level) {
        ChunkLoaderSavedData data = ChunkLoaderSavedData.get(level);
        List<Map.Entry<UUID, ChunkLoaderSavedData.LoaderData>> entries = new ArrayList<>(data.getLoaders().entrySet());
        int index = 0;

        while (index < entries.size()) {
            Map.Entry<UUID, ChunkLoaderSavedData.LoaderData> entry = entries.get(index);
            int range = entry.getValue().range;
            // ゼロ除算防止: range が 0 以下の場合、デフォルト値を使用し警告ログ
            if (range <= 0) {
                LOGGER.warn("Invalid range {} for chunk loader UUID {} in dimension {}. Using default range {}.",
                        range, entry.getKey(), entry.getValue().dimension.location(), ChunkLoaderConfig.getChunkLoadRange());
                range = ChunkLoaderConfig.getChunkLoadRange();
                data.removeLoader(entry.getKey());
                index++;
                continue;
            }
            int endIndex = Math.min(index + BATCH_SIZE / (range * range), entries.size());
            for (int i = index; i < endIndex; i++) {
                entry = entries.get(i);
                UUID uuid = entry.getKey();
                ResourceKey<Level> dimension = entry.getValue().dimension;
                Set<ChunkPos> chunks = entry.getValue().chunks;
                if (dimension.equals(level.dimension())) {
                    Entity entity = level.getEntity(uuid);
                    if (entity != null && entity.isAlive() && !entity.isRemoved() && hasChunkLoaderTag(entity)) {
                        LOADERS.add(entity);
                        CHUNKS.put(entity, new HashSet<>(chunks));
                        loadChunksForge(level, entity, chunks);
                    } else {
                        data.removeLoader(uuid);
                    }
                }
            }
            index = endIndex;
            if (index < entries.size()) {
                break;
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        if (server == null) {
            LOGGER.error("Server is null in onServerStarted");
            return;
        }
        LOADERS.clear();
        CHUNKS.clear();
        scanChunkLoaders();
        for (ServerLevel level : server.getAllLevels()) {
            loadSavedChunks(level);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            loadSavedChunks(level);
            scanChunkLoaders();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (server == null) return;

        for (ServerLevel level : server.getAllLevels()) {
            ChunkLoaderSavedData data = ChunkLoaderSavedData.get(level);
            for (Entity entity : LOADERS) {
                if (entity.level() == level && entity.isAlive() && !entity.isRemoved() && hasChunkLoaderTag(entity)) {
                    Set<ChunkPos> chunks = CHUNKS.getOrDefault(entity, new HashSet<>());
                    int range = data.getLoaders().getOrDefault(entity.getUUID(), new ChunkLoaderSavedData.LoaderData(level.dimension(), chunks, ChunkLoaderConfig.getChunkLoadRange())).range;
                    data.addLoader(entity.getUUID(), level, chunks, range);
                } else {
                    data.removeLoader(entity.getUUID());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onWorldSave(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level) || server == null) return;

        for (Entity entity : LOADERS) {
            if (entity.level() == level && entity.isAlive() && !entity.isRemoved()) {
                CompoundTag tag = entity.getPersistentData();
                if (!tag.getBoolean(CHUNKLOADER_TAG)) {
                    tag.putBoolean(CHUNKLOADER_TAG, true);
                    entity.setPos(entity.position());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        level.getEntities().getAll().forEach(entity -> {
            if (hasChunkLoaderTag(entity) && entity.isAlive() && !entity.isRemoved() && !LOADERS.contains(entity)) {
                LOADERS.add(entity);
                ChunkLoaderSavedData data = ChunkLoaderSavedData.get(level);
                ChunkLoaderSavedData.LoaderData loader = data.getLoaders().get(entity.getUUID());
                int range = loader != null ? loader.range : ChunkLoaderConfig.getChunkLoadRange();
                CHUNKS.computeIfAbsent(entity, k -> new HashSet<>(range * range));
                int offset = range / 2;
                ChunkPos center = new ChunkPos(entity.blockPosition());
                Set<ChunkPos> chunks = new HashSet<>(range * range);
                for (int dx = -offset; dx <= offset; dx++) {
                    for (int dz = -offset; dz <= offset; dz++) {
                        chunks.add(new ChunkPos(center.x + dx, center.z + dz));
                    }
                }
                loadChunksForge(level, entity, chunks);
                CHUNKS.put(entity, chunks);
                data.addLoader(entity.getUUID(), level, chunks, range);
            }
        });
    }

    @SubscribeEvent
    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("setchunkloader")
                        .requires(p -> p.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> setChunkLoader(context.getSource(), EntityArgument.getEntities(context, "targets"), ChunkLoaderConfig.getChunkLoadRange()))
                                .then(Commands.argument("range", IntegerArgumentType.integer(3))
                                        .executes(context -> {
                                            int inputRange = IntegerArgumentType.getInteger(context, "range");
                                            int maxRange = ChunkLoaderConfig.getMaxChunkLoadRange();
                                            if (inputRange > maxRange) {
                                                context.getSource().sendFailure(Component.translatable("commands.chunkloadermod.set.failure.range_exceeded", maxRange));
                                                return 0;
                                            }
                                            int range = ChunkLoaderConfig.validateRange(inputRange);
                                            return setChunkLoader(context.getSource(), EntityArgument.getEntities(context, "targets"), range);
                                        })))
        );

        dispatcher.register(
                Commands.literal("removechunkloader")
                        .requires(p -> p.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    Collection<? extends Entity> entities;
                                    try {
                                        entities = EntityArgument.getEntities(context, "targets");
                                    } catch (CommandSyntaxException e) {
                                        source.sendFailure(Component.translatable("commands.chunkloadermod.remove.failure.syntax_error", e.getMessage()));
                                        return 0;
                                    }
                                    int removed = 0;
                                    for (Entity entity : entities) {
                                        if (LOADERS.remove(entity)) {
                                            Set<ChunkPos> oldChunks = CHUNKS.remove(entity);
                                            if (oldChunks != null && entity.level() instanceof ServerLevel level) {
                                                ServerChunkCache chunkSource = level.getChunkSource();
                                                for (ChunkPos chunk : oldChunks) {
                                                    try {
                                                        chunkSource.updateChunkForced(chunk, false);
                                                    } catch (Exception ignored) {
                                                    }
                                                }
                                                unloadChunksForge(level, entity, oldChunks);
                                                ChunkLoaderSavedData.get(level).removeLoader(entity.getUUID());
                                            }
                                            removeChunkLoaderTag(entity);
                                            removed++;
                                        }
                                    }
                                    final int finalRemoved = removed; // final 変数にコピー
                                    source.sendSuccess(() -> Component.translatable("commands.chunkloadermod.remove.success", finalRemoved), true);
                                    return removed > 0 ? 1 : 0;
                                }))
        );

        dispatcher.register(
                Commands.literal("listchunkloaders")
                        .requires(p -> p.hasPermission(2))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            if (server == null) {
                                source.sendFailure(Component.translatable("commands.chunkloadermod.server_unavailable"));
                                return 0;
                            }

                            int count = 0;
                            for (ServerLevel level : server.getAllLevels()) {
                                ChunkLoaderSavedData data = ChunkLoaderSavedData.get(level);
                                for (Map.Entry<UUID, ChunkLoaderSavedData.LoaderData> entry : data.getLoaders().entrySet()) {
                                    UUID uuid = entry.getKey();
                                    int range = entry.getValue().range;
                                    Entity entity = level.getEntity(uuid);
                                    if (entity != null && LOADERS.contains(entity)) {
                                        String entityType = entity.getType().getDescriptionId().replace("entity.minecraft.", "");
                                        CompoundTag tags = entity.getPersistentData();
                                        List<String> tagList = new ArrayList<>();
                                        if (tags.contains("Tags", Tag.TAG_LIST)) {
                                            ListTag tagListNbt = tags.getList("Tags", Tag.TAG_STRING);
                                            for (Tag tag : tagListNbt) {
                                                tagList.add(tag.getAsString());
                                            }
                                        }
                                        String teamName = entity.getTeam() != null ? entity.getTeam().getName() : "None";
                                        double x = entity.getX();
                                        double y = entity.getY();
                                        double z = entity.getZ();
                                        // エンティティ種類を黄色で表示
                                        MutableComponent fullMessage = Component.literal("")
                                                .append(Component.literal(entityType)
                                                        .withStyle(Style.EMPTY.withColor(TextColor.fromLegacyFormat(ChatFormatting.YELLOW))))
                                                .append(Component.literal(": ")
                                                        .withStyle(Style.EMPTY.withColor(TextColor.fromLegacyFormat(ChatFormatting.WHITE))))
                                                .append(Component.literal(
                                                                String.format("Tags=%s, Team=%s, Range=%dx%d, Pos=[x=%.1f, y=%.1f, z=%.1f]",
                                                                        tagList, teamName, range, range, x, y, z))
                                                        .withStyle(Style.EMPTY.withColor(TextColor.fromLegacyFormat(ChatFormatting.WHITE))));
                                        source.sendSuccess(() -> fullMessage, false);
                                        count++;
                                    }
                                }
                            }
                            final int finalCount = count; // final 変数にコピー
                            if (count == 0) {
                                source.sendFailure(Component.translatable("commands.chunkloadermod.list.none"));
                                return 0;
                            }
                            source.sendSuccess(() -> Component.translatable("commands.chunkloadermod.list.total", finalCount), false);
                            return 1;
                        })
        );
    }

    private static int setChunkLoader(CommandSourceStack source, Collection<? extends Entity> entities, int range) {
        if (entities.isEmpty()) {
            source.sendFailure(Component.translatable("commands.chunkloadermod.set.failure.no_entities"));
            return 0;
        }
        int added = 0;
        for (Entity entity : entities) {
            if (LOADERS.add(entity)) {
                CHUNKS.put(entity, new HashSet<>(range * range));
                addChunkLoaderTag(entity);
                if (entity.level() instanceof ServerLevel level) {
                    ChunkPos center = new ChunkPos(entity.blockPosition());
                    Set<ChunkPos> chunks = new HashSet<>(range * range);
                    int offset = range / 2;
                    for (int dx = -offset; dx <= offset; dx++) {
                        for (int dz = -offset; dz <= offset; dz++) {
                            chunks.add(new ChunkPos(center.x + dx, center.z + dz));
                        }
                    }
                    loadChunksForge(level, entity, chunks);
                    CHUNKS.put(entity, chunks);
                    ChunkLoaderSavedData.get(level).addLoader(entity.getUUID(), level, chunks, range);
                }
                added++;
            }
        }
        final int finalAdded = added; // final 変数にコピー
        final int finalRange = range; // final 変数にコピー
        source.sendSuccess(() -> Component.translatable("commands.chunkloadermod.set.success", finalAdded, finalRange, finalRange), true);
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (server != null && ++scanCounter >= SCAN_INTERVAL) {
            scanChunkLoaders();
            scanCounter = 0;
        }

        Iterator<Entity> iterator = LOADERS.iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (!(entity.level() instanceof ServerLevel level)) {
                continue;
            }

            if (!entity.isAlive() || entity.isRemoved()) {
                iterator.remove();
                Set<ChunkPos> oldChunks = CHUNKS.remove(entity);
                if (oldChunks != null) {
                    ServerChunkCache chunkSource = level.getChunkSource();
                    for (ChunkPos chunk : oldChunks) {
                        try {
                            chunkSource.updateChunkForced(chunk, false);
                        } catch (Exception ignored) {
                        }
                    }
                    unloadChunksForge(level, entity, oldChunks);
                    ChunkLoaderSavedData.get(level).removeLoader(entity.getUUID());
                }
                removeChunkLoaderTag(entity);
                continue;
            }

            ChunkLoaderSavedData data = ChunkLoaderSavedData.get(level);
            ChunkLoaderSavedData.LoaderData loader = data.getLoaders().get(entity.getUUID());
            int range = loader != null ? loader.range : ChunkLoaderConfig.getChunkLoadRange();

            ChunkPos center = new ChunkPos(entity.blockPosition());
            Set<ChunkPos> newChunks = new HashSet<>(range * range);
            int offset = range / 2;
            for (int dx = -offset; dx <= offset; dx++) {
                for (int dz = -offset; dz <= offset; dz++) {
                    newChunks.add(new ChunkPos(center.x + dx, center.z + dz));
                }
            }

            Set<ChunkPos> oldChunks = CHUNKS.getOrDefault(entity, new HashSet<>());
            ServerChunkCache chunkSource = level.getChunkSource();
            for (ChunkPos chunk : oldChunks) {
                if (!newChunks.contains(chunk)) {
                    try {
                        chunkSource.updateChunkForced(chunk, false);
                    } catch (Exception ignored) {
                    }
                    ForgeChunkManager.forceChunk(level, ChunkLoaderMod.MODID, entity.getUUID(), chunk.x, chunk.z, false, true);
                }
            }

            for (ChunkPos chunk : newChunks) {
                try {
                    chunkSource.updateChunkForced(chunk, true);
                } catch (Exception ignored) {
                }
                ForgeChunkManager.forceChunk(level, ChunkLoaderMod.MODID, entity.getUUID(), chunk.x, chunk.z, true, true);
            }

            data.removeLoader(entity.getUUID());
            data.addLoader(entity.getUUID(), level, newChunks, range);
            CHUNKS.put(entity, newChunks);
        }
    }
}