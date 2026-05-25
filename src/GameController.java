import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Game flow controller. Holds the live `Board`, the game mode, and the
 * history of played moves. Receives clicks from the BoardPanel, decides
 * whether they're legal, and applies them. When it's the engine's turn,
 * runs the search on a background SwingWorker so the EDT stays responsive.
 *
 * Concurrency:
 *   - Everything that touches `board`, `playedMoves`, `selectedSq` must
 *     happen on the EDT.
 *   - The engine search runs on a SwingWorker. It gets a *clone* of the
 *     current Board (by FEN round-trip) so the EDT can keep painting the
 *     real board without aliasing.
 *   - thinkingNow is set/cleared on the EDT and read by isThinking().
 */
public class GameController {

    /** Who controls each side. */
    public enum Side  { HUMAN, ENGINE }

    public interface Listener {
        /** Board state changed (move applied/undone, new game, etc). */
        void boardChanged(Board newBoard, int lastFromSq, int lastToSq);
        /** Status line should update (e.g. "White to move", "engine thinking...", "game over"). */
        void statusChanged(String text);
        /** Engine progress: best move, score, depth, nodes, ms. */
        void engineProgress(String line);
        /** Game ended; result describes who won and how. */
        void gameOver(String result);
    }

    /* ----- state ----- */
    private Board board;
    private final List<Move> playedMoves = new ArrayList<>();
    private int  lastFromSq = -1, lastToSq = -1;

    private Side whiteSide = Side.HUMAN;
    private Side blackSide = Side.ENGINE;
    /** Per-side engine settings (depth, TT, weights, defender scale).
     *  Whichever side the engine controls reads its settings here. */
    private EngineSettings whiteSettings = EngineSettings.defaults();
    private EngineSettings blackSettings = EngineSettings.defaults();

    /** True between SwingWorker.start and done. EDT-only. */
    private boolean thinkingNow = false;
    /**
     * Generation token. Bumped whenever state is reset (newGame, setSides,
     * loadGame, loadPosition). A worker snapshots the current generation at
     * launch and discards its result on `done()` if the generation has moved
     * on — meaning the world it was reasoning about no longer exists.
     *
     * This is more robust than a single cancel flag because it survives the
     * cancel→reset-flag cycle: even if newGame() clears any signal before the
     * old worker's done() runs, the worker still sees that its generation is
     * stale and discards.
     */
    private long currentGeneration = 0L;

    /** For human play: currently selected source square, or -1. */
    private int  selectedSq = -1;
    /** Legal-move destinations from selectedSq, as a bitboard. */
    private long destinations = 0L;

    private Listener listener;

    public GameController() {
        this.board = Board.initial();
    }

    public void setListener(Listener l) { this.listener = l; }

    /* ----- public API used by Gui ----- */

    public void newGame() {
        currentGeneration++;
        thinkingNow = false;
        board = Board.initial();
        playedMoves.clear();
        lastFromSq = -1; lastToSq = -1;
        selectedSq = -1; destinations = 0L;
        
        fireBoardChanged();
        updateStatus();
        maybeKickEngine();
    }

    /**
     * Replace state with a game loaded from disk: replay the moves from the
     * starting position, then continue from the resulting position. Throws
     * IllegalArgumentException if any move in the list is illegal at its turn.
     */
    public void loadGame(java.util.List<Move> moves) {
        currentGeneration++;
        thinkingNow = false;
        Board b = Board.initial();
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            int  fromSq = m.fromRow() * 8 + m.fromCol();
            int  toSq   = m.toRow()   * 8 + m.toCol();
            int  packed = (fromSq << 6) | toSq;
            // Verify move is legal at this turn.
            int[] buf = new int[MoveGenerator.MAX_MOVES];
            int n = MoveGenerator.generate(b, buf);
            boolean legal = false;
            for (int j = 0; j < n; j++) if (buf[j] == packed) { legal = true; break; }
            if (!legal) {
                throw new IllegalArgumentException("Illegal move at ply " + (i + 1) + ": " + m);
            }
            b.apply(m);
            if (b.winner() != Board.EMPTY && i < moves.size() - 1) {
                throw new IllegalArgumentException("Game ended at ply " + (i + 1)
                                                 + " but file has more moves");
            }
        }
        board = b;
        playedMoves.clear();
        playedMoves.addAll(moves);
        if (!moves.isEmpty()) {
            Move last = moves.get(moves.size() - 1);
            lastFromSq = last.fromRow() * 8 + last.fromCol();
            lastToSq   = last.toRow()   * 8 + last.toCol();
        } else {
            lastFromSq = -1; lastToSq = -1;
        }
        selectedSq = -1; destinations = 0L;
        
        fireBoardChanged();
        updateStatus();
        byte w = board.winner();
        if (w != Board.EMPTY) {
            fireGameOver((w == Board.WHITE ? "White" : "Black") + " wins on move "
                       + ((playedMoves.size() + 1) / 2));
        } else {
            maybeKickEngine();
        }
    }

    /**
     * Replace state with a position loaded from disk. The played-move history
     * is cleared (we don't know what moves led to this position).
     */
    public void loadPosition(Board newBoard) {
        currentGeneration++;
        thinkingNow = false;
        board = newBoard;
        playedMoves.clear();
        lastFromSq = -1; lastToSq = -1;
        selectedSq = -1; destinations = 0L;
        
        fireBoardChanged();
        updateStatus();
        byte w = board.winner();
        if (w != Board.EMPTY) {
            fireGameOver((w == Board.WHITE ? "White" : "Black") + " wins");
        } else {
            maybeKickEngine();
        }
    }

    public void setSides(Side white, Side black) {
        currentGeneration++;
        thinkingNow = false;
        whiteSide = white;
        blackSide = black;
        
        updateStatus();
        maybeKickEngine();
    }

    public Side whiteSide() { return whiteSide; }
    public Side blackSide() { return blackSide; }

    public EngineSettings whiteSettings() { return whiteSettings; }
    public EngineSettings blackSettings() { return blackSettings; }
    public void setWhiteSettings(EngineSettings s) { this.whiteSettings = s; }
    public void setBlackSettings(EngineSettings s) { this.blackSettings = s; }
    public void resetSettings() {
        this.whiteSettings = EngineSettings.defaults();
        this.blackSettings = EngineSettings.defaults();
    }

    public Board board() { return board; }
    public int   selectedSq()   { return selectedSq; }
    public long  destinations() { return destinations; }
    public int   lastFromSq()   { return lastFromSq; }
    public int   lastToSq()     { return lastToSq; }
    public List<Move> playedMoves() { return playedMoves; }
    public boolean isThinking() { return thinkingNow; }

    /* ----- click handling ----- */

    /**
     * Called by BoardPanel when the user clicks a square. The controller
     * decides what it means: select a piece, deselect, or play a move.
     */
    public void onClick(int row, int col) {
        if (thinkingNow) return;                       // ignore clicks while engine thinks
        if (board.winner() != Board.EMPTY) return;     // game over
        if (sideToMoveIsEngine()) return;              // not the human's turn

        int clickedSq = row * 8 + col;
        byte stm = board.side();
        byte pieceHere = board.get(row, col);

        if (selectedSq < 0) {
            // Selecting: must click own piece with at least one legal move.
            if (pieceHere == stm) {
                long dests = destinationsFor(selectedSq = clickedSq);
                if (dests == 0L) { selectedSq = -1; return; }
                destinations = dests;
                fireBoardChanged();
            }
            return;
        }

        // Something is already selected.
        if (clickedSq == selectedSq) {
            // Clicking the selected square again deselects.
            selectedSq = -1; destinations = 0L;
            fireBoardChanged();
            return;
        }
        if (pieceHere == stm) {
            // Clicking another own piece: switch selection.
            selectedSq = clickedSq;
            destinations = destinationsFor(selectedSq);
            fireBoardChanged();
            return;
        }
        if (((destinations >>> clickedSq) & 1L) != 0L) {
            // Legal destination — play the move.
            int fromRow = selectedSq >>> 3, fromCol = selectedSq & 7;
            Move m = new Move(fromRow, fromCol, row, col);
            applyMove(m);
            return;
        }
        // Click on an empty/illegal square: clear selection.
        selectedSq = -1; destinations = 0L;
        fireBoardChanged();
    }

    /* ----- move application ----- */

    /** Find all legal destinations from a given source square. */
    private long destinationsFor(int srcSq) {
        long bb = 0L;
        int[] buf = new int[MoveGenerator.MAX_MOVES];
        int n = MoveGenerator.generate(board, buf);
        for (int i = 0; i < n; i++) {
            if (Move.fromSq(buf[i]) == srcSq) bb |= 1L << Move.toSq(buf[i]);
        }
        return bb;
    }

    private void applyMove(Move m) {
        board.apply(m);
        playedMoves.add(m);
        lastFromSq = m.fromRow() * 8 + m.fromCol();
        lastToSq   = m.toRow()   * 8 + m.toCol();
        selectedSq = -1; destinations = 0L;
        fireBoardChanged();

        // Check for game end.
        byte w = board.winner();
        if (w != Board.EMPTY) {
            fireGameOver((w == Board.WHITE ? "White" : "Black") + " wins on move "
                       + ((playedMoves.size() + 1) / 2));
            updateStatus();
            return;
        }
        // No-moves: opponent loses by exhaustion.
        if (MoveGenerator.legalMoves(board).isEmpty()) {
            byte loser = board.side();
            String winnerName = (loser == Board.WHITE) ? "Black" : "White";
            fireGameOver(winnerName + " wins by elimination on move "
                       + ((playedMoves.size() + 1) / 2));
            updateStatus();
            return;
        }
        updateStatus();
        maybeKickEngine();
    }

    private boolean sideToMoveIsEngine() {
        return (board.side() == Board.WHITE ? whiteSide : blackSide) == Side.ENGINE;
    }

    /* ----- engine thread ----- */

    /**
     * If the side to move is an engine and we're not already thinking,
     * launch a background search. Must be called on the EDT.
     */
    private void maybeKickEngine() {
        if (!sideToMoveIsEngine()) return;
        if (thinkingNow) return;
        if (board.winner() != Board.EMPTY) return;

        // Capture the current state for the worker — clone via FEN so the
        // worker's mutations don't alias the EDT-owned board.
        final String fen = board.toFen();
        final EngineSettings cfg = (board.side() == Board.WHITE) ? whiteSettings : blackSettings;
        final long generation = currentGeneration;
        thinkingNow = true;
        updateStatus();

        SwingWorker<Move, String> worker = new SwingWorker<>() {
            @Override protected Move doInBackground() {
                Board copy = Board.fromFen(fen);
                Search s = cfg.buildSearch();
                long t0 = System.currentTimeMillis();
                Search.Result r = s.findBest(copy, cfg.depth);
                long ms = System.currentTimeMillis() - t0;
                publish(String.format("depth=%d  best=%s  score=%+d  nodes=%d  %d ms",
                                       r.depth, r.bestMove, r.score, r.nodes, ms));
                return r.bestMove;
            }
            @Override protected void process(List<String> lines) {
                // Suppress progress lines from stale workers — they'd corrupt
                // the engine-output panel by reporting about a now-defunct
                // position.
                if (generation != currentGeneration) return;
                if (listener != null) for (String l : lines) listener.engineProgress(l);
            }
            @Override protected void done() {
                thinkingNow = false;
                if (generation != currentGeneration) { updateStatus(); return; }
                try {
                    Move m = get();
                    if (m != null) applyMove(m);
                } catch (Exception ex) {
                    if (listener != null) listener.statusChanged("Engine error: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    /* ----- listener helpers ----- */

    private void fireBoardChanged() {
        if (listener != null) listener.boardChanged(board, lastFromSq, lastToSq);
    }
    private void fireGameOver(String result) {
        if (listener != null) listener.gameOver(result);
    }
    private void updateStatus() {
        if (listener == null) return;
        byte w = board.winner();
        if (w != Board.EMPTY) {
            listener.statusChanged((w == Board.WHITE ? "White" : "Black") + " wins.");
            return;
        }
        String stm   = (board.side() == Board.WHITE) ? "White" : "Black";
        String controller = (sideToMoveIsEngine() ? "engine" : "human");
        if (thinkingNow) {
            int d = (board.side() == Board.WHITE) ? whiteSettings.depth : blackSettings.depth;
            listener.statusChanged("Engine thinking (" + stm + ", depth " + d + ")...");
        }
        else listener.statusChanged(stm + " to move (" + controller + ")");
    }
}
