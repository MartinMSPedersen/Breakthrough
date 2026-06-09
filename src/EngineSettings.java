/**
 * Per-engine configuration: search depth, TT size, evaluator weights, and
 * defender scale. Value-like; instances are passed around and replaced
 * wholesale rather than mutated in place.
 *
 * The static `defaults()` factory mirrors the Search/Evaluator compiled-in
 * defaults and is what "Reset to default Settings" restores both engines to.
 */
public final class EngineSettings {

    public final int      depth;
    public final int      ttBits;
    public final int[]    weights;
    public final double   defenderScale;

    public EngineSettings(int depth, int ttBits, int[] weights, double defenderScale) {
        if (weights == null || weights.length != Board.SIZE)
            throw new IllegalArgumentException("Need " + Board.SIZE + " weights");
        this.depth         = depth;
        this.ttBits        = ttBits;
        this.weights       = weights.clone();
        this.defenderScale = defenderScale;
    }

    public static EngineSettings defaults() {
        return new EngineSettings(
            8,                                       // strong default; ~2-3s per move
            24,                                      // 16M entries, ~512 MB
            Evaluator.DEFAULT_WEIGHTS,
            Evaluator.DEFAULT_DEFENDER_SCALE
        );
    }

    /** Build the Evaluator these settings imply. */
    public Evaluator buildEvaluator() {
        return new Evaluator(weights, defenderScale);
    }

    /** Build a fresh Search using these settings. Each call returns a new
     *  Search so the TT isn't shared across moves — exactly the behavior we
     *  want when the engine plays from scratch on each turn. */
    public Search buildSearch() {
        return new Search(ttBits, buildEvaluator());
    }

    /** Comma-separated weight spec, e.g. "25,22,23,27,41,58,127,1000". */
    public String weightsSpec() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(weights[i]);
        }
        return sb.toString();
    }

    /** Parse a comma-separated weight spec. Throws IllegalArgumentException on bad input. */
    public static int[] parseWeights(String spec) {
        String[] parts = spec.split("\\s*,\\s*");
        if (parts.length != Board.SIZE) {
            throw new IllegalArgumentException(
                "Need " + Board.SIZE + " comma-separated weights, got " + parts.length);
        }
        int[] out = new int[Board.SIZE];
        for (int i = 0; i < Board.SIZE; i++) {
            try { out[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad weight at position " + i + ": " + parts[i]);
            }
        }
        return out;
    }

    @Override public String toString() {
        return "depth=" + depth + " ttBits=" + ttBits
             + " w=" + weightsSpec() + " ds=" + defenderScale;
    }
}
