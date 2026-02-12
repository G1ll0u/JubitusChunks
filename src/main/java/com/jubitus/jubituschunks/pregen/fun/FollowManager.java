package com.jubitus.jubituschunks.pregen.fun;

import com.jubitus.jubituschunks.pregen.PregenManager;
import com.jubitus.jubituschunks.pregen.PregenTask;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FollowManager {

    private static class Session {
        final UUID playerId;
        final int dim;

        long lastTickMs = 0;

        int lastHeadChunkX = Integer.MIN_VALUE;
        int lastHeadChunkZ = Integer.MIN_VALUE;

        double emaHeadBlocksPerSec = 0.0; // smoothed head speed

        Session(UUID playerId, int dim) {
            this.playerId = playerId;
            this.dim = dim;
        }
    }


    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** Toggle follow for this player in their current dimension. Returns true if now enabled. */
    public static boolean toggle(EntityPlayerMP p) {
        UUID id = p.getUniqueID();
        if (SESSIONS.containsKey(id)) {
            SESSIONS.remove(id);
            return false;
        } else {
            SESSIONS.put(id, new Session(id, p.dimension));
            return true;
        }
    }

    public static void stop(EntityPlayerMP p) {
        SESSIONS.remove(p.getUniqueID());
    }

    /** Used by PregenTask to ignore watchers so they don't block unloading. */
    public static boolean isWatcher(UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }

    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;

        for (Session s : SESSIONS.values()) {
            EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(s.playerId);
            if (p == null) {
                SESSIONS.remove(s.playerId);
                continue;
            }

            if (p.dimension != s.dim) {
                SESSIONS.remove(s.playerId);
                continue;
            }

            PregenTask t = PregenManager.getTask(s.dim);
            if (t == null) {
                SESSIONS.remove(s.playerId);
                p.sendMessage(new net.minecraft.util.text.TextComponentString(
                        "No pregen task running here; follow disabled."
                ));
                continue;
            }

            WorldServer world = server.getWorld(s.dim);
            if (world == null) continue;

            // --- head position ---
            int hx = t.getHeadChunkX();
            int hz = t.getHeadChunkZ();

            // --- dt ---
            long now = System.currentTimeMillis();
            double dtSec;
            if (s.lastTickMs == 0) dtSec = 0.05;
            else dtSec = Math.max(0.001, (now - s.lastTickMs) / 1000.0);
            s.lastTickMs = now;

            // --- estimate head speed (blocks/sec), smoothed ---
            if (s.lastHeadChunkX != Integer.MIN_VALUE) {
                int dChunkX = hx - s.lastHeadChunkX;
                int dChunkZ = hz - s.lastHeadChunkZ;

                double dBlocks = Math.sqrt((double)dChunkX * dChunkX + (double)dChunkZ * dChunkZ) * 16.0;
                double instHeadBps = dBlocks / dtSec;

                double alpha = 0.25;
                s.emaHeadBlocksPerSec = (s.emaHeadBlocksPerSec == 0.0)
                        ? instHeadBps
                        : (s.emaHeadBlocksPerSec * (1.0 - alpha) + instHeadBps * alpha);
            }
            s.lastHeadChunkX = hx;
            s.lastHeadChunkZ = hz;

            // Aim at middle of the head chunk
            int x = (hx << 4) + 8;
            int z = (hz << 4) + 8;

            // Keep your "high Y" spectator-style view
            BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z));
            double tx = x + 0.5;
            double tz = z + 0.5;
            double ty = top.getY() + 32.0;

            // --- vector to target ---
            double dx = tx - p.posX;
            double dy = ty - p.posY;
            double dz = tz - p.posZ;

            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);

            // close enough: damp motion a bit
            if (dist < 0.8) {
                p.motionX *= 0.6;
                p.motionY *= 0.6;
                p.motionZ *= 0.6;
                p.velocityChanged = true;
                continue; // IMPORTANT: continue, not return
            }

            double inv = 1.0 / Math.max(0.0001, dist);
            double dirX = dx * inv;
            double dirY = dy * inv;
            double dirZ = dz * inv;

            // --- SPEED CONTROLLER ---
            // base = head speed, plus catch-up that grows with distance
            double headBps = Math.max(0.0, s.emaHeadBlocksPerSec);

            // Increase this multiplier if you're still lagging behind
            double catchupBps = dist * 1.2;

            double desiredBps = headBps + catchupBps;

            // cap to avoid rubberband insanity
            double maxBps = 600.0;
            desiredBps = Math.min(desiredBps, maxBps);

            // blocks/sec -> blocks/tick (20tps)
            double speedPerTick = desiredBps / 20.0;

            // Apply motion
            p.motionX = dirX * speedPerTick;
            p.motionZ = dirZ * speedPerTick;

            // vertical: don't bob too hard
            double maxYPerTick = 1.2; // since you're hovering at +32, allow faster vertical correction
            if (Math.abs(dy) > 1.5) {
                p.motionY = Math.max(-maxYPerTick, Math.min(maxYPerTick, dirY * speedPerTick));
            } else {
                p.motionY *= 0.7;
            }

            p.fallDistance = 0;
            p.velocityChanged = true;
        }
    }


}