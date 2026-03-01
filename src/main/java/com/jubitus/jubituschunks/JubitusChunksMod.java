package com.jubitus.jubituschunks;


import com.jubitus.jubituschunks.commands.CommandJubitusChunks;
import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.net.NetworkHandler;
import com.jubitus.jubituschunks.pregen.PregenManager;
import com.jubitus.jubituschunks.pregen.PregenTickHandler;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION)
public class JubitusChunksMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Hello From {}!", Tags.MOD_NAME);
        MinecraftForge.EVENT_BUS.register(new PregenTickHandler());
        NetworkHandler.init();
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        // Register /pregenMill command
        event.registerServerCommand(new CommandJubitusChunks());
    }

    @Mod.EventHandler
    public void onServerStarted(net.minecraftforge.fml.common.event.FMLServerStartedEvent event) {


        if (!JubitusChunksConfig.GENERAL.autoResumePregenOnStartup) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // Resume for each loaded world/dimension that has state
        for (WorldServer world : server.worlds) {
            if (world == null) continue;

            PregenState st = PregenState.load(world);
            if (st == null) continue;




            // Safety: avoid resuming if a task is already running
            if (PregenManager.getTask(world.provider.getDimension()) != null) continue;

            // Resume
            BlockPos start = new BlockPos(st.centerChunkX << 4, 64, st.centerChunkZ << 4);
            int radiusBlocks = st.chunkRadius * 16;

            JubitusChunksMod.LOGGER.info(
                    "Auto-resuming pregen for dim {} at ({}, {}) r={} chunks (REWALK from step 0 to repair/avoid holes)",
                    st.dim, st.centerChunkX, st.centerChunkZ, st.chunkRadius
            );


            boolean ok = PregenManager.startResumable(
                    server,
                    world,
                    start,
                    radiusBlocks,
                    null,
                    st.skipExisting,
                    st.verifyExisting,
                    st.populateViaGenerator,
                    st.spiralSteps
            );




            if (!ok) {
                JubitusChunksMod.LOGGER.warn("Auto-resume failed for dim {} (task already running?)", st.dim);
            }
        }
    }
    @Mod.EventHandler
    public void onServerStopping(net.minecraftforge.fml.common.event.FMLServerStoppingEvent event) {
        // Flush all running pregenerators before shutdown
        PregenManager.pauseAllAndFlush();
    }


}
