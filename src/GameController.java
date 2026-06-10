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
     *  inspecting history).
     *
     *  EDIT_POSITION = the user is placing/removing pieces on the board.
     *  Clicks set squares to the currently-selected palette piece (W, B,
     *  or empty). Engine activity is suspended. Exiting via commit applies
     *  the edited position (clearing move history); cancelling reverts. */
    public enum Mode  { PLAY, ANALYSE, ANNOTATE, EDIT_POSITION }

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
        /** Analyse-mode navigation state changed. totalPlies > 0 means the
         *  GUI should show step controls; totalPlies == 0 means hide them. */
        default void analyseNavStateChanged(int ply, int totalPlies) {}
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
    /** Game tags (PGN-style metadata: White, Black, Event, Site, Date, Result).
     *  Loaded from `# Tag: value` comment lines and written back on save.
     *  Insertion-ordered so the saved tag block reads predictably. */
    private final java.util.LinkedHashMap<String, String> tags = new java.util.LinkedHashMap<>();

    private Side whiteSide = Side.HUMAN;
    private Side blackSide = Side.ENGINE;
    private Mode mode      = Mode.PLAY;
    /** Per-side engine settings (depth, TT, weights, defender scale).
     *  Whichever side the engine controls reads its settings here. */
    private EngineSettings whiteSettings = EngineSettings.defaults();
    private EngineSettings blackSettings = EngineSettings.defaults();
    /** Maximum depth used by Analyse Mode. Set near Search.MAX_PLY so Analyse
     *  effectively runs as deep as it can; the mate-stop, the cancel flag,
     *  and the user's patience are what actually terminate it. */
    private int analyseMaxDepth = 99;
    /** Cancel flag for the currently running Analyse search, if any. The
     *  controller sets this true to stop the running analysis, then clears
     *  it (allocates a fresh AtomicBoolean) before starting a new one. */
    private java.util.concurrent.atomic.AtomicBoolean analyseCancel
        = new java.util.concurrent.atomic.AtomicBoolean();

    /** Analyse-mode navigation. When a game is "in progress" (playedMoves
     *  non-empty) at Analyse entry, we snapshot those moves into
     *  analyseGameMoves and set analyseGamePly = playedMoves.size(). The GUI
     *  then shows a step toolbar; stepping rebuilds the board to that ply and
     *  restarts the search. Playing any move clears the snapshot (the user
     *  is now exploring a variation). */
    private java.util.List<Move> analyseGameMoves = new java.util.ArrayList<>();
    private int                  analyseGamePly   = 0;

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
    /** Whether to include the principal variation in play-mode engine output
     *  lines. Off by default; toggled by View → "Show PV during game". */
    private boolean showPvDuringGame = false;
    public void setShowPvDuringGame(boolean v) { this.showPvDuringGame = v; }
    public boolean isShowPvDuringGame() { return showPvDuringGame; }
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
        tags.clear();
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
        tags.clear();
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
        tags.clear();
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
            // Leaving Annotate: KEEP the currently-displayed position, AND
            // promote the annotated game into playedMoves so step navigation
            // works in the next mode (especially Analyse). We keep the full
            // game's move list; the current "ply position" is captured via
            // analyseGamePly below for Analyse, or just used for the last-move
            // highlight otherwise.
            int wasAtPly = annotatePly;
            java.util.List<Move> moves = new java.util.ArrayList<>(annotateMoves);
            annotateMoves.clear();
            annotateCache.clear();
            annotatePly = 0;
            playedMoves.clear();
            playedMoves.addAll(moves);
            // Preserve the analyse-stepping notion of "where we are":
            // we'll set this below when entering ANALYSE.
            // board, lastFromSq, lastToSq stay as they are.
            fireAnnotateStateChanged();

            // If switching directly into ANALYSE, seed analyseGamePly to the
            // ply we were viewing, not the end. (The general "snapshot
            // playedMoves" branch below runs after this and uses
            // playedMoves.size() — but for the annotate→analyse case we
            // want the *current* viewing ply.)
            if (newMode == Mode.ANALYSE) {
                analyseGameMoves = new java.util.ArrayList<>(moves);
                analyseGamePly   = wasAtPly;
                // Trim playedMoves to the viewing prefix, consistent with
                // analyseGotoInGame's contract that playedMoves matches the
                // currently-displayed board.
                playedMoves.clear();
                playedMoves.addAll(moves.subList(0, wasAtPly));
                fireAnalyseNavStateChanged();
                startAnalyse();
                updateStatus();
                return;
            }
        }
        if (mode == Mode.ANALYSE) {
            // Snapshot the played moves so the GUI can offer step navigation
            // through them. analyseGamePly starts at the end (current position).
            analyseGameMoves = new java.util.ArrayList<>(playedMoves);
            analyseGamePly   = analyseGameMoves.size();
            fireAnalyseNavStateChanged();
            startAnalyse();
        } else {
            // Leaving Analyse: clear the navigation snapshot if it was set.
            if (!analyseGameMoves.isEmpty()) {
                analyseGameMoves.clear();
                analyseGamePly = 0;
                fireAnalyseNavStateChanged();
            }
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

    /* ----- Analyse-mode navigation ----- */

    public void analyseStepInGame(int delta) { analyseGotoInGame(analyseGamePly + delta); }
    public int  analyseGamePly()              { return analyseGamePly; }
    public int  analyseGameTotal()            { return analyseGameMoves.size(); }

    /** Step to a specific ply in the loaded game. 0 = starting position; N
     *  = after Nth ply. Out-of-range values are clamped. No-op if not in
     *  Analyse mode or no game is loaded. */
    public void analyseGotoInGame(int newPly) {
        if (mode != Mode.ANALYSE) return;
        if (analyseGameMoves.isEmpty()) return;
        newPly = Math.max(0, Math.min(newPly, analyseGameMoves.size()));
        if (newPly == analyseGamePly) return;
        stopAnalyse();
        currentGeneration++;
        // Rebuild board at the requested ply.
        Board b = Board.initial();
        for (int i = 0; i < newPly; i++) b.apply(analyseGameMoves.get(i));
        board = b;
        analyseGamePly = newPly;
        // Keep playedMoves in sync — it's "the moves leading to the displayed
        // board", which is exactly the prefix up to newPly.
        playedMoves.clear();
        playedMoves.addAll(analyseGameMoves.subList(0, newPly));
        if (newPly > 0) {
            Move m = analyseGameMoves.get(newPly - 1);
            lastFromSq = m.fromRow() * 8 + m.fromCol();
            lastToSq   = m.toRow()   * 8 + m.toCol();
        } else {
            lastFromSq = -1; lastToSq = -1;
        }
        selectedSq = -1; destinations = 0L;
        fireBoardChanged();
        fireAnalyseNavStateChanged();
        updateStatus();
        startAnalyse();
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

    /* ----- Edit Position ----- */
    /** Snapshot of state taken on entry to EDIT_POSITION. Restored if the user
     *  cancels rather than committing. */
    private String  editSnapshotFen;
    private java.util.List<Move> editSnapshotMoves;

    /** Enter EDIT_POSITION mode. Snapshots the current board+history; the
     *  user can now place/remove pieces. Call editCommit() or editCancel()
     *  to exit. */
    public void enterEditPosition() {
        if (mode == Mode.EDIT_POSITION) return;
        if (mode == Mode.ANNOTATE) leaveAnnotate();
        stopAnalyse();
        currentGeneration++;
        thinkingNow = false;
        editSnapshotFen   = board.toFen();
        editSnapshotMoves = new java.util.ArrayList<>(playedMoves);
        mode = Mode.EDIT_POSITION;
        selectedSq = -1; destinations = 0L;
        lastFromSq = -1; lastToSq = -1;
        fireBoardChanged();
        updateStatus();
    }

    /** Place a piece (or empty) at the given square. EDT-only. No-op if not
     *  in EDIT_POSITION mode. */
    public void editPlacePiece(int row, int col, byte piece) {
        if (mode != Mode.EDIT_POSITION) return;
        if (row < 0 || row > 7 || col < 0 || col > 7) return;
        if (piece != Board.EMPTY && piece != Board.WHITE && piece != Board.BLACK) return;
        board.set(row, col, piece);
        fireBoardChanged();
    }

    /** Clear the board. */
    public void editClearBoard() {
        if (mode != Mode.EDIT_POSITION) return;
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) board.set(r, c, Board.EMPTY);
        fireBoardChanged();
    }

    /** Reset the board to the standard Breakthrough starting position. */
    public void editResetBoard() {
        if (mode != Mode.EDIT_POSITION) return;
        Board fresh = Board.initial();
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) board.set(r, c, fresh.get(r, c));
        fireBoardChanged();
    }

    /** Flip side-to-move. */
    public void editFlipSideToMove() {
        if (mode != Mode.EDIT_POSITION) return;
        // Board only exposes side via FEN — reconstruct with the opposite side.
        String fen = board.toFen();
        // FEN ends with " W" or " B". Flip the last token.
        String flipped;
        if (fen.endsWith(" W"))      flipped = fen.substring(0, fen.length() - 1) + "B";
        else if (fen.endsWith(" B")) flipped = fen.substring(0, fen.length() - 1) + "W";
        else                          flipped = fen;
        board = Board.fromFen(flipped);
        fireBoardChanged();
        updateStatus();
    }

    /** Commit the edited position. Move history is cleared (we don't know
     *  what led to this position). Mode returns to PLAY. */
    public void editCommit() {
        if (mode != Mode.EDIT_POSITION) return;
        editSnapshotFen = null;
        editSnapshotMoves = null;
        playedMoves.clear();
        tags.clear();
        lastFromSq = -1; lastToSq = -1;
        mode = Mode.PLAY;
        fireBoardChanged();
        updateStatus();
        // Check for instant game-over on the committed position.
        byte w = board.winner();
        if (w != Board.EMPTY) {
            fireGameOver((w == Board.WHITE ? "White" : "Black") + " wins");
        } else {
            maybeKickEngine();
        }
    }

    /** Cancel the edit; restore the snapshot. */
    public void editCancel() {
        if (mode != Mode.EDIT_POSITION) return;
        board = Board.fromFen(editSnapshotFen);
        playedMoves.clear();
        playedMoves.addAll(editSnapshotMoves);
        if (!playedMoves.isEmpty()) {
            Move last = playedMoves.get(playedMoves.size() - 1);
            lastFromSq = last.fromRow() * 8 + last.fromCol();
            lastToSq   = last.toRow()   * 8 + last.toCol();
        }
        editSnapshotFen = null;
        editSnapshotMoves = null;
        mode = Mode.PLAY;
        fireBoardChanged();
        updateStatus();
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

    /** Returns a defensive copy of the game tags. */
    public java.util.LinkedHashMap<String, String> tags() {
        return new java.util.LinkedHashMap<>(tags);
    }
    /** Replace all tags. Empty values are dropped to avoid writing empty
     *  `# Tag: ` lines to the saved file. */
    public void setTags(java.util.Map<String, String> newTags) {
        tags.clear();
        for (var e : newTags.entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isBlank()) tags.put(e.getKey(), v.trim());
        }
    }

    /* ----- click handling ----- */

    /**
     * Called by BoardPanel when the user clicks a square. The controller
     * decides what it means: select a piece, deselect, or play a move.
     */
    public void onClick(int row, int col) {
        if (thinkingNow) return;                       // ignore clicks while engine thinks
        if (mode == Mode.EDIT_POSITION) return;        // GUI uses editPlacePiece in this mode
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
            // Playing a move in Analyse mode counts as "exploring a variation".
            // Drop the navigation snapshot so the step toolbar disappears —
            // we don't try to reconcile played-variations against the snapshot.
            if (!analyseGameMoves.isEmpty()) {
                analyseGameMoves.clear();
                analyseGamePly = 0;
                fireAnalyseNavStateChanged();
            }
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
                String moveStr;
                if (showPvDuringGame) {
                    // Extract PV from the just-finished search; TT entries are
                    // intact since we're between iteration callbacks (none here)
                    // and the next search hasn't started.
                    java.util.List<Move> pv = s.extractPv(copy, 3);
                    if (pv.isEmpty()) {
                        moveStr = (r.bestMove == null) ? "(none)" : r.bestMove.toString();
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < pv.size(); i++) {
                            if (i > 0) sb.append(' ');
                            sb.append(pv.get(i));
                        }
                        moveStr = sb.toString();
                    }
                    publish(String.format("depth=%d  pv=%s  score=%+d  nodes=%d  %d ms",
                                           r.depth, moveStr, r.score, r.nodes, ms));
                } else {
                    publish(String.format("depth=%d  best=%s  score=%+d  nodes=%d  %d ms",
                                           r.depth, r.bestMove, r.score, r.nodes, ms));
                }
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
                // Extract the principal variation now, while the TT still holds
                // this iteration's entries. The next iteration would overwrite.
                // Show up to 3 plies: best move + two follow-ups.
                java.util.List<Move> pv = s.extractPv(copy, 3);
                StringBuilder pvStr = new StringBuilder();
                if (pv.isEmpty()) {
                    pvStr.append(res.bestMove == null ? "(none)" : res.bestMove.toString());
                } else {
                    for (int i = 0; i < pv.size(); i++) {
                        if (i > 0) pvStr.append(' ');
                        pvStr.append(pv.get(i).toString());
                    }
                }
                String line = String.format(
                    "(analyse) depth=%d  pv=%s  score=%+d  nodes=%d  %d ms",
                    res.depth, pvStr, res.score, res.nodes, res.ms);
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
    private void fireAnalyseNavStateChanged() {
        if (listener != null) listener.analyseNavStateChanged(analyseGamePly, analyseGameMoves.size());
    }
    private void updateStatus() {
        if (listener == null) return;
        // In EDIT_POSITION, the board may not even be legal; don't try to
        // interpret it. Just say what mode we're in.
        if (mode == Mode.EDIT_POSITION) {
            String stm = (board.side() == Board.WHITE) ? "White" : "Black";
            listener.statusChanged("Editing position — " + stm + " to move. Use the toolbar to place pieces.");
            return;
        }
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
