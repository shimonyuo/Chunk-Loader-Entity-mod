package shimo_first_mod_chunkloader;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.core.registries.Registries;

import java.util.*;

public class ChunkLoaderSavedData extends SavedData {
    private static final String DATA_NAME = ChunkLoaderMod.MODID + "_chunkloaders";
    private final Map<UUID, LoaderData> loaders = new HashMap<>();

    static class LoaderData {
        final ResourceKey<Level> dimension;
        final Set<ChunkPos> chunks;
        final int range;

        LoaderData(ResourceKey<Level> dimension, Set<ChunkPos> chunks, int range) {
            this.dimension = dimension;
            this.chunks = chunks;
            this.range = range;
        }
    }

    public static ChunkLoaderSavedData load(CompoundTag tag) {
        ChunkLoaderSavedData data = new ChunkLoaderSavedData();
        ListTag loadersTag = tag.getList("Loaders", Tag.TAG_COMPOUND);
        for (Tag t : loadersTag) {
            CompoundTag loaderTag = (CompoundTag) t;
            UUID uuid = loaderTag.getUUID("UUID");
            String dimension = loaderTag.getString("Dimension");
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
            int range = loaderTag.getInt("Range");
            Set<ChunkPos> chunks = new HashSet<>();
            ListTag chunksTag = loaderTag.getList("Chunks", Tag.TAG_COMPOUND);
            for (Tag c : chunksTag) {
                CompoundTag chunkTag = (CompoundTag) c;
                chunks.add(new ChunkPos(chunkTag.getInt("X"), chunkTag.getInt("Z")));
            }
            data.loaders.put(uuid, new LoaderData(dimKey, chunks, range));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag loadersTag = new ListTag();
        for (Map.Entry<UUID, LoaderData> entry : loaders.entrySet()) {
            CompoundTag loaderTag = new CompoundTag();
            loaderTag.putUUID("UUID", entry.getKey());
            loaderTag.putString("Dimension", entry.getValue().dimension.location().toString());
            loaderTag.putInt("Range", entry.getValue().range);
            ListTag chunksTag = new ListTag();
            for (ChunkPos chunk : entry.getValue().chunks) {
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putInt("X", chunk.x);
                chunkTag.putInt("Z", chunk.z);
                chunksTag.add(chunkTag);
            }
            loaderTag.put("Chunks", chunksTag);
            loadersTag.add(loaderTag);
        }
        tag.put("Loaders", loadersTag);
        return tag;
    }

    public static ChunkLoaderSavedData get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(ChunkLoaderSavedData::load, ChunkLoaderSavedData::new, DATA_NAME);
    }

    public void addLoader(UUID uuid, ServerLevel level, Set<ChunkPos> chunks, int range) {
        loaders.put(uuid, new LoaderData(level.dimension(), new HashSet<>(chunks), range));
        setDirty();
    }

    public void removeLoader(UUID uuid) {
        loaders.remove(uuid);
        setDirty();
    }

    public Map<UUID, LoaderData> getLoaders() {
        return Collections.unmodifiableMap(loaders);
    }
}