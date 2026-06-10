import java.util.ArrayList;
import java.util.List;

/**
 * Bitboard move generator.
 *
 * Two API styles are provided:
 *
 * 1. **Buffer-filling**, used by the Search hot path:
 *      int n = MoveGenerator.generate(board, intBuffer);
 *    fills the buffer with up to ~48 packed-int moves and returns the count.
 *    Zero allocation per call; the buffer is owned by the Search instance.
 *
 * 2. **Allocating**, used by the CLI and benchmark:
 *      List<Move> moves = MoveGenerator.legalMoves(board);
 *    convenient when you want to iterate, sort, or print; allocates a fresh
 *    ArrayList and a Move record per move.
 *
 * Both share the same underlying bitboard algorithm: for each of the three
 * move types (forward, diag-left, diag-right), compute the destination
 * bitboard with a couple of shifts/masks, then iterate set bits.
 */
public final class MoveGenerator {

    private MoveGenerator() {}

    /** Conservative upper bound on legal moves in any 8x8 position. 48 is the
     *  theoretical max (16 pieces × 3 directions); 64 leaves slack. */
    public static final int MAX_MOVES = 64;

    private static final long NOT_FILE_A = ~Bitboards.FILE_A;
    private static final long NOT_FILE_H = ~Bitboards.FILE_H;

    /* --------------- Buffer-filling (hot path) --------------- */

    /**
     * Fill `out` with packed-int moves for the side to move on `b`.
     * Returns the number of moves written. Does no allocation.
     */
    public static int generate(Board b, int[] out) {
        return generate(b, out, 0, false);
    }

    /** As above, but emits only captures (diagonal-forward onto opponent piece). */
    public static int generateCaptures(Board b, int[] out) {
        return generate(b, out, 0, true);
    }

    /**
     * Quiescence move set: captures PLUS quiet "winning pushes" — non-capture
     * moves landing on the opponent's home rank. Reaching that rank wins the
     * game instantly, so these are the Breakthrough equivalent of promotions
     * in chess quiescence. A capture-only quiescence is blind to them: a piece
     * one step from the goal "looks" harmless unless its winning move happens
     * to be a capture, which causes severe horizon artifacts (the engine sees
     * the capture-win g7xh8 but not the quiet win g7-h8, mis-scoring entire
     * subtrees). Found via a real game blunder.
     */
    public static int generateQuiescence(Board b, int[] out) {
        int idx = generate(b, out, 0, true);   // all captures first
        final long own   = (b.side() == Board.WHITE) ? b.whiteBits() : b.blackBits();
        final long opp   = (b.side() == Board.WHITE) ? b.blackBits() : b.whiteBits();
        final long empty = ~(own | opp);
        if (b.side() == Board.WHITE) {
            final long GOAL = 0xFF00000000000000L;          // rank 8 (rows are 0-based from rank 1)
            long fwd   = ((own << 8)                & empty) & GOAL;
            long diagL = (((own & NOT_FILE_A) << 7) & empty) & GOAL;
            long diagR = (((own & NOT_FILE_H) << 9) & empty) & GOAL;
            idx = emit(out, idx, fwd,   -8);
            idx = emit(out, idx, diagL, -7);
            idx = emit(out, idx, diagR, -9);
        } else {
            final long GOAL = 0xFFL;                        // rank 1
            long fwd   = ((own >>> 8)                & empty) & GOAL;
            long diagL = (((own & NOT_FILE_H) >>> 7) & empty) & GOAL;
            long diagR = (((own & NOT_FILE_A) >>> 9) & empty) & GOAL;
            idx = emit(out, idx, fwd,   +8);
            idx = emit(out, idx, diagL, +7);
            idx = emit(out, idx, diagR, +9);
        }
        return idx;
    }

    private static int generate(Board b, int[] out, int offset, boolean capturesOnly) {
        final long own  = (b.side() == Board.WHITE) ? b.whiteBits() : b.blackBits();
        final long opp  = (b.side() == Board.WHITE) ? b.blackBits() : b.whiteBits();
        final long empty = ~(own | opp);

        int idx = offset;
        if (b.side() == Board.WHITE) {
            // Diagonals: empty or opponent (for legal) or opponent only (for captures)
            long diagTargetsL = capturesOnly ? opp : (empty | opp);
            long diagTargetsR = capturesOnly ? opp : (empty | opp);
            if (!capturesOnly) {
                long fwd = (own << 8) & empty;
                idx = emit(out, idx, fwd, -8);
            }
            long diagL = ((own & NOT_FILE_A) << 7) & diagTargetsL;
            long diagR = ((own & NOT_FILE_H) << 9) & diagTargetsR;
            idx = emit(out, idx, diagL, -7);
            idx = emit(out, idx, diagR, -9);
        } else {
            long diagTargetsL = capturesOnly ? opp : (empty | opp);
            long diagTargetsR = capturesOnly ? opp : (empty | opp);
            if (!capturesOnly) {
                long fwd = (own >>> 8) & empty;
                idx = emit(out, idx, fwd, +8);
            }
            long diagL = ((own & NOT_FILE_H) >>> 7) & diagTargetsL;
            long diagR = ((own & NOT_FILE_A) >>> 9) & diagTargetsR;
            idx = emit(out, idx, diagL, +7);
            idx = emit(out, idx, diagR, +9);
        }
        return idx - offset;
    }

    /**
     * For each set bit in `destinations`, pack a (from, to) move and write it
     * to `out[idx++]`, where from = to + fromOffset (offset is negative for
     * White moves, positive for Black).
     */
    private static int emit(int[] out, int idx, long destinations, int fromOffset) {
        while (destinations != 0L) {
            int toSq   = Long.numberOfTrailingZeros(destinations);
            int fromSq = toSq + fromOffset;
            out[idx++] = (fromSq << 6) | toSq;
            destinations &= destinations - 1L;
        }
        return idx;
    }

    /* --------------- Allocating (CLI/benchmark) --------------- */

    /** Convenience: returns a fresh List<Move>. Allocates an ArrayList and a
     *  Move record per legal move. Used outside the search hot path. */
    public static List<Move> legalMoves(Board b) {
        int[] buf = new int[MAX_MOVES];
        int n = generate(b, buf);
        List<Move> moves = new ArrayList<>(n);
        for (int i = 0; i < n; i++) moves.add(Move.unpack(buf[i]));
        return moves;
    }

    /** Convenience: returns a fresh List<Move> of capture moves only. */
    public static List<Move> captureMoves(Board b) {
        int[] buf = new int[MAX_MOVES];
        int n = generateCaptures(b, buf);
        List<Move> moves = new ArrayList<>(n);
        for (int i = 0; i < n; i++) moves.add(Move.unpack(buf[i]));
        return moves;
    }
}
