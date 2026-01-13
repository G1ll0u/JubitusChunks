package com.jubitus.jubituschunks.pregen;

import com.jubitus.jubituschunks.JubitusChunksMod;
import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.ArrayList;
import java.util.List;

public class PregenTask {

    // --- viewer/head tracking ---
    private volatile int headChunkX;
    private volatile int headChunkZ;
    private volatile int headRadiusChunks;
    private volatile long headStepIndex;

    private long processedSteps = 0;      // steps we actually attempted (after nextTodo)
    private long populatedChunks = 0;     // your current generatedChunks meaning
    private long loadedChunksTouched = 0; // optional: how many provideChunk calls for center/bulk

    // --- low memory watchdog ---
    private long lastMemCheckMs = 0;
    private boolean gcRequested = false;
    private long usedBeforeGc = 0;
    private int consecutiveGcFailures = 0;

    // --- speed tracking ---
    private long perfLastMs = System.currentTimeMillis();
    private long perfLastSteps = 0;
    private long perfLastGenerated = 0;
    private long perfLastSkipped = 0;

    private double emaStepsPerSec = 0.0;
    private double emaGenPerSec = 0.0;
    private double emaSkipPerSec = 0.0;

    private final boolean populateViaGenerator;
    private final MinecraftServer server;
    private final WorldServer world;
    private final EntityPlayerMP initiator;
    private long generatedChunks = 0;
    private long skippedExisting = 0;
    private final long totalSteps;
    private final boolean verifyExisting;

    private final int centerChunkX;
    private final int centerChunkZ;
    private final int chunkRadius;
    private final boolean skipExisting;

    private final int bulkRUsed;
    private final int strideChunks;


    private final SpiralChunkIterator iterator;


    private long lastProgressMsgMs = 0;

    public PregenTask(MinecraftServer server,
                      WorldServer world,
                      BlockPos start,
                      int radiusBlocks,
                      EntityPlayerMP initiator,
                      boolean skipExisting,
                      boolean verifyExisting,
                      boolean populateViaGenerator) {

        this.server = server;
        this.world = world;
        this.initiator = initiator;

        this.skipExisting = skipExisting;
        this.verifyExisting = verifyExisting;
        this.populateViaGenerator = populateViaGenerator;

        this.centerChunkX = start.getX() >> 4;
        this.centerChunkZ = start.getZ() >> 4;

        this.chunkRadius = (int) Math.ceil(radiusBlocks / 16.0);

// --- NEW: decide how far to jump each spiral step ---
        int preloadR = JubitusChunksConfig.GENERAL.preloadRadiusChunks;

        int bulkR = 0;
        int stride = 1;

        if (JubitusChunksConfig.GENERAL.bulkPopulateEnabled) {
            bulkR = JubitusChunksConfig.GENERAL.bulkRadiusChunks;

            // must have neighbors loaded around chunks we populate
            if (preloadR > 0) bulkR = Math.min(bulkR, preloadR - 1);
            else bulkR = 0;

            // jump by the width of the bulk square, so squares don't overlap
            stride = 2 * bulkR + 1;
        }

        this.bulkRUsed = bulkR;
        this.strideChunks = stride;

// We want to cover a chunkRadius square, but each step jumps by stride
// and each step populates +/- bulkRUsed around that center.
        int radiusSteps = (int) Math.ceil((this.chunkRadius + this.bulkRUsed) / (double) this.strideChunks);

        this.iterator = new SpiralChunkIterator(centerChunkX, centerChunkZ, radiusSteps, this.strideChunks);
        this.totalSteps = iterator.getMaxSteps();
        this.headChunkX = this.centerChunkX;
        this.headChunkZ = this.centerChunkZ;
        this.headRadiusChunks = JubitusChunksConfig.GENERAL.preloadRadiusChunks
                + (JubitusChunksConfig.GENERAL.bulkPopulateEnabled ? this.bulkRUsed : 0);
        this.headStepIndex = 0;

    }


    /**
     * @return true if done
     */
    public boolean tickStep() {
        if (world.getMinecraftServer() == null || world.getMinecraftServer().isServerStopped()) return true;

        if (memoryWatchdogMaybeStop()) {
            // Pause generation this tick (or shutdown was initiated)
            return false;
        }


        ChunkProviderServer cps = world.getChunkProvider();

        int targetStepsPerTick = computeEffectiveStepsPerTick();

        int did = 0;
        int lastWorkX = centerChunkX;
        int lastWorkZ = centerChunkZ;

        while (did < targetStepsPerTick) {

            // Don't consume a spiral step if we're already overloaded
            if (cps.getLoadedChunkCount() > JubitusChunksConfig.GENERAL.maxLoadedChunksSoftLimit) {
                break;
            }

            if (!iterator.hasNext()) {
                String msg = "Mill pregen complete. Steps=" + iterator.getSteps() + "/" + totalSteps
                        + " | skippedExisting=" + skippedExisting;
                notifyInitiatorAndConsole(msg);
                PregenState.delete(world);
                return true;
            }

            SpiralChunkIterator.ChunkCoord coord = nextTodo(cps);
            if (coord == null) break;
            processedSteps++;

            headChunkX = coord.x;
            headChunkZ = coord.z;
            headStepIndex = iterator.getSteps();

// what “square” moves with the spiral head:
// preload + bulk is the most useful for debugging loaded chunks
            int preloadR = JubitusChunksConfig.GENERAL.preloadRadiusChunks;
            int bulkR = (JubitusChunksConfig.GENERAL.bulkPopulateEnabled ? this.bulkRUsed : 0);
            headRadiusChunks = preloadR + bulkR;


            lastWorkX = coord.x;
            lastWorkZ = coord.z;

            try {
                boolean processedNow = processOne(cps, coord.x, coord.z);
                if (!processedNow) {
                    // soft limit reached (your existing behavior)
                    break;
                }
                did++;
            } catch (Throwable t) {
                JubitusChunksMod.LOGGER.error("Error generating chunk {},{} in dim {}",
                        coord.x, coord.z, world.provider.getDimension(), t);
                did++;
            }
        }

        // If we're overloaded, push unloading harder (toggleable)
        applyUnloadPressure(cps, lastWorkX, lastWorkZ);

        maybeSendProgress();

        if (!iterator.hasNext()) {
            String msg = "Mill pregen complete. Steps=" + iterator.getSteps() + "/" + totalSteps
                    + " | generated=" + generatedChunks
                    + " | populated=" + populatedChunks
                    + " | skippedExisting=" + skippedExisting
                    + " | processedSteps=" + processedSteps;
            notifyInitiatorAndConsole(msg);
            PregenState.delete(world);
            return true;
        }

        return false;
    }



    private void maybeSendProgress() {
        long now = System.currentTimeMillis();
        long intervalMs = JubitusChunksConfig.GENERAL.progressMessageIntervalSeconds * 1000L;
        if (now - lastProgressMsgMs < intervalMs) return;
        lastProgressMsgMs = now;


        long finished = iterator.getSteps();

        long dtMs = Math.max(1, now - perfLastMs);
        double dtSec = dtMs / 1000.0;

        long dSteps = finished - perfLastSteps;
        long dGen   = generatedChunks - perfLastGenerated;
        long dSkip  = skippedExisting - perfLastSkipped;

        double instSteps = dSteps / dtSec;
        double instGen   = dGen   / dtSec;
        double instSkip  = dSkip  / dtSec;

// Exponential moving average to keep it stable
        double alpha = 0.35;
        emaStepsPerSec = (emaStepsPerSec == 0) ? instSteps : (emaStepsPerSec * (1 - alpha) + instSteps * alpha);
        emaGenPerSec   = (emaGenPerSec   == 0) ? instGen   : (emaGenPerSec   * (1 - alpha) + instGen   * alpha);
        emaSkipPerSec  = (emaSkipPerSec  == 0) ? instSkip  : (emaSkipPerSec  * (1 - alpha) + instSkip  * alpha);

        perfLastMs = now;
        perfLastSteps = finished;
        perfLastGenerated = generatedChunks;
        perfLastSkipped = skippedExisting;

        long remainingSteps = Math.max(0, totalSteps - finished);
        long etaSec = (emaStepsPerSec > 0.001) ? (long) Math.ceil(remainingSteps / emaStepsPerSec) : -1;


        int pct = (int) ((finished * 100L) / Math.max(1L, totalSteps));

        String msg = "Mill pregen: step=" + finished + "/" + totalSteps + " (" + pct + "%)"
                + " | loaded=" + world.getChunkProvider().getLoadedChunkCount()
                + " | " + memString()
                + " | speed=" + String.format(java.util.Locale.ROOT,
                "%.2f steps/s",
                emaStepsPerSec)
                + " | eta=" + (etaSec < 0 ? "?" : formatDuration(etaSec));

        if (initiator != null) initiator.sendMessage(new TextComponentString(msg));
        else JubitusChunksMod.LOGGER.info(msg);

// Save state whenever we print progress (cheap + crash-safe)
        saveState();

    }



    private boolean processOne(ChunkProviderServer cps, int chunkX, int chunkZ) {

        final int preloadR = JubitusChunksConfig.GENERAL.preloadRadiusChunks;
        int bulkR = JubitusChunksConfig.GENERAL.bulkPopulateEnabled ? this.bulkRUsed : 0;

// Key change: preload extra to cover the bulk square edges
        final int preloadForStep = preloadR + bulkR;
        // touched chunks (so we can queueUnload them)
        int side = 2 * preloadForStep + 1;
        List<Chunk> touched = new ArrayList<>(side * side);


        // 1) preload neighbors
        for (int dx = -preloadForStep; dx <= preloadForStep; dx++) {
            for (int dz = -preloadForStep; dz <= preloadForStep; dz++) {
                Chunk c = cps.provideChunk(chunkX + dx, chunkZ + dz);
                if (c != null) touched.add(c);
            }
        }

        // 2) populate (either center only, or bulk area)
        if (!JubitusChunksConfig.GENERAL.bulkPopulateEnabled) {
            // center-only (player-ish)
            Chunk center = cps.provideChunk(chunkX, chunkZ);
            if (center != null) {
                if (!isAlreadyPopulated(center)) {
                    populateChunk(cps, chunkX, chunkZ, center);
                    generatedChunks++;
                } else if (skipExisting) {
                    skippedExisting++; // only if you want verify-mode to count this
                }
            }

            if (JubitusChunksConfig.GENERAL.queueUnloadCenterChunk && center != null) {
                cps.queueUnload(center);
            }

        } else {
            // bulk mode: populate a whole square per spiral step

            // Safety: ensure we have neighbors loaded around chunks we populate
            // best practice: bulkR <= preloadR - 1
            if (preloadR > 0) bulkR = Math.min(bulkR, preloadR - 1);
            else bulkR = 0;
            for (int dx = -bulkR; dx <= bulkR; dx++) {
                for (int dz = -bulkR; dz <= bulkR; dz++) {
                    int x = chunkX + dx;
                    int z = chunkZ + dz;

                    // NEW: don't generate outside the requested square radius
                    if (Math.abs(x - centerChunkX) > chunkRadius || Math.abs(z - centerChunkZ) > chunkRadius) {
                        continue;
                    }
                    // SKIP mode in bulk too (fast, no load)
                    if (skipExisting && !verifyExisting && cps.isChunkGeneratedAt(x, z)) {
                        skippedExisting++;
                        continue;
                    }

                    Chunk c = cps.provideChunk(x, z);

                    if (c != null) {
                        if (!isAlreadyPopulated(c)) {
                            populateChunk(cps, x, z, c);
                            generatedChunks++;
                        } else if (skipExisting) {
                            // VERIFY mode counts "already populated" as skipped
                            skippedExisting++;
                        }

                        if (JubitusChunksConfig.GENERAL.queueUnloadCenterChunk) {
                            cps.queueUnload(c);
                        }
                    }
                }
            }

        }

        // 3) unload touched neighbors too (very important for huge radiuses)
        if (JubitusChunksConfig.GENERAL.queueUnloadPreloadedNeighbors) {
            for (Chunk c : touched) {
                cps.queueUnload(c);
            }
        }

        return true;
    }
    private SpiralChunkIterator.ChunkCoord nextTodo(ChunkProviderServer cps) {
        while (iterator.hasNext()) {
            SpiralChunkIterator.ChunkCoord c = iterator.next();

            // SKIP mode: don't even load existing chunks
            if (skipExisting && !verifyExisting && cps.isChunkGeneratedAt(c.x, c.z)) {
                skippedExisting++;
                continue;
            }

            return c;
        }
        return null;
    }



    private void applyUnloadPressure(ChunkProviderServer cps, int keepCenterX, int keepCenterZ) {
        int loaded = cps.getLoadedChunkCount();

        if (!JubitusChunksConfig.GENERAL.aggressiveUnloadPressure) return;
        if (loaded <= JubitusChunksConfig.GENERAL.loadedChunksHardLimit) return;

        int keepR = JubitusChunksConfig.GENERAL.keepLoadedRadiusChunks;

        // Mark nearly everything unloadable except the small working window
        for (Chunk c : cps.getLoadedChunks()) {
            if (Math.abs(c.x - keepCenterX) <= keepR && Math.abs(c.z - keepCenterZ) <= keepR) {
                continue;
            }
            cps.queueUnload(c);
        }

        // Optional: run extra unload passes to catch up quicker
        int passes = JubitusChunksConfig.GENERAL.extraUnloadPassesWhenOverLimit;
        for (int i = 0; i < passes; i++) {
            cps.tick(); // unloads up to ~100 per call
        }
    }
    private int computeEffectiveStepsPerTick() {
        int requested = JubitusChunksConfig.GENERAL.stepsPerTick;
        if (!JubitusChunksConfig.GENERAL.adaptiveStepCap) return requested;

        int preloadR = JubitusChunksConfig.GENERAL.preloadRadiusChunks;
        int bulkR = (JubitusChunksConfig.GENERAL.bulkPopulateEnabled ? this.bulkRUsed : 0);
        int preloadForStep = preloadR + bulkR;

        int side = 2 * preloadForStep + 1;
        int touchedPerStep = side * side;


        // In bulk mode you may touch even more chunks, but touchedPerStep is still a good lower bound.
        int budget = JubitusChunksConfig.GENERAL.unloadBudgetPerTick;

        // If you're loading ~touchedPerStep chunks per step, keep steps small enough not to exceed unload budget
        int safe = Math.max(1, budget / Math.max(1, touchedPerStep));
        return Math.min(requested, safe);
    }
    private void notifyInitiatorAndConsole(String msg) {
        if (initiator != null) {
            initiator.sendMessage(new TextComponentString(msg));
        }
        // Always log to console too
        JubitusChunksMod.LOGGER.info(msg);
    }
    private static String memString() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;

        // MB
        long usedMb = used / (1024L * 1024L);
        long totalMb = total / (1024L * 1024L);
        long maxMb = max / (1024L * 1024L);

        return "mem=" + usedMb + "MB/" + totalMb;
    }
    private void saveState() {
        PregenState s = new PregenState();
        s.dim = world.provider.getDimension();
        s.centerChunkX = centerChunkX;
        s.centerChunkZ = centerChunkZ;
        s.chunkRadius = chunkRadius;
        s.spiralSteps = iterator.getSteps();

        s.skipExisting = skipExisting;
        s.verifyExisting = verifyExisting;                 // NEW
        s.populateViaGenerator = populateViaGenerator;     // NEW

        s.generatedChunks = generatedChunks;
        s.skippedExisting = skippedExisting;
        PregenState.save(world, s);
    }

    public void setSpiralStep(long stepIndex) {
        iterator.setSteps(stepIndex);

        // reset speed baseline to avoid a bogus huge first speed reading
        perfLastMs = System.currentTimeMillis();
        perfLastSteps = stepIndex;
        perfLastGenerated = generatedChunks;
        perfLastSkipped = skippedExisting;

        emaStepsPerSec = emaGenPerSec = emaSkipPerSec = 0.0;
    }

    private static boolean isAlreadyPopulated(Chunk c) {

        return c.isTerrainPopulated();
    }
    private void populateChunk(ChunkProviderServer cps, int chunkX, int chunkZ, Chunk c) {
        if (!populateViaGenerator) {
            c.populate(cps, cps.chunkGenerator);
            return;
        }

        net.minecraftforge.event.ForgeEventFactory.onChunkPopulate(true, cps.chunkGenerator, world, world.rand, chunkX, chunkZ, false);
        cps.chunkGenerator.populate(chunkX, chunkZ);
        net.minecraftforge.event.ForgeEventFactory.onChunkPopulate(false, cps.chunkGenerator, world, world.rand, chunkX, chunkZ, false);

        // IMPORTANT: mark flags, otherwise MC may try to populate again later
        c.setTerrainPopulated(true);
        c.setLightPopulated(true);
        c.markDirty();
    }
    private static String formatDuration(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600; s %= 3600;
        long m = s / 60;   s %= 60;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }
    private boolean memoryWatchdogMaybeStop() {
        if (!JubitusChunksConfig.GENERAL.stopServerOnLowMemory) return false;

        long now = System.currentTimeMillis();
        long intervalMs = JubitusChunksConfig.GENERAL.memoryCheckIntervalSeconds * 1000L;
        if (now - lastMemCheckMs < intervalMs) return false;
        lastMemCheckMs = now;

        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();

        int usedPct = (int) ((used * 100L) / Math.max(1L, max));
        int criticalPct = JubitusChunksConfig.GENERAL.criticalHeapUsedPercent;

        // Not critical: reset state
        if (usedPct < criticalPct) {
            gcRequested = false;
            usedBeforeGc = 0;
            consecutiveGcFailures = 0;
            return false;
        }

        // Critical: first time -> request GC and wait for next interval to measure
        if (!gcRequested) {
            gcRequested = true;
            usedBeforeGc = used;

            // Push unload pressure immediately too (helps more than GC sometimes)
            try {
                ChunkProviderServer cps = world.getChunkProvider();
                applyUnloadPressure(cps, centerChunkX, centerChunkZ);
            } catch (Throwable ignored) {}

            System.gc(); // stop-the-world; but better than OOM
            String msg = "Mill pregen: memory critical (" + usedPct + "% of heap). Forcing GC + extra unload pressure...";
            notifyInitiatorAndConsole(msg);
            return true; // pause work this check tick
        }

        // Second (or later) check after GC request: measure recovery
        long usedAfter = used;
        long freed = Math.max(0L, usedBeforeGc - usedAfter);
        long freedMb = freed / (1024L * 1024L);

        int minFreedMb = JubitusChunksConfig.GENERAL.minRecoveredAfterGcMB;
        if (freedMb < minFreedMb) {
            consecutiveGcFailures++;
            String msg = "Mill pregen: GC recovery too low (freed " + freedMb + "MB, need " + minFreedMb + "MB). "
                    + "Failure " + consecutiveGcFailures + "/" + JubitusChunksConfig.GENERAL.consecutiveGcFailuresToStop
                    + ". UsedHeap=" + usedPct + "% " + memString();
            notifyInitiatorAndConsole(msg);
        } else {
            consecutiveGcFailures = 0;
            notifyInitiatorAndConsole("Mill pregen: GC recovered " + freedMb + "MB. Continuing.");
        }

        // Reset GC request state so we can do another GC cycle if still critical
        gcRequested = false;
        usedBeforeGc = 0;

        if (consecutiveGcFailures >= JubitusChunksConfig.GENERAL.consecutiveGcFailuresToStop) {
            // Save pregen state BEFORE shutdown
            saveState();

            String stopMsg =
                    "Mill pregen stopped the server to prevent an out-of-memory crash.\n"
                            + "Heap usage stayed critical and GC could not free enough memory.\n"
                            + "Restart the game/server and run /pregenMill resume to continue.";

            notifyInitiatorAndConsole(stopMsg);

            // Ask the server to stop cleanly (same idea as /stop)
            try {
                server.initiateShutdown();
            } catch (Throwable t) {
                // Fallback: stopServer exists in many 1.12 servers
                try {
                    server.stopServer();
                } catch (Throwable ignored) {}
            }
            return true;
        }

        return true; // while we're in critical mode, we pause generating on check ticks
    }
    public int getHeadChunkX() { return headChunkX; }
    public int getHeadChunkZ() { return headChunkZ; }
    public int getHeadRadiusChunks() { return headRadiusChunks; }
    public long getHeadStepIndex() { return headStepIndex; }

    public int getCenterChunkX() { return centerChunkX; }
    public int getCenterChunkZ() { return centerChunkZ; }
    public int getChunkRadius()  { return chunkRadius; }
    public long getTotalSteps()  { return totalSteps; }
    public long getGeneratedChunks() { return generatedChunks; }
    public long getSkippedExisting() { return skippedExisting; }

}
