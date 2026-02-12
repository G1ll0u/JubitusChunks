package com.jubitus.jubituschunks.pregen;

import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PregenManager {

    private static final Map<Integer, PregenTask> TASKS = new ConcurrentHashMap<>();

    public static boolean start(MinecraftServer server, WorldServer world, BlockPos start, int radiusBlocks,
                                EntityPlayerMP initiator, boolean skipExisting) {

        int dim = world.provider.getDimension();
        if (TASKS.containsKey(dim)) return false;

        boolean verifyExisting = JubitusChunksConfig.GENERAL.verifyExistingChunks;
        if (skipExisting) verifyExisting = true;
        boolean populateViaGenerator = JubitusChunksConfig.GENERAL.populateViaGenerator;

        PregenTask task = new PregenTask(server, world, start, radiusBlocks, initiator,
                skipExisting, verifyExisting, populateViaGenerator);

        TASKS.put(dim, task);
        return true;
    }


    public static boolean stop(int dimension) {
        PregenTask t = TASKS.remove(dimension);
        if (t == null) return false;

        try {
            t.requestStopAndFlush();
        } catch (Throwable ignored) {}

        return true;
    }
    public static void stopAllAndFlush() {
        for (Integer dim : new java.util.ArrayList<>(TASKS.keySet())) {
            stop(dim); // calls requestStopAndFlush()
        }
    }


    public static void tick() {
        for (Map.Entry<Integer, PregenTask> e : TASKS.entrySet()) {
            PregenTask task = e.getValue();
            boolean done = task.tickStep();
            if (done) {
                TASKS.remove(e.getKey());
            }
        }
    }
    public static boolean startResumable(MinecraftServer server, WorldServer world, BlockPos start, int radiusBlocks,
                                         EntityPlayerMP initiator,
                                         boolean skipExisting,
                                         boolean verifyExisting,
                                         boolean populateViaGenerator,
                                         long stepIndex) {

        if (skipExisting) verifyExisting = true;

        int dim = world.provider.getDimension();
        if (TASKS.containsKey(dim)) return false;

        PregenTask task = new PregenTask(server, world, start, radiusBlocks, initiator,
                skipExisting, verifyExisting, populateViaGenerator);

        task.setSpiralStep(stepIndex);
        TASKS.put(dim, task);
        return true;
    }
    @Nullable
    public static PregenTask getTask(int dim) {
        PregenTask t = TASKS.get(dim);
        return t;
    }


}
