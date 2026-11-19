package dev.helstera.ai;

/** A native dispatch decision made before predicate outcomes or their inversion. */
public final class ConditionDispatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean retained;

    public ConditionDispatchException(boolean retained) {
        super(null, null, false, false);
        this.retained = retained;
    }

    public boolean retained() { return retained; }
}
