package com.jubitus.jubituschunks.pregen;

import com.google.common.collect.ImmutableSetMultimap;
import com.jubitus.jubituschunks.JubitusChunksMod;
import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.pregen.fun.FollowManager;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.ArrayList;
import java.util.List;

public class PregenTask {

    private int lastFailX = Integer.MIN_VALUE;
    private int lastFailZ = Integer.MIN_VALUE;
    private int consecutiveFailsSameCoord = 0;

    private static final int MAX_FAILS_SAME_COORD = 10;


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

    // --- "do not unload" awareness (players + Forge forced chunks) ---
    private final java.util.Set<Long> forcedChunkKeys = new java.util.HashSet<>();
    private long lastForcedRefreshTick = 0;

    // how often to refresh the forced-chunk list (ticks). 200 = 10 seconds
    private static final int FORCED_REFRESH_INTERVAL_TICKS = 200;


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
        tickCounter++;
        cleanupProtection();
        if (world.getMinecraftServer() == null || world.getMinecraftServer().isServerStopped()) return true;

        MemAction mem = memoryWatchdogMaybeStop();
        if (mem == MemAction.PAUSE) {
            // pause this tick, keep task alive
            return false;
        }
        if (mem == MemAction.TERMINATE) {
            // stop task first (manager will remove it)
            return true;
        }



        ChunkProviderServer cps = world.getChunkProvider();

        int targetStepsPerTick = computeEffectiveStepsPerTick();

        int did = 0;
        int lastWorkX = centerChunkX;
        int lastWorkZ = centerChunkZ;

        while (did < targetStepsPerTick) {

            // Don't consume a spiral step if we're already overloaded
            if (cps.getLoadedChunkCount() > JubitusChunksConfig.GENERAL.maxLoadedChunksSoftLimit) {
                // IMPORTANT: on ne génère pas plus, on force l’unload + IO à rattraper
                drainUnloadsAndFlush(cps, false);
                break;
            }


            if (!iterator.hasNext()) {
                String msg = "Jubitus Chunks Pregeneration complete. Steps=" + iterator.getSteps() + "/" + totalSteps
                        + " | skippedExisting=" + skippedExisting;
                notifyInitiatorAndConsole(msg);
                try {
                    drainUnloadsAndFlush(cps, true);
                } catch (Throwable ignored) {}
                PregenState.delete(world);
                return true;
            }

            // Peek next spiral coordinate WITHOUT consuming it
            SpiralChunkIterator.ChunkCoord coord = iterator.peek();

            headChunkX = coord.x;
            headChunkZ = coord.z;

// what “square” moves with the spiral head:
            int preloadR = JubitusChunksConfig.GENERAL.preloadRadiusChunks;
            int bulkR = (JubitusChunksConfig.GENERAL.bulkPopulateEnabled ? this.bulkRUsed : 0);
            headRadiusChunks = preloadR + bulkR;

            lastWorkX = coord.x;
            lastWorkZ = coord.z;

            try {
                boolean processedNow = processOne(cps, coord.x, coord.z);
                if (!processedNow) {
                    // e.g. soft limit logic — do NOT advance spiral
                    break;
                }

                // ✅ Only now do we consume the spiral step
                iterator.advance();
                processedSteps++;

                headStepIndex = iterator.getSteps(); // "completed steps" / next index

                did++;
            } catch (Throwable t) {
                JubitusChunksMod.LOGGER.error("Error generating chunk {},{} in dim {}",
                        coord.x, coord.z, world.provider.getDimension(), t);

                if (coord.x == lastFailX && coord.z == lastFailZ) {
                    consecutiveFailsSameCoord++;
                } else {
                    lastFailX = coord.x;
                    lastFailZ = coord.z;
                    consecutiveFailsSameCoord = 1;
                }

                if (consecutiveFailsSameCoord >= MAX_FAILS_SAME_COORD) {
                    JubitusChunksMod.LOGGER.error(
                            "Chunk {},{} keeps failing ({} times). Skipping this spiral step to avoid stalling.",
                            coord.x, coord.z, consecutiveFailsSameCoord
                    );

                    // record this somewhere (file / state NBT) if you want a repair pass
                    // e.g. PregenState.addFailedCoord(dim, coord.x, coord.z);

                    iterator.advance(); // ⚠️ this creates a hole but avoids infinite lock
                    processedSteps++;
                    headStepIndex = iterator.getSteps();
                    consecutiveFailsSameCoord = 0;
                }

                break;
            }


        }

        // If we're overloaded, push unloading harder (toggleable)
        applyUnloadPressure(cps, lastWorkX, lastWorkZ);

        maybeSendProgress();

        if (!iterator.hasNext()) {
            String msg = "Jubitus Chunks Pregeneration complete. Steps=" + iterator.getSteps() + "/" + totalSteps
                    + " | generated=" + generatedChunks
                    + " | populated=" + populatedChunks
                    + " | skippedExisting=" + skippedExisting
                    + " | processedSteps=" + processedSteps;
            notifyInitiatorAndConsole(msg);
            try {
                drainUnloadsAndFlush(cps, true);
            } catch (Throwable ignored) {}
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

        String msg = "Jubitus Chunks Pregeneration: step=" + finished + "/" + totalSteps + " (" + pct + "%)"
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
        // ✅ Très important : flush régulier pour éviter les chunks "fantômes" non écrits sur disque
        try {
            ChunkProviderServer cps = world.getChunkProvider();
            drainUnloadsAndFlush(cps, true);
        } catch (Throwable ignored) {}


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
                Chunk c = loadOrGenerateRaw(cps, chunkX + dx, chunkZ + dz);
                if (c != null) {
                    touched.add(c);
                    protectChunk(chunkX + dx, chunkZ + dz, JubitusChunksConfig.GENERAL.keepNeighborChunksLoadedTicks);
                }

            }
        }

        // 2) populate (either center only, or bulk area)
        if (!JubitusChunksConfig.GENERAL.bulkPopulateEnabled) {
            // center-only (player-ish)
            Chunk center = loadOrGenerateRaw(cps, chunkX, chunkZ);
            if (center != null) {

                // Does this chunk already exist on disk?
                // If yes, we should NOT "retrogen" it (avoid re-populating) if it's already populated.
                boolean existedOnDisk = cps.isChunkGeneratedAt(chunkX, chunkZ);

                boolean shouldPopulate;

                if (!existedOnDisk) {
                    // Brand new chunk: ALWAYS populate (trees/ores/etc)
                    shouldPopulate = true;
                } else {
                    // Existing chunk: only populate if it's NOT populated yet
                    // (this covers crash-resume cases without retrogen)
                    shouldPopulate = !isAlreadyPopulated(center);
                }

                if (shouldPopulate) {
                    populateChunk(cps, chunkX, chunkZ, center);
                    generatedChunks++;
                    protectChunk(chunkX, chunkZ, JubitusChunksConfig.GENERAL.keepPopulatedChunksLoadedTicks);
                } else if (skipExisting) {
                    skippedExisting++;
                }
            }

// ✅ IMPORTANT: only unload if NOT protected
            if (JubitusChunksConfig.GENERAL.queueUnloadCenterChunk && center != null) {
                if (!shouldNeverUnload(center.x, center.z)) {
                    cps.queueUnload(center);
                }
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

                    // Stay inside the requested square radius
                    if (Math.abs(x - centerChunkX) > chunkRadius || Math.abs(z - centerChunkZ) > chunkRadius) {
                        continue;
                    }

                    // Does it already exist on disk?
                    boolean existedOnDisk = cps.isChunkGeneratedAt(x, z);

                    // Fast skip mode: if it exists on disk and user wants skipping, do not touch it at all.
                    // (No retrogen, fastest.)
                    if (skipExisting && !verifyExisting && existedOnDisk) {
                        skippedExisting++;
                        continue;
                    }

                    // Ensure it's loaded/generated through normal provider path
                    Chunk c = loadOrGenerateRaw(cps, x, z);
                    if (c == null) continue;

                    boolean shouldPopulate;
                    if (!existedOnDisk) {
                        // Brand new chunk: ALWAYS populate (trees/ores/etc)
                        shouldPopulate = true;
                    } else {
                        // Existing chunk: only populate if it was never populated (crash recovery)
                        shouldPopulate = !isAlreadyPopulated(c);
                    }

                    if (shouldPopulate) {
                        populateChunk(cps, x, z, c);
                        generatedChunks++;
                        protectChunk(x, z, JubitusChunksConfig.GENERAL.keepPopulatedChunksLoadedTicks);
                    } else if (skipExisting) {
                        skippedExisting++;
                    }

                    if (JubitusChunksConfig.GENERAL.queueUnloadCenterChunk) {
                        if (!shouldNeverUnload(c.x, c.z)) {
                            cps.queueUnload(c);
                        }
                    }
                }
            }

        }

        // 3) unload touched neighbors too (very important for huge radiuses)
        if (JubitusChunksConfig.GENERAL.queueUnloadPreloadedNeighbors) {
            for (Chunk c : touched) {
                if (!shouldNeverUnload(c.x, c.z)) {
                    cps.queueUnload(c);
                }
            }
        }


        return true;
    }



    private void applyUnloadPressure(ChunkProviderServer cps, int keepCenterX, int keepCenterZ) {
        int loaded = cps.getLoadedChunkCount();

        if (!JubitusChunksConfig.GENERAL.aggressiveUnloadPressure) return;
        if (loaded <= JubitusChunksConfig.GENERAL.loadedChunksHardLimit) return;

        int keepR = JubitusChunksConfig.GENERAL.keepLoadedRadiusChunks;

        // Mark nearly everything unloadable except the small working window
        for (Chunk c : cps.getLoadedChunks()) {
            if (shouldNeverUnload(c.x, c.z)) continue;

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
        s.radiusSteps = iterator.getRadiusSteps();
        s.strideChunks = iterator.getStrideChunks();

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

        c.setTerrainPopulated(true);
        c.setLightPopulated(true);
        c.markDirty();
    }


    private Chunk loadOrGenerateRaw(ChunkProviderServer cps, int x, int z) {
        // This ensures chunks are created/loaded through the normal provider path.
        // It prevents "terrain only" results caused by missing provider bookkeeping.
        return cps.provideChunk(x, z);
    }


    private static String formatDuration(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600; s %= 3600;
        long m = s / 60;   s %= 60;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }
    private MemAction memoryWatchdogMaybeStop() {
        if (!JubitusChunksConfig.GENERAL.stopServerOnLowMemory) return MemAction.NONE;

        long now = System.currentTimeMillis();
        long intervalMs = JubitusChunksConfig.GENERAL.memoryCheckIntervalSeconds * 1000L;
        if (now - lastMemCheckMs < intervalMs) return MemAction.NONE;
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
            return MemAction.NONE;
        }


        // Critical: first time -> request GC and wait for next interval to measure
        if (!gcRequested) {
            gcRequested = true;
            usedBeforeGc = used;

            try {
                ChunkProviderServer cps = world.getChunkProvider();
                applyUnloadPressure(cps, centerChunkX, centerChunkZ);
            } catch (Throwable ignored) {}

            System.gc();
            notifyInitiatorAndConsole("Jubitus Chunks Pregeneration: memory critical (" + usedPct + "% of heap). Forcing GC + extra unload pressure...");
            return MemAction.PAUSE; // pause this tick
        }


        // Second (or later) check after GC request: measure recovery
        long usedAfter = used;
        long freed = Math.max(0L, usedBeforeGc - usedAfter);
        long freedMb = freed / (1024L * 1024L);

        int minFreedMb = JubitusChunksConfig.GENERAL.minRecoveredAfterGcMB;
        if (freedMb < minFreedMb) {
            consecutiveGcFailures++;
            String msg = "Jubitus Chunks Pregeneration: GC recovery too low (freed " + freedMb + "MB, need " + minFreedMb + "MB). "
                    + "Failure " + consecutiveGcFailures + "/" + JubitusChunksConfig.GENERAL.consecutiveGcFailuresToStop
                    + ". UsedHeap=" + usedPct + "% " + memString();
            notifyInitiatorAndConsole(msg);
        } else {
            consecutiveGcFailures = 0;
            notifyInitiatorAndConsole("Jubitus Chunks Pregeneration: GC recovered " + freedMb + "MB. Continuing.");
        }

        // Reset GC request state so we can do another GC cycle if still critical
        gcRequested = false;
        usedBeforeGc = 0;

        if (consecutiveGcFailures >= JubitusChunksConfig.GENERAL.consecutiveGcFailuresToStop) {

            // Save pregen state BEFORE shutdown
            saveState();
            try {
                ChunkProviderServer cps = world.getChunkProvider();
                drainUnloadsAndFlush(cps, true);
            } catch (Throwable ignored) {}


            String stopMsg =
                    "Jubitus Chunks Pregeneration stopped the server to prevent an out-of-memory crash.\n"
                            + "Heap usage stayed critical and GC could not free enough memory.\n"
                            + "Restart the game/server and run /jubituschunks resume to continue.";

            notifyInitiatorAndConsole(stopMsg);

            // Ask the server to stop cleanly (same idea as /stop)
            try {
                server.initiateShutdown();
            } catch (Throwable t) {
                try {
                    server.stopServer();
                } catch (Throwable ignored) {}
            }

            // IMPORTANT: terminate the pregen task immediately,
            // so it is removed from PregenManager before shutdown proceeds.
            return MemAction.TERMINATE;
        }


        return MemAction.PAUSE; // while we're in critical mode, pause generating on check ticks
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
    private enum MemAction {
        NONE,
        PAUSE,
        TERMINATE
    }
    // chunks we promise not to unload until a certain server tick
    private final java.util.Map<Long, Long> protectUntilTick = new java.util.HashMap<>();
    private long tickCounter = 0;

    private static long chunkKey(int x, int z) {
        return (((long)x) << 32) ^ (z & 0xffffffffL);
    }

    private void protectChunk(int x, int z, int ticks) {
        if (ticks <= 0) return;
        long key = chunkKey(x, z);
        long until = tickCounter + ticks;
        Long prev = protectUntilTick.get(key);
        if (prev == null || prev < until) protectUntilTick.put(key, until);
    }

    private boolean isProtected(int x, int z) {
        Long until = protectUntilTick.get(chunkKey(x, z));
        return until != null && until > tickCounter;
    }

    private void cleanupProtection() {
        // cheap cleanup occasionally
        if ((tickCounter & 31) != 0) return; // every 32 ticks
        java.util.Iterator<java.util.Map.Entry<Long, Long>> it = protectUntilTick.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= tickCounter) it.remove();
        }
    }
    public void requestStopAndFlush() {
        // stop generating immediately, but keep world valid
        saveState();

        try {
            ChunkProviderServer cps = world.getChunkProvider();

            // Force chunk saves (flush dirty chunks)
            cps.saveChunks(true);

            // Also flush any pending threaded IO work
            net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        } catch (Throwable t) {
            JubitusChunksMod.LOGGER.warn("Failed to flush chunk IO on stop", t);
        }
    }
    private void refreshForcedChunksMaybe() {
        if (tickCounter - lastForcedRefreshTick < FORCED_REFRESH_INTERVAL_TICKS) return;
        lastForcedRefreshTick = tickCounter;

        forcedChunkKeys.clear();

        try {
            // keySet = chunks that are held by ForgeChunkManager tickets (mod force-load)
            ImmutableSetMultimap<ChunkPos, ForgeChunkManager.Ticket> mm =
                    ForgeChunkManager.getPersistentChunksFor(world);

            for (ChunkPos pos : mm.keySet()) {
                forcedChunkKeys.add(chunkKey(pos.x, pos.z));
            }
        } catch (Throwable t) {
            // don't crash pregen if some mod does weird stuff
            JubitusChunksMod.LOGGER.warn("Failed to refresh forced chunk list", t);
        }
    }

    private boolean isForceLoadedByMods(int x, int z) {
        // keep list reasonably fresh
        refreshForcedChunksMaybe();
        return forcedChunkKeys.contains(chunkKey(x, z));
    }

    private boolean isInAnyPlayerView(int x, int z) {
        int vd = server.getPlayerList().getViewDistance(); // chunks
        for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
            if (p.dimension != world.provider.getDimension()) continue;

            int dx = Math.abs(p.chunkCoordX - x);
            int dz = Math.abs(p.chunkCoordZ - z);
            if (dx <= vd && dz <= vd) return true;
        }
        return false;
    }


    /** True if we should NEVER queueUnload this chunk. */
    private boolean shouldNeverUnload(int x, int z) {
        if (isProtected(x, z)) return true;
        if (isForceLoadedByMods(x, z)) return true;
        if (isInAnyPlayerView(x, z)) return true;
        return false;
    }
    private void drainUnloadsAndFlush(ChunkProviderServer cps, boolean forceAllSaves) {
        try {
            // 1) Laisser ChunkProviderServer traiter la queue d'unload
            // (vanilla: ~100 unload max par tick)
            for (int i = 0; i < 20; i++) {
                cps.tick();
            }

            // 2) Sauver les chunks encore chargés (si nécessaire)
            cps.saveChunks(forceAllSaves);

            // 3) Flush du loader (écritures disque)
            cps.flushToDisk();

            // 4) Finir l'IO thread (important en moddé)
            net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        } catch (Throwable t) {
            JubitusChunksMod.LOGGER.warn("Failed to drain unloads/flush IO", t);
        }
    }

}
