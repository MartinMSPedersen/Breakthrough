/**
 * Negamax with alpha-beta pruning, iterative deepening, transposition table,
 * killer-move ordering, and quiescence search.
 *
 * Hot-path representation:
 *   - Moves are packed ints (see Move.pack): top 6 bits = from-square,
 *     bottom 6 bits = to-square. No Move-record allocation in the inner loop.
 *   - Per-ply move buffers are pre-allocated arrays owned by this Search
 *     instance. MoveGenerator.generate() fills them in place.
 *   - The TT stores packed-int best moves.
 *
 * Scoring is ply-indexed: a forced win/loss at distance P from the search
 * root scores WIN_SCORE - P (or negative). On TT store the score is
 * adjusted to be position-relative; on probe it's adjusted back. Without
 * this, mate distances would corrupt across TT hits at different plies.
 */
public final class Search {

    public static final class Result {
        public final Move bestMove;
        public final int  score;
        public final long nodes;
        public final int  depth;
        public final long ms;
        public Result(Move m, int s, long n, int d) { this(m, s, n, d, 0L); }
        public Result(Move m, int s, long n, int d, long elapsedMs) {
            bestMove = m; score = s; nodes = n; depth = d; ms = elapsedMs;
        }
    }

    /** Scores at or beyond this magnitude are treated as forced-mate distances. */
    private static final int MATE_THRESHOLD = Evaluator.WIN_SCORE - 1000;

    private static final int MAX_PLY = 128;

    private final TT        tt;
    private final Evaluator eval;
    private final int       noiseAmp;
    private final long      noiseSeed;
    private long nodes;

    /** Per-ply move buffers. Each ply gets its own slice so recursion doesn't
     *  alias buffers across depths. */
    private final int[][] moveBuf       = new int[MAX_PLY][MoveGenerator.MAX_MOVES];
    /** Per-ply scratch for move-ordering scores, aligned with moveBuf[ply]. */
    private final int[][] orderScoreBuf = new int[MAX_PLY][MoveGenerator.MAX_MOVES];
    /** Killer moves: two slots per ply. Move.NONE means empty. */
    private final int[][] killers       = new int[MAX_PLY][2];

    /** Triangular PV table: pv[ply] holds the PV starting at that ply.
     *  pv[ply][0] is the best move at this ply; pv[ply][1..] is the
     *  continuation. pvLen[ply] is how many slots are valid.
     *  Used to construct the full principal variation during search;
     *  more reliable than walking the TT (which loses PV when entries
     *  get overwritten by later, deeper, unrelated probes). */
    private final int[][] pvTable        = new int[MAX_PLY][MAX_PLY];
    private final int[]   pvLen          = new int[MAX_PLY];
    /** The PV from root after the last completed iteration. Used by extractPv. */
    private int[]         rootPv         = new int[0];

    /** Default TT size: 2^20 ≈ 1M slots; default evaluator weights; no noise. */
    public Search()                                       { this(20, Evaluator.defaults(), 0, 0L); }
    public Search(int ttBits)                             { this(ttBits, Evaluator.defaults(), 0, 0L); }
    public Search(Evaluator eval)                         { this(20, eval, 0, 0L); }
    public Search(int ttBits, Evaluator eval)             { this(ttBits, eval, 0, 0L); }
    public Search(int ttBits, Evaluator eval, int noiseAmp, long noiseSeed) {
        this.tt        = new TT(ttBits);
        this.eval      = eval;
        this.noiseAmp  = Math.max(0, noiseAmp);
        this.noiseSeed = noiseSeed;
    }

    public TT        tt()        { return tt; }
    public Evaluator evaluator() { return eval; }

    /**
     * Called by an external thread to request prompt termination. The
     * search checks this at iteration boundaries and at internal nodes;
     * it returns the best result from the *last completed* iteration,
     * never a partial one (which would have garbage at the abandoned
     * subtrees).
     *
     * Stored as a final reference so the JIT can hoist the read.
     */
    public interface CancelFlag { boolean isCancelled(); }
    private static final CancelFlag NEVER_CANCEL = () -> false;

    /** Per-iteration progress callback. Invoked on the search thread between
     *  iterations, never inside one. Receives the result of the iteration
     *  that just completed. */
    public interface IterationCallback { void onIteration(Result r); }
    private static final IterationCallback NO_CALLBACK = r -> {};

    /** Sentinel thrown from negamax when the cancel flag is set; caught in
     *  findBest to discard the partial iteration. Not a "real" error. */
    @SuppressWarnings("serial")
    private static final class CancelledException extends RuntimeException {
        CancelledException() { super(null, null, false, false); }
    }
    private static final CancelledException CANCEL_SENTINEL = new CancelledException();

    private CancelFlag        cancel    = NEVER_CANCEL;
    private IterationCallback callback  = NO_CALLBACK;
    /** Cancel-check throttle: only consult the flag every Nth node. */
    private static final int CANCEL_CHECK_MASK = 0xFFF;

    public Result findBest(Board b, int maxDepth) {
        return findBest(b, maxDepth, NEVER_CANCEL, NO_CALLBACK);
    }

    /**
     * Run iterative-deepening search. Returns the result of the deepest
     * completed iteration (never a partial one). If `cancel` becomes true
     * during a search iteration, that iteration is discarded and we return
     * whatever the previous iteration found.
     */
    public Result findBest(Board b, int maxDepth, CancelFlag cancel, IterationCallback cb) {
        clearKillers();
        this.cancel   = (cancel == null)   ? NEVER_CANCEL : cancel;
        this.callback = (cb == null)       ? NO_CALLBACK  : cb;

        int  bestMovePacked = Move.NONE;
        int  bestScore      = 0;
        long bestNodes      = 0;
        int  bestDepth      = 0;
        long totalNodes     = 0;
        long t0             = System.currentTimeMillis();
        for (int d = 1; d <= maxDepth; d++) {
            if (this.cancel.isCancelled()) break;
            nodes = 0;
            int score;
            try {
                score = negamax(b, d, 0, -Evaluator.MAX_SCORE, Evaluator.MAX_SCORE);
            } catch (CancelledException ce) {
                // Mid-iteration cancellation: discard this iteration entirely.
                // The TT has incomplete entries from this iteration which would
                // poison future searches, so we wipe it on cancel.
                tt.clear();
                break;
            }
            totalNodes += nodes;
            bestScore = score;
            bestNodes = totalNodes;
            bestDepth = d;
            TT.Entry rootE = tt.probe(b.hash());
            if (rootE != null) bestMovePacked = rootE.bestMove;
            // Snapshot the root PV produced by this iteration. We make a
            // copy because pvTable[0] gets overwritten by the next iteration.
            int rootLen = pvLen[0];
            rootPv = new int[rootLen];
            System.arraycopy(pvTable[0], 0, rootPv, 0, rootLen);
            // Report this iteration's result.
            Move iterMove = bestMovePacked == Move.NONE ? null : Move.unpack(bestMovePacked);
            long elapsed  = System.currentTimeMillis() - t0;
            this.callback.onIteration(new Result(iterMove, score, totalNodes, d, elapsed));
            if (Math.abs(score) >= MATE_THRESHOLD) break;
        }
        // Reset for safety in case the Search instance is reused.
        this.cancel   = NEVER_CANCEL;
        this.callback = NO_CALLBACK;
        Move bm = bestMovePacked == Move.NONE ? null : Move.unpack(bestMovePacked);
        return new Result(bm, bestScore, bestNodes, bestDepth,
                          System.currentTimeMillis() - t0);
    }

    private void clearKillers() {
        for (int i = 0; i < MAX_PLY; i++) {
            killers[i][0] = Move.NONE;
            killers[i][1] = Move.NONE;
        }
    }

    /**
     * Return the principal variation from the last completed iteration of
     * findBest, capped at maxLength. The PV is captured during the search
     * itself (triangular PV table) — far more reliable than walking the TT,
     * which loses entries to later, deeper, unrelated probes.
     *
     * The startingPosition parameter is currently unused but kept for API
     * compatibility; the PV is always relative to the root of the most
     * recent findBest call.
     */
    public java.util.List<Move> extractPv(Board startingPosition, int maxLength) {
        java.util.List<Move> pv = new java.util.ArrayList<>();
        int n = Math.min(maxLength, rootPv.length);
        for (int i = 0; i < n; i++) pv.add(Move.unpack(rootPv[i]));
        return pv;
    }

    /** Record a quiet move that caused a beta cutoff at this ply. */
    private void rememberKiller(int ply, int packedMove) {
        if (ply >= MAX_PLY) return;
        int slot0 = killers[ply][0];
        if (packedMove == slot0) return;
        killers[ply][1] = slot0;
        killers[ply][0] = packedMove;
    }

    /**
     * Returns the negamax value of position b at the given depth, side-to-move's
     * perspective. The best move (if any) is left in the TT under b.hash().
     */
    private int negamax(Board b, int depth, int ply, int alpha, int beta) {
        nodes++;
        // Cancellation check, throttled so we don't read the flag every node.
        if ((nodes & CANCEL_CHECK_MASK) == 0 && cancel.isCancelled()) {
            throw CANCEL_SENTINEL;
        }
        if (ply < MAX_PLY) pvLen[ply] = 0;   // assume no PV update at this ply yet
        final int  alphaOrig = alpha;
        final long hash      = b.hash();

        /* ----- TT probe ----- */
        int ttMove = Move.NONE;
        TT.Entry ttE = tt.probe(hash);
        if (ttE != null) {
            ttMove = ttE.bestMove;
            if (ttE.depth >= depth) {
                int ttScore = adjustMateFromTT(ttE.score, ply);
                switch (ttE.flag) {
                    case TT.EXACT -> { return ttScore; }
                    case TT.LOWER -> { if (ttScore > alpha) alpha = ttScore; }
                    case TT.UPPER -> { if (ttScore < beta)  beta  = ttScore; }
                }
                if (alpha >= beta) return ttScore;
            }
        }

        /* ----- Terminal / leaf ----- */
        byte winner = b.winner();
        if (winner != Board.EMPTY) {
            return (winner == b.side()) ?  (Evaluator.WIN_SCORE - ply)
                                        : -(Evaluator.WIN_SCORE - ply);
        }
        if (depth == 0) {
            return quiesce(b, alpha, beta, ply);
        }

        /* ----- Move generation ----- */
        int[] moves = moveBuf[ply];
        int   n     = MoveGenerator.generate(b, moves);
        if (n == 0) {
            // No moves = side to move loses by exhaustion.
            return -(Evaluator.WIN_SCORE - ply);
        }

        /* ----- Move ordering -----
         * Sort by orderScore() descending. Use a small in-place selection-style
         * pass: at iteration i, find the highest-scoring move among moves[i..n-1]
         * and swap to position i. Cheap for small n (≤ ~48 in this game) and
         * doesn't allocate. */
        final byte side    = b.side();
        final int  killer0 = (ply < MAX_PLY) ? killers[ply][0] : Move.NONE;
        final int  killer1 = (ply < MAX_PLY) ? killers[ply][1] : Move.NONE;
        int[] scores = orderScoreBuf[ply];
        for (int i = 0; i < n; i++) {
            scores[i] = orderScore(b, side, moves[i], ttMove, killer0, killer1);
        }

        /* ----- Search children with PVS -----
         *
         * Principal Variation Search: the first move (highest-ordered) gets
         * a full-window search. Every subsequent move gets a zero-window
         * "scout" search asking only "is this better than alpha?". If the
         * answer is yes (the scout returns > alpha), we re-search with the
         * full window to get the actual score. If no, we keep moving.
         *
         * Zero-window searches prune very aggressively, so when ordering is
         * good (which it usually is — TT move, captures, killers come first)
         * the scout-then-skip case dominates and the search is faster overall.
         *
         * IMPORTANT: a fail-high scout result is a BOUND (alpha+1 in
         * fail-hard), never an exact score, so it must ALWAYS be verified
         * by a full-window re-search — including at depth 1, where the
         * re-search is just a cheap windowed quiescence. An earlier version
         * skipped the re-search at depth 1 on the false assumption that
         * the zero-window value was "already final"; this recorded garbage
         * bound values as real scores and, in positions where the first
         * move loses to a forced win, produced false mate scores at the
         * root (each later scout "improving" the fake best by exactly +1,
         * the last sibling winning the bestMove slot). Found via a real
         * game where the engine chose a losing quiet move over the only
         * saving capture. Do not reintroduce the depth condition.
         */
        int bestMove  = moves[0];
        int bestScore = -Evaluator.MAX_SCORE;
        boolean searchedPV = false;
        for (int i = 0; i < n; i++) {
            // Find the highest-scoring remaining move; swap it to position i.
            int maxIdx = i;
            int maxVal = scores[i];
            for (int j = i + 1; j < n; j++) {
                if (scores[j] > maxVal) { maxVal = scores[j]; maxIdx = j; }
            }
            if (maxIdx != i) {
                int tm = moves[i];  moves[i] = moves[maxIdx];  moves[maxIdx] = tm;
                int ts = scores[i]; scores[i] = scores[maxIdx]; scores[maxIdx] = ts;
            }

            int m   = moves[i];
            byte cap = b.applyPacked(m);

            int s;
            if (!searchedPV) {
                // First move: full window.
                s = -negamax(b, depth - 1, ply + 1, -beta, -alpha);
                searchedPV = true;
            } else {
                // Scout search with a null (zero-width) window.
                s = -negamax(b, depth - 1, ply + 1, -alpha - 1, -alpha);
                if (s > alpha && s < beta) {
                    // Scout failed high but didn't already cause a beta cutoff:
                    // re-search with the full window to get the exact value.
                    s = -negamax(b, depth - 1, ply + 1, -beta, -alpha);
                }
            }

            b.undoPacked(m, cap);

            if (s > bestScore) { bestScore = s; bestMove = m; }
            if (s > alpha) {
                alpha = s;
                // PV update: record this move + the child's PV at this ply.
                if (ply < MAX_PLY) {
                    pvTable[ply][0] = m;
                    int childLen = (ply + 1 < MAX_PLY) ? pvLen[ply + 1] : 0;
                    if (childLen > MAX_PLY - 1 - ply) childLen = MAX_PLY - 1 - ply;
                    for (int k = 0; k < childLen; k++) {
                        pvTable[ply][k + 1] = pvTable[ply + 1][k];
                    }
                    pvLen[ply] = childLen + 1;
                }
            }
            if (alpha >= beta) {
                if (cap == Board.EMPTY) rememberKiller(ply, m);
                break;
            }
        }

        /* ----- TT store ----- */
        byte flag;
        if      (bestScore <= alphaOrig) flag = TT.UPPER;
        else if (bestScore >= beta)      flag = TT.LOWER;
        else                              flag = TT.EXACT;
        tt.store(hash, depth, adjustMateToTT(bestScore, ply), flag, bestMove);

        return bestScore;
    }

    /**
     * Quiescence search at a leaf: extend only along captures until quiet,
     * then return static eval. Stand-pat allowed (captures are optional).
     */
    private int quiesce(Board b, int alpha, int beta, int ply) {
        nodes++;

        byte winner = b.winner();
        if (winner != Board.EMPTY) {
            return (winner == b.side()) ?  (Evaluator.WIN_SCORE - ply)
                                        : -(Evaluator.WIN_SCORE - ply);
        }
        if (ply >= MAX_PLY) return leafEval(b);

        int standPat = leafEval(b);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        int[] caps = moveBuf[ply];
        // Captures AND quiet winning pushes (moves to the goal rank). A
        // capture-only quiescence is unsound in Breakthrough: the winning
        // move is usually quiet, so a runner one step from the goal would
        // be invisible to the search at the horizon.
        int   n    = MoveGenerator.generateQuiescence(b, caps);
        if (n == 0) return alpha;

        // Simple ordering: by destination advancement (closer to home row = better).
        final byte side = b.side();
        int[] scores = orderScoreBuf[ply];
        for (int i = 0; i < n; i++) scores[i] = advanceBonusPacked(side, caps[i]);

        for (int i = 0; i < n; i++) {
            int maxIdx = i, maxVal = scores[i];
            for (int j = i + 1; j < n; j++) {
                if (scores[j] > maxVal) { maxVal = scores[j]; maxIdx = j; }
            }
            if (maxIdx != i) {
                int tm = caps[i];   caps[i] = caps[maxIdx];   caps[maxIdx] = tm;
                int ts = scores[i]; scores[i] = scores[maxIdx]; scores[maxIdx] = ts;
            }

            int  m   = caps[i];
            byte cap = b.applyPacked(m);
            int  score = -quiesce(b, -beta, -alpha, ply + 1);
            b.undoPacked(m, cap);

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    /* ----- Move ordering ----- */

    /** Order: TT move > captures > killer0 > killer1 > advancement. */
    private static int orderScore(Board b, byte side, int packedMove,
                                  int ttMove, int killer0, int killer1) {
        if (packedMove == ttMove) return 1_000_000;
        int toSq = packedMove & 0x3F;
        // Capture if opponent piece sits on toSq.
        long oppBits = (side == Board.WHITE) ? b.blackBits() : b.whiteBits();
        boolean capture = ((oppBits >>> toSq) & 1L) != 0L;
        if (capture)              return 10_000 + advanceBonusPacked(side, packedMove);
        if (packedMove == killer0) return 900;
        if (packedMove == killer1) return 800;
        return advanceBonusPacked(side, packedMove);
    }

    private static int advanceBonusPacked(byte side, int packedMove) {
        int toRow   = (packedMove & 0x3F) >>> 3;
        int homeRow = (side == Board.WHITE) ? Board.SIZE - 1 : 0;
        return 100 - Math.abs(homeRow - toRow) * 10;
    }

    /* ----- Mate-score adjustment around the TT ----- */

    private static int adjustMateToTT(int score, int ply) {
        if (score >=  MATE_THRESHOLD) return score + ply;
        if (score <= -MATE_THRESHOLD) return score - ply;
        return score;
    }
    private static int adjustMateFromTT(int score, int ply) {
        if (score >=  MATE_THRESHOLD) return score - ply;
        if (score <= -MATE_THRESHOLD) return score + ply;
        return score;
    }

    /* ----- Leaf evaluation with optional deterministic noise ----- */

    private int leafEval(Board b) {
        int s = eval.evaluate(b);
        if (noiseAmp == 0) return s;
        long h = splitmix64(b.hash() ^ noiseSeed);
        int span = 2 * noiseAmp + 1;
        int n    = (int) Math.floorMod(h, span) - noiseAmp;
        return s + n;
    }

    private static long splitmix64(long x) {
        x += 0x9E3779B97F4A7C15L;
        x  = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x  = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return  x ^ (x >>> 31);
    }
}
