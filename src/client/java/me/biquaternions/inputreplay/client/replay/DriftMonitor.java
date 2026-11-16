package me.biquaternions.inputreplay.client.replay;

import me.biquaternions.inputreplay.client.io.RecordingReader.Snap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Live player state vs. the source recording's snapshot of the same anchored tick. */
public final class DriftMonitor {
    private final double threshold;
    public double lastDpos = Double.NaN;
    public double lastDvel = Double.NaN;
    public double lastDrot = Double.NaN;
    public double maxDpos;
    public int firstDivergentTick = -1;
    public int compared;

    public DriftMonitor(double threshold) {
        this.threshold = threshold;
    }

    /** Returns true if this tick exceeds the threshold. */
    public boolean compare(int tick, LocalPlayer p, Snap s) {
        if (p == null || s == null) return false;
        Vec3 v = p.getDeltaMovement();
        lastDpos = Math.sqrt(sq(p.getX() - s.x()) + sq(p.getY() - s.y()) + sq(p.getZ() - s.z()));
        lastDvel = Math.sqrt(sq(v.x - s.vx()) + sq(v.y - s.vy()) + sq(v.z - s.vz()));
        lastDrot = Math.max(Math.abs(p.getYRot() - s.yRot()), Math.abs(p.getXRot() - s.xRot()));
        maxDpos = Math.max(maxDpos, lastDpos);
        compared++;
        boolean diverged = lastDpos > threshold;
        if (diverged && firstDivergentTick < 0) firstDivergentTick = tick;
        return diverged;
    }

    private static double sq(double d) {
        return d * d;
    }
}
