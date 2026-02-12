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

    public int getRadiusSteps() { return r; }
    public int getStrideChunks() { return stride; }


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
        ChunkCoord out = peek();
        advance();
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
     * Jump the iterator so that the *next* yield is the chunk at index stepIndex.
     * stepIndex=0 -> center chunk.
     *
     * Invariant after setSteps: (x,z) equals coordAt(steps), and (dx,dz) is the
     * direction needed to advance from coordAt(steps) to coordAt(steps+1).
     */
    public void setSteps(long stepIndex) {
        if (stepIndex < 0) stepIndex = 0;
        if (stepIndex >= max) {          // clamp to END, not "one past + compute"
            steps = max;
            x = 0; z = 0; dx = 0; dz = -1;
            return;
        }

        this.steps = stepIndex;

        if (stepIndex == 0) {
            x = 0; z = 0; dx = 0; dz = -1;
            return;
        }

        int[] cur = coordAt(stepIndex);
        x = cur[0];
        z = cur[1];

        int[] nxt = coordAt(stepIndex + 1);
        dx = nxt[0] - cur[0];
        dz = nxt[1] - cur[1];
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
    public ChunkCoord peek() {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        return new ChunkCoord(cx + x * stride, cz + z * stride);
    }

    public void advance() {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        steps++;
        advanceSpiral();
    }



}
