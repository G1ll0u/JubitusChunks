package com.jubitus.jubituschunks.config;

import com.jubitus.jubituschunks.Tags;
import net.minecraftforge.common.config.Config;

@Config(modid = Tags.MOD_ID, name = "jubituschunks")
public class JubitusChunksConfig {

    @Config.Name("general")
    public static final General GENERAL = new General();

    public static class General {

        @Config.Comment({
                "Maximum spiral STEPS to process per server tick.",
                "A 'step' is one spiral position (NOT always 1 chunk).",
                "If bulkPopulateEnabled=true, one step can populate a whole square of chunks (see bulkRadiusChunks).",
                "Higher = faster, but more lag + more loaded chunks + more RAM usage.",
                "NOTE: If adaptiveStepCap=true, the real steps/tick may be clamped lower automatically."
        })
        @Config.RangeInt(min = 1, max = 64)
        public int stepsPerTick = 8;

        @Config.Comment({
                "Neighbor preload radius (in CHUNKS) around the work chunk before populating.",
                "0 = only the target chunk.",
                "1 = 3x3 loaded, 2 = 5x5 loaded, 3 = 7x7 loaded, etc.",
                "Higher improves compatibility with some mods (they expect neighbor chunks to exist),",
                "but memory usage increases very quickly."
        })
        @Config.RangeInt(min = 0, max = 8)
        public int preloadRadiusChunks = 3;


        @Config.Comment({
                "If loaded chunk count is above this, the pregenerator will pause for this tick",
                "to allow the server to unload chunks (prevents huge memory usage)."
        })
        @Config.RangeInt(min = 64, max = 50000)
        public int maxLoadedChunksSoftLimit = 12000;

        @Config.Comment({
                "If true, queueUnload() is called on the center chunk after it is generated/populated.",
                "This marks it as unloadable so it won't stay in memory forever."
        })
        public boolean queueUnloadCenterChunk = true;

        @Config.Comment({
                "If true, queueUnload() is also called on the preloaded neighbor chunks (after populating the center).",
                "This is the best option for huge radiuses (10k+ blocks) to avoid accumulating loaded chunks."
        })
        public boolean queueUnloadPreloadedNeighbors = true;

        @Config.Comment({
                "How often (in seconds) to send progress messages to the command sender."
        })
        @Config.RangeInt(min = 1, max = 3600)
        public int progressMessageIntervalSeconds = 5;
        @Config.Comment({
                "If true, each spiral step will populate and mark a whole square area as DONE,",
                "instead of only the center chunk.",
                "Example: bulkRadiusChunks=1 -> 3x3 chunks per spiral step.",
                "This makes generation much faster, but is less 'player-like'."
        })
        public boolean bulkPopulateEnabled = true;

        @Config.Comment({
                "Radius (in chunks) of the square to populate per spiral step when bulkPopulateEnabled=true.",
                "1 = 3x3, 2 = 5x5, etc.",
                "NOTE: The code clamps this to at most (preloadRadiusChunks - 1) as a safety policy.",
                "Larger values increase memory use sharply because more chunks must be loaded per step."
        })
        @Config.RangeInt(min = 0, max = 8)
        public int bulkRadiusChunks = 1;
        @Config.Comment({
                "If true, dynamically reduce steps/tick so we don't load chunks faster than the server can unload them.",
                "Highly recommended for large preloadRadiusChunks or high stepsPerTick.",
                "In bulk mode, the adaptive cap accounts for the larger effective preload area."
        })
        public boolean adaptiveStepCap = true;

        @Config.Comment({
                "Approx unload budget per tick of ChunkProviderServer.tick(). Vanilla/Forge is ~100.",
                "Used only for adaptiveStepCap."
        })
        @Config.RangeInt(min = 10, max = 500)
        public int unloadBudgetPerTick = 100;

        @Config.Comment({
                "Extra times to call ChunkProviderServer.tick() when overloaded (to unload faster).",
                "0 = don't force extra unload passes (safest, but may accumulate chunks if gen is too fast)."
        })
        @Config.RangeInt(min = 0, max = 20)
        public int extraUnloadPassesWhenOverLimit = 2;

        @Config.Comment({
                "Hard limit: if loaded chunk count exceeds this, we trigger aggressive unload pressure.",
                "Set lower for less RAM use."
        })
        @Config.RangeInt(min = 128, max = 50000)
        public int loadedChunksHardLimit = 1800;

        @Config.Comment({
                "If true, when loadedChunksHardLimit is exceeded, queueUnload() all loaded chunks outside keepLoadedRadiusChunks.",
                "This prevents 'trail' chunks from staying loaded."
        })
        public boolean aggressiveUnloadPressure = true;

        @Config.Comment({
                "Chunks within this radius (Chebyshev distance) around the current working chunk are NOT queued for unload",
                "during aggressive unload pressure.",
                "Recommended: keepLoadedRadiusChunks >= (preloadRadiusChunks + bulkRadiusChunks) in bulk mode."
        })
        @Config.RangeInt(min = 0, max = 32)
        public int keepLoadedRadiusChunks = 6;
        @Config.Comment({
                "If true, pregeneration will SKIP chunks that already exist on disk (already generated).",
                "This makes expanding an existing world much faster because it won't load/populate old chunks.",
                "WARNING: If you changed worldgen mods/settings, skipping means old chunks won't get 'updated'."
        })
        public boolean skipAlreadyGeneratedChunks = true;
        @Config.Comment({
                "When skipAlreadyGeneratedChunks=true:",
                "false = SKIP mode: skip chunks purely via isChunkGeneratedAt() (fastest, no loading).",
                "true  = VERIFY mode: load the chunk and only populate if it is NOT populated yet (slower, safer after crashes)."
        })
        public boolean verifyExistingChunks = false;
        @Config.Comment({
                "Population method:",
                "false = use Chunk.populate(provider, generator) (vanilla-style).",
                "true  = use IChunkGenerator.populate(x,z) and then manually mark chunk populated.",
                "Some mods behave better with one or the other."
        })
        public boolean populateViaGenerator = false;
        @Config.Comment({
                "If true, the pregenerator will stop the server cleanly when memory is critically high",
                "and a forced GC does not free enough heap (see minRecoveredAfterGcMB).",
                "This prevents OOM and allows you to /pregenMill resume after restart."
        })
        public boolean stopServerOnLowMemory = true;

        @Config.Comment({
                "How often (in seconds) to run the memory watchdog check."
        })
        @Config.RangeInt(min = 1, max = 3600)
        public int memoryCheckIntervalSeconds = 10;

        @Config.Comment({
                "Critical heap usage percent (of -Xmx). If used heap >= this percent, the watchdog triggers."
        })
        @Config.RangeInt(min = 50, max = 99)
        public int criticalHeapUsedPercent = 97;

        @Config.Comment({
                "When memory is critical, the watchdog triggers a GC and checks how much was freed.",
                "If less than this many MB were recovered, it counts as a failure."
        })
        @Config.RangeInt(min = 0, max = 8192)
        public int minRecoveredAfterGcMB = 300;

        @Config.Comment({
                "How many consecutive GC failures before the server is stopped."
        })
        @Config.RangeInt(min = 1, max = 20)
        public int consecutiveGcFailuresToStop = 3;

    }



}
