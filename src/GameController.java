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
    /** PLAY = normal game (sides drive moves). ANALYSE = engine searches the
     *  current position continuously; the user can move pieces for either
     *  side to explore variations.
     *
     *  ANNOTATE = walk through a previously-played game ply by ply. Engine
     *  analyzes each position in the background; results are cached so
     *  re-visiting a ply is instant. Click-to-move is disabled (you're
     *  inspecting history). */
    public enum Mode  { PLAY, ANALYSE, ANNOTATE }

    public interface Listener {
        /** Board state changed (move applied/undone, new game, etc). */
        void boardChanged(Board newBoard, int lastFromSq, int lastToSq);
        /** Status line should update (e.g. "White to move", "engine thinking...", "game over"). */
        void statusChanged(String text);
        /** Engine progress: which side (Board.WHITE/BLACK) and a free-form line. */
        void engineProgress(byte side, String line);
        /** Annotate-only: a completed ply analysis. Includes the played move
         *  for that ply, the engine's best move, and whether they agree. */
        default void annotateResult(int ply, Move played, Search.Result engineResult, boolean agrees) {}
        /** Annotate state changed (current ply, total plies). Triggers toolbar updates. */
        default void annotateStateChanged(int ply, int totalPlies) {}
        /** Play / Two Machines: an engine search just completed and its move
         *  is about to be applied. ply is 1-based (the ply about to be played).
         *  side is the side whose move it is. Useful for graphing evaluation. */
        default void engineMoveCompleted(int ply, byte side, Search.Result r) {}
        /** Game ended; result describes who won and how. */
        void gameOver(String result);
    }

    /* ----- state ----- */
    private Board board;
    private final List<Move> playedMoves = new ArrayList<>();
    private int  lastFromSq = -1, lastToSq = -1;

    private Side whiteSide = Side.HUMAN;
    private Side blackSide = Side.ENGINE;
    private Mode mode      = Mode.PLAY;
    /** Per-side engine settings (depth, TT, weights, defender scale).
     *  Whichever side the engine controls reads its settings here. */
    private EngineSettings whiteSettings = EngineSettings.defaults();
    private EngineSettings blackSettings = EngineSettings.defaults();
    /** Maximum depth used by Analyse Mode. Capped to prevent runaway memory. */
    private int analyseMaxDepth = 14;
    /** Cancel flag for the currently running Analyse search, if any. The
     *  controller sets this true to stop the running analysis, then clears
     *  it (allocates a fresh AtomicBoolean) before starting a new one. */
    private java.util.concurrent.atomic.AtomicBoolean analyseCancel
        = new java.util.concurrent.atomic.AtomicBoolean();

    /** Annotate state. annotateMoves holds the full sequence of plies from the
     *  loaded game. annotatePly is the index of the *next* ply to be played
     *  (0 = starting position before any move; 1 = position after white's
     *  first move; etc). annotateCache maps each ply index ≥ 1 to the
     *  engine's analysis of the position *before* that ply (i.e. the position
     *  from which the played move was chosen). */
    private java.util.List<Move>             annotateMoves  = new java.util.ArrayList<>();
    private int                              annotatePly    = 0;
    private final java.util.Map<Integer, Search.Result> annotateCache = new java.util.HashMap<>();
    private java.util.concurrent.atomic.AtomicBoolean annotateCancel
        = new java.util.concurrent.atomic.AtomicBoolean();

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
        if (mode == Mode.ANNOTATE) leaveAnnotate();
        stopAnalyse();
        currentGeneration++;
        thinkingNow = false;
        board = Board.initial();
        playedMoves.clear();
        lastFromSq = -1; lastToSq = -1;
        selectedSq = -1; destinations = 0L;
        
        fireBoardChanged();
        updateStatus();
        if (mode == Mode.ANALYSE) startAnalyse();
        else                       maybeKickEngine();
    }

    /**
     * Replace state with a game loaded from disk: replay the moves from the
     * starting position, then continue from the resulting position. Throws
     * IllegalArgumentException if any move in the list is illegal at its turn.
     */
    public void loadGame(java.util.List<Move> moves) {
        if (mode == Mode.ANNOTATE) leaveAnnotate();
        stopAnalyse();
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
            if (mode == Mode.ANALYSE) startAnalyse();
            else                       maybeKickEngine();
        }
    }

    /**
     * Replace state with a position loaded from disk. The played-move history
     * is cleared (we don't know what moves led to this position).
     */
    public void loadPosition(Board newBoard) {
        if (mode == Mode.ANNOTATE) leaveAnnotate();
        stopAnalyse();
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
            if (mode == Mode.ANALYSE) startAnalyse();
            else                       maybeKickEngine();
        }
    }

    public void setSides(Side white, Side black) {
        if (mode == Mode.ANNOTATE) leaveAnnotate();
        stopAnalyse();
        currentGeneration++;
        thinkingNow = false;
        whiteSide = white;
        blackSide = black;
        
        updateStatus();
        if (mode == Mode.ANALYSE) startAnalyse();
        else                       maybeKickEngine();
    }

    public Side whiteSide() { return whiteSide; }
    public Side blackSide() { return blackSide; }
    public Mode mode()      { return mode; }

    /** Switch into Analyse Mode: stop any thinking, start a continuous search
     *  on the current position. The user can still click pieces to move
     *  (for either side). Switching out of Analyse cancels the search.
     *
     *  Note: switching into ANNOTATE mode this way is invalid (no game
     *  loaded); use enterAnnotate(List<Move>) instead. Switching *out* of
     *  ANNOTATE to another mode is fine. */
    public void setMode(Mode newMode) {
        if (mode == newMode) return;
        if (newMode == Mode.ANNOTATE) {
            throw new IllegalStateException(
                "Use enterAnnotate(moves) to switch into ANNOTATE mode");
        }
        Mode oldMode = mode;
        mode = newMode;
        stopAnalyse();              // always cancel any running analysis first
        stopAnnotate();             // and any running annotate analysis
        currentGeneration++;        // invalidates any normal-play workers in flight
        thinkingNow = false;
        if (oldMode == Mode.ANNOTATE) {
            // Leaving Annotate: reset to a fresh starting position.
            annotateMoves.clear();
            annotateCache.clear();
            annotatePly = 0;
            board = Board.initial();
            playedMoves.clear();
            lastFromSq = -1; lastToSq = -1;
            fireBoardChanged();
            fireAnnotateStateChanged();
        }
        if (mode == Mode.ANALYSE) {
            startAnalyse();
        } else {
            maybeKickEngine();
        }
        updateStatus();
    }

    /**
     * Enter Annotate Mode with the given sequence of moves. Validates that
     * each move is legal at its turn; throws IllegalArgumentException if not
     * (with the offending ply number). Starts at ply 0 (the initial position
     * before any move has been played).
     */
    public void enterAnnotate(java.util.List<Move> moves) {
        // Validate by replaying onto a scratch board.
        Board scratch = Board.initial();
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            int packed = (m.fromRow() * 8 + m.fromCol()) << 6 | (m.toRow() * 8 + m.toCol());
            int[] buf = new int[MoveGenerator.MAX_MOVES];
            int n = MoveGenerator.generate(scratch, buf);
            boolean legal = false;
            for (int j = 0; j < n; j++) if (buf[j] == packed) { legal = true; break; }
            if (!legal) throw new IllegalArgumentException("Illegal move at ply " + (i + 1) + ": " + m);
            scratch.apply(m);
        }
        // All moves legal — switch state atomically.
        stopAnalyse();
        stopAnnotate();
        currentGeneration++;
        thinkingNow = false;
        mode = Mode.ANNOTATE;
        annotateMoves = new java.util.ArrayList<>(moves);
        annotateCache.clear();
        annotatePly = 0;
        board = Board.initial();
        playedMoves.clear();
        lastFromSq = -1; lastToSq = -1;
        selectedSq = -1; destinations = 0L;
        fireBoardChanged();
        fireAnnotateStateChanged();
        updateStatus();
        // If there's a next ply to analyse, start.
        kickAnnotateIfNeeded();
    }

    /** Step to a specific ply index. 0 = starting position; N = after Nth ply.
     *  Out-of-range values are clamped. */
    public void annotateGoto(int newPly) {
        if (mode != Mode.ANNOTATE) return;
        newPly = Math.max(0, Math.min(newPly, annotateMoves.size()));
        if (newPly == annotatePly) return;
        stopAnnotate();
        currentGeneration++;
        // Rebuild board at the requested ply.
        Board b = Board.initial();
        for (int i = 0; i < newPly; i++) b.apply(annotateMoves.get(i));
        board = b;
        annotatePly = newPly;
        // Last-move highlight on the move just played, if any.
        if (newPly > 0) {
            Move m = annotateMoves.get(newPly - 1);
            lastFromSq = m.fromRow() * 8 + m.fromCol();
            lastToSq   = m.toRow()   * 8 + m.toCol();
        } else {
            lastFromSq = -1; lastToSq = -1;
        }
        selectedSq = -1; destinations = 0L;
        fireBoardChanged();
        fireAnnotateStateChanged();
        updateStatus();
        kickAnnotateIfNeeded();
    }

    public void annotateStep(int delta) { annotateGoto(annotatePly + delta); }
    public int  annotatePly()           { return annotatePly; }
    public int  annotateTotal()         { return annotateMoves.size(); }
    public java.util.List<Move> annotateMoves() {
        return java.util.Collections.unmodifiableList(annotateMoves);
    }

    /** Start the engine analyzing the *current* position (the one shown on
     *  the board). When the search completes, fires annotateResult on the
     *  listener with the played move (if there is one) and the engine's
     *  result. If the result is already cached, fires immediately without
     *  re-searching. */
    private void kickAnnotateIfNeeded() {
        if (mode != Mode.ANNOTATE) return;
        // At ply==total, the game is finished; nothing to analyse.
        if (annotatePly >= annotateMoves.size()) return;

        final int  plyAtLaunch = annotatePly;
        final Move played      = annotateMoves.get(plyAtLaunch);
        // The result is cached by *next ply index* — i.e. plyAtLaunch+1 is the
        // ply that was played from the current position. Use that as the key.
        final int  cacheKey    = plyAtLaunch + 1;

        Search.Result cached = annotateCache.get(cacheKey);
        if (cached != null) {
            // Hit — fire immediately, no engine run.
            fireAnnotateResult(cacheKey, played, cached);
            return;
        }

        // Miss — run the engine in a background thread.
        final String fen = board.toFen();
        final EngineSettings cfg = whiteSettings;   // annotate always uses MP1 settings
        final long generation = currentGeneration;
        final byte searchSide = board.side();
        annotateCancel = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean myCancel = annotateCancel;

        Thread t = new Thread(() -> {
            Board copy = Board.fromFen(fen);
            Search s = cfg.buildSearch();
            Search.Result r = s.findBest(copy, cfg.depth, myCancel::get, null);
            SwingUtilities.invokeLater(() -> {
                if (generation != currentGeneration) return;     // user moved on
                if (r.bestMove == null) return;                  // search aborted
                annotateCache.put(cacheKey, r);
                // Forward as a progress line too, so the live engine output
                // panel shows what just happened.
                String line = String.format(
                    "(annotate ply %d) depth=%d  best=%s  score=%+d  nodes=%d  %d ms",
                    cacheKey, r.depth, r.bestMove, r.score, r.nodes, r.ms);
                if (listener != null) listener.engineProgress(searchSide, line);
                fireAnnotateResult(cacheKey, played, r);
            });
        }, "BreakthroughAnnotate");
        t.setDaemon(true);
        t.start();
    }

    private void stopAnnotate() {
        if (annotateCancel != null) annotateCancel.set(true);
    }

    /** Reset annotate state to "no game loaded" and revert mode to PLAY.
     *  Called by the various reset methods (newGame, loadGame, etc) when they
     *  fire while we're in Annotate mode. */
    private void leaveAnnotate() {
        stopAnnotate();
        annotateMoves.clear();
        annotateCache.clear();
        annotatePly = 0;
        mode = Mode.PLAY;
        fireAnnotateStateChanged();
    }

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
        if (mode == Mode.ANNOTATE) return;             // annotate is read-only history
        // In PLAY mode: only the human side may move. In ANALYSE mode: any
        // human can move for any side (you're exploring lines).
        if (mode == Mode.PLAY && sideToMoveIsEngine()) return;

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
        if (mode == Mode.ANALYSE) {
            stopAnalyse();
            currentGeneration++;   // invalidate any in-flight analyse callbacks
            startAnalyse();
        } else {
            maybeKickEngine();
        }
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
        if (mode == Mode.ANALYSE) return;        // analyse uses startAnalyse instead
        if (!sideToMoveIsEngine()) return;
        if (thinkingNow) return;
        if (board.winner() != Board.EMPTY) return;

        // Capture the current state for the worker — clone via FEN so the
        // worker's mutations don't alias the EDT-owned board.
        final String fen = board.toFen();
        final byte   searchSide = board.side();
        final EngineSettings cfg = (searchSide == Board.WHITE) ? whiteSettings : blackSettings;
        final long generation = currentGeneration;
        thinkingNow = true;
        updateStatus();

        SwingWorker<Search.Result, String> worker = new SwingWorker<>() {
            @Override protected Search.Result doInBackground() {
                Board copy = Board.fromFen(fen);
                Search s = cfg.buildSearch();
                long t0 = System.currentTimeMillis();
                Search.Result r = s.findBest(copy, cfg.depth);
                long ms = System.currentTimeMillis() - t0;
                publish(String.format("depth=%d  best=%s  score=%+d  nodes=%d  %d ms",
                                       r.depth, r.bestMove, r.score, r.nodes, ms));
                return r;
            }
            @Override protected void process(List<String> lines) {
                if (generation != currentGeneration) return;
                if (listener != null) for (String l : lines) listener.engineProgress(searchSide, l);
            }
            @Override protected void done() {
                thinkingNow = false;
                if (generation != currentGeneration) { updateStatus(); return; }
                try {
                    Search.Result r = get();
                    if (r != null && r.bestMove != null) {
                        // ply about to be played is playedMoves.size() + 1.
                        int ply = playedMoves.size() + 1;
                        if (listener != null) listener.engineMoveCompleted(ply, searchSide, r);
                        applyMove(r.bestMove);
                    }
                } catch (Exception ex) {
                    if (listener != null) listener.statusChanged("Engine error: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    /* ----- analyse mode ----- */

    /**
     * Start a continuous iterative-deepening search on the current position.
     * Runs on a daemon thread. Iteration callbacks are re-posted to the EDT
     * so the listener's `engineProgress` always runs on the EDT.
     *
     * Uses the side-to-move's engine settings (weights/TT) but pushes depth
     * to `analyseMaxDepth` regardless of the settings' depth — Analyse Mode
     * is "search as deep as you can in a reasonable time".
     */
    private void startAnalyse() {
        if (board.winner() != Board.EMPTY) return;
        byte stm = board.side();
        final String fen = board.toFen();
        final EngineSettings cfg = (stm == Board.WHITE) ? whiteSettings : blackSettings;
        final int  maxDepth = analyseMaxDepth;
        final long generation = currentGeneration;
        final byte searchSide = stm;
        // Fresh cancel flag for this analyse run.
        analyseCancel = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean myCancel = analyseCancel;

        Thread t = new Thread(() -> {
            Board copy = Board.fromFen(fen);
            Search s = cfg.buildSearch();
            s.findBest(copy, maxDepth, myCancel::get, res -> {
                if (generation != currentGeneration) return;
                String line = String.format(
                    "(analyse) depth=%d  best=%s  score=%+d  nodes=%d  %d ms",
                    res.depth, res.bestMove, res.score, res.nodes, res.ms);
                SwingUtilities.invokeLater(() -> {
                    if (generation != currentGeneration) return;
                    if (listener != null) listener.engineProgress(searchSide, line);
                });
            });
        }, "BreakthroughAnalyse");
        t.setDaemon(true);
        t.start();
    }

    private void stopAnalyse() {
        if (analyseCancel != null) analyseCancel.set(true);
    }

    /* ----- listener helpers ----- */

    private void fireBoardChanged() {
        if (listener != null) listener.boardChanged(board, lastFromSq, lastToSq);
    }
    private void fireGameOver(String result) {
        if (listener != null) listener.gameOver(result);
    }
    private void fireAnnotateStateChanged() {
        if (listener != null) listener.annotateStateChanged(annotatePly, annotateMoves.size());
    }
    private void fireAnnotateResult(int ply, Move played, Search.Result r) {
        boolean agrees = r.bestMove != null && r.bestMove.toString().equals(played.toString());
        if (listener != null) listener.annotateResult(ply, played, r, agrees);
    }
    private void updateStatus() {
        if (listener == null) return;
        byte w = board.winner();
        if (w != Board.EMPTY) {
            listener.statusChanged((w == Board.WHITE ? "White" : "Black") + " wins.");
            return;
        }
        String stm = (board.side() == Board.WHITE) ? "White" : "Black";
        if (mode == Mode.ANALYSE) {
            listener.statusChanged("Analysing — " + stm + " to move. Click any piece to explore.");
            return;
        }
        if (mode == Mode.ANNOTATE) {
            int total = annotateMoves.size();
            int moveNum = (annotatePly + 1) / 2 + ((annotatePly > 0 && (annotatePly & 1) == 0) ? 0 : 0);
            String pos = annotatePly + " / " + total;
            if (annotatePly < total) {
                Move next = annotateMoves.get(annotatePly);
                listener.statusChanged("Annotate ply " + pos + " — " + stm + " to play " + next);
            } else {
                listener.statusChanged("Annotate ply " + pos + " — end of game");
            }
            return;
        }
        String controller = (sideToMoveIsEngine() ? "engine" : "human");
        if (thinkingNow) {
            int d = (board.side() == Board.WHITE) ? whiteSettings.depth : blackSettings.depth;
            listener.statusChanged("Engine thinking (" + stm + ", depth " + d + ")...");
        }
        else listener.statusChanged(stm + " to move (" + controller + ")");
    }
}
