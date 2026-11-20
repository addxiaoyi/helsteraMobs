package dev.helstera.ai;

/** Stops the current skill's remaining actions without cancelling sibling skills. */
public final class SkillActionAbortException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SkillActionAbortException() {
        super("Target variable registry is unavailable", null, false, false);
    }
}
