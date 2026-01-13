package com.jubitus.jubituschunks.pregen;

import java.util.NoSuchElementException;

public class SpiralChunkIterator {

    public static class ChunkCoord {
        public final int x;
        public final int z;
        public ChunkCoord(int x, int z) { this.x = x; this.z = z; }
    }

    private final int cx, cz;
    private final int r;          // radius in "spiral steps" (not chunks)
    private final int stride;     // how many chunks we jump per spiral step
    private final long max;
    private long steps = 0;



    // Spiral state around (0,0)
    private int x = 0, z = 0;
    private int dx = 0, dz = -1;



    public SpiralChunkIterator(int centerX, int centerZ, int radiusSteps, int strideChunks) {
        this.cx = centerX;
        this.cz = centerZ;
        this.r = radiusSteps;
        this.stride = Math.max(1, strideChunks);

        long d = (long) (r * 2 + 1);
        this.max = d * d;
    }

    public long getSteps() { return steps; }
    public long getMaxSteps() { return max; }

    public boolean hasNext() {
        return steps < max;
    }

    public ChunkCoord next() {
        if (!hasNext()) throw new java.util.NoSuchElementException();

        // IMPORTANT: multiply spiral coordinates by stride so we jump farther each step
        ChunkCoord out = new ChunkCoord(cx + x * stride, cz + z * stride);
        steps++;
        advanceSpiral();
        return out;
    }

    private void advanceSpiral() {
        if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
            int tmp = dx;
            dx = -dz;
            dz = tmp;
        }
        x += dx;
        z += dz;
    }

    /**
     * Jump the iterator so that the *next* call to next() yields the chunk at index stepIndex.
     * stepIndex=0 -> center chunk.
     */
    public void setSteps(long stepIndex) {
        if (stepIndex < 0) stepIndex = 0;
        if (stepIndex > max) stepIndex = max;

        this.steps = stepIndex;

        if (stepIndex == 0) {
            x = 0; z = 0; dx = 0; dz = -1;
            return;
        }

        // We want the internal (x,z,dx,dz) state corresponding to "about to yield stepIndex"
        // That equals the spiral coordinate at stepIndex, and direction equals (coord(stepIndex)-coord(stepIndex-1)).
        int[] cur = coordAt(stepIndex);
        int[] prev = coordAt(stepIndex - 1);

        x = cur[0];
        z = cur[1];
        dx = cur[0] - prev[0];
        dz = cur[1] - prev[1];
    }

    /**
     * Standard square spiral coordinate for index n:
     * 0:(0,0), 1:(1,0), 2:(1,1), 3:(0,1), 4:(-1,1) ...
     */
    private static int[] coordAt(long n) {
        if (n == 0) return new int[]{0, 0};

        // ring k
        long k = (long) Math.ceil((Math.sqrt(n + 1.0) - 1.0) / 2.0);
        long t = 2L * k + 1L;        // side length
        long m = t * t - 1L;         // max index on this ring
        long side = 2L * k;

        long d = m - n;

        int x, z;
        if (d < side) {
            // bottom side, moving left
            x = (int) (k - d);
            z = (int) (-k);
        } else if (d < 2L * side) {
            // left side, moving up
            d -= side;
            x = (int) (-k);
            z = (int) (-k + d);
        } else if (d < 3L * side) {
            // top side, moving right
            d -= 2L * side;
            x = (int) (-k + d);
            z = (int) (k);
        } else {
            // right side, moving down
            d -= 3L * side;
            x = (int) (k);
            z = (int) (k - d);
        }

        return new int[]{x, z};
    }
}
