package shimo_first_mod_chunkloader;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class ChunkLoaderConfig {
    public static final ForgeConfigSpec CONFIG_SPEC;
    public static final ForgeConfigSpec.IntValue CHUNK_LOAD_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_CHUNK_LOAD_RANGE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Chunk Loader Mod Configuration");
        builder.push("general");

        MAX_CHUNK_LOAD_RANGE = builder
                .comment("Maximum chunk loading range (odd numbers, 3-255).",
                        "Applies to both config default range and per-entity ranges set by commands.",
                        "Even numbers are rounded up (e.g., 8 -> 9).")
                .defineInRange("max_chunk_load_range", 9, 3, 255);

        CHUNK_LOAD_RANGE = builder
                .comment("Default chunk loading range (odd numbers, 3 to max_chunk_load_range).",
                        "Used when no range is specified in /setchunkloader.",
                        "Even numbers are rounded up (e.g., 8 -> 9).")
                .defineInRange("chunk_load_range", 3, 3, 255);

        builder.pop();
        CONFIG_SPEC = builder.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC, "chunkloaderentityconfig.toml");
    }

    public static int getChunkLoadRange() {
        int maxRange = getMaxChunkLoadRange();
        int range = Math.min(CHUNK_LOAD_RANGE.get(), maxRange);
        return range % 2 == 0 ? range + 1 : range;
    }

    public static int getMaxChunkLoadRange() {
        int maxRange = MAX_CHUNK_LOAD_RANGE.get();
        return maxRange % 2 == 0 ? maxRange + 1 : maxRange;
    }

    public static int validateRange(int range) {
        int maxRange = getMaxChunkLoadRange();
        range = Math.max(3, Math.min(range, maxRange));
        return range % 2 == 0 ? range + 1 : range;
    }
}