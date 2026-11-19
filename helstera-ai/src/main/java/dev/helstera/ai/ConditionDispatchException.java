package dev.helstera.ai;

/** A native dispatch/evaluation decision made before predicate outcomes or their inversion. */
public final class ConditionDispatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean retained;
    private final boolean abortsTargetSet;

    public ConditionDispatchException(boolean retained) {
        this(retained, false);
    }

    private ConditionDispatchException(boolean retained, boolean abortsTargetSet) {
        super(null, null, false, false);
        this.retained = retained;
        this.abortsTargetSet = abortsTargetSet;
    }

    public boolean retained() { return retained; }

    public boolean abortsTargetSet() { return abortsTargetSet; }

    /** Native evaluateTargets catches a predicate error outside the whole target-set filter. */
    public static ConditionDispatchException evaluationFailure() {
        return new ConditionDispatchException(false, true);
    }
}
