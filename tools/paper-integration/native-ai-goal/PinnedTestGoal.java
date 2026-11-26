package dev.helstera.test.randomspawn;

import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;

/** Fixture-only native Goal used to observe the real scheduler's interruption and stop behavior. */
public final class PinnedTestGoal extends Goal {
    public int starts, stops;
    private final boolean interruptible;
    public PinnedTestGoal(net.minecraft.world.entity.Mob mob, boolean interruptible) {
        this.interruptible=interruptible;
        setFlags(EnumSet.of(Flag.TARGET));
    }
    @Override public boolean canUse(){return true;}
    @Override public boolean canContinueToUse(){return true;}
    @Override public boolean isInterruptable(){return interruptible;}
    @Override public void start(){starts++;}
    @Override public void stop(){stops++;}
}
