package shimo_first_mod_chunkloader;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ChunkLoaderMod.MODID)
public class ChunkLoaderMod {
    public static final String MODID = "chunkloadermod";

    public ChunkLoaderMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(new ChunkHandler());
        ChunkLoaderConfig.register(); // コンフィグ登録
    }

    private void setup(final FMLCommonSetupEvent event) {
    }
}