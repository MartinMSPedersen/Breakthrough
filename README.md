# Breakthrough GUI

Swing front-end for the Breakthrough engine. Reuses the engine code
from the CLI project (`Search`, `Evaluator`, `Board`, `MoveGenerator`,
etc.) verbatim; adds a graphical board, a menu, an engine output
panel, and a settings dialog on top.

## Build & run

```sh
make          # compile everything to build/
make gui      # launch the GUI
```

Requires JDK 21 (records, switch expressions, pattern matching). No
external dependencies - Swing is in the JDK.

The CLI tools from the engine project (`Main play`, `Main analyse`,
`Main benchmark`, `Tuner`) also work in this directory: `make play`,
`make bench`, `make tune`, etc. The GUI doesn't depend on them, but
they're useful for sanity-checking the engine after any changes.

## Distributable AppImage

For handing the GUI to people who don't have Java installed:

```sh
make appimage
```

Produces `Breakthrough-GUI-x86_64.AppImage`, a single self-contained
executable. Bundles a `jlink`-trimmed JRE (~55 MB) so the user
doesn't need Java on their machine. Run with:

```sh
chmod +x Breakthrough-GUI-x86_64.AppImage
./Breakthrough-GUI-x86_64.AppImage
```

Notes:
- First `make appimage` downloads `appimagetool` from GitHub into
  `tools/` (~28 MB). Cached after the first run.
- Built on x86_64 produces an x86_64 AppImage. For ARM you'd build on
  ARM with an ARM JDK.
- The host needs `fuse` to run AppImages — almost every Linux desktop
  has it.
- `make clean` removes the AppImage and build artifacts but keeps the
  cached appimagetool. `make dist-clean` removes that too.

## At a glance

- **Click to play**: click a piece to select it, click a green-highlighted
  square to move there.
- **Or drag and drop**: press on a piece, drag, release on the destination.
  Dropping outside the board or on an illegal square snaps the piece back.
- **Last move** is shown in yellow, **selected square** in blue, **legal
  destinations** in green.
- **Coordinates** (a-h, 1-8) drawn around the board.
- **Flip view** to see the position from Black's side.
- **Engine output panel** on the right shows what each engine is thinking,
  with MP1/MP2 labels and timestamps.
- **Per-engine settings** for depth, TT size, weights, defender scale.
- **Analyse Mode** runs the engine continuously on the current position,
  streaming each iteration's depth/score/best move.
- **Annotate Mode** walks through a saved game ply by ply, with the engine
  evaluating each position and flagging where it disagrees with the played
  move.
- **Evaluation Graph** plots score over the played plies in a floating
  window; click any point in Annotate mode to jump to that ply.
- **Position editor** for setting up arbitrary boards with a piece palette
  and side-to-move toggle.
- **Game tags** (PGN-style metadata: White, Black, Event, Site, Date,
  Result) editable and saved alongside the game.
- **Four color themes** — Classic, Slate, High Contrast, Sepia.
- **Load and save** games (`.game`) and positions (`.fen`); compatible
  with the CLI tools' file formats.

## Menu reference

### File

- **New Game** (Ctrl+N) — reset to the starting position.
- **Load Game...** (Ctrl+O) — open a saved `.game` file. The moves are
  replayed; if the engine is configured to play the current side, it
  starts thinking from there. Game tags (if any) load from the file's
  comment block.
- **Load Position...** — load a FEN-style `.fen` file. Move history is
  cleared (we don't know what led to the position).
- **Save Game...** (Ctrl+S) — save the played moves. Defaults to a
  timestamped filename like `breakthrough-2026-05-25_22-15-30.game` in
  `./saves/`. Tags (if set via Edit → Edit Tags) are written as `# Tag:
  value` lines at the top.
- **Save Position...** — write the current FEN to a `.fen` file.
- **Quit** (Ctrl+Q).

### Edit

- **Edit Tags** — PGN-style metadata dialog. Six rows: White, Black,
  Event, Site, Date, Result. All optional, free-form. Blank values get
  dropped on save. Loaded games auto-populate their tags here.
- **Edit Position** — enter a position editor where clicks place pieces
  on the board rather than moving them. A south-bar toolbar appears:

  - **Place: White / Black / Empty** — palette radio. Next click sets
    the square to this piece (or clears it).
  - **Flip side to move** — toggle who plays from the edited position.
  - **Clear board** — empty all squares.
  - **Reset to start** — restore the standard starting layout.
  - **OK** — commit; move history is cleared.
  - **Cancel** — restore the board and history that existed when you
    entered the editor.

### View

- **Flip View** — rotate the board 180°. Coordinate labels follow.
- **Coordinates** (default on) — show file letters and rank numbers.
- **Engine Output** (default on) — toggle the right-side output panel.
- **Evaluation Graph** — toggle a floating window plotting the engine's
  score across the game. The Y-axis auto-scales (±1k, ±2k, ±5k...) so
  quiet games show detail and dramatic games still fit. Click any point
  in Annotate Mode to jump to that ply.
- **Colors** — submenu with four theme presets:
  - **Classic** — warm brown wood (default).
  - **Slate** — cool gray-blue, modern.
  - **High Contrast** — pure white squares, dark gray squares, bright
    primary-color highlights, opposite-color piece edges so pieces
    are visible on every square. Accessibility-oriented.
  - **Sepia** — cream and caramel, low-glare for long sessions.
  
  Themes affect the board, engine output panel, and evaluation graph;
  menu chrome and dialogs use the system look-and-feel.

### Mode

Five radio-button modes; the active one drives gameplay.

- **Machine Player 1** — engine plays White, you play Black.
- **Machine Player 2** — you play White, engine plays Black (default).
- **Two Machines** — engine vs engine; press *New Game* and watch.
- **Analyse Mode** — engine searches the current position continuously,
  emitting an iteration to the output panel for each depth. You can
  click pieces for either side to explore variations; analysis
  restarts on the new position. Leaving this mode cancels the search.
- **Annotate Game** — opens a file chooser; pick a `.game` file. The
  board resets to the starting position and a step toolbar appears
  below it (`|<  <  ply N / M  >  >|`). Use the buttons or **Left/Right/
  Home/End** arrow keys to step. At each ply the engine analyzes the
  position in the background and notes whether it agrees with the
  played move. Results are cached so revisiting a ply is instant.
  Click-to-move is disabled — you're inspecting history.

### Engine

- **Machine Player 1 Settings...** — open a dialog editing the White
  engine's depth (1-15), TT size in bits (16-26, with live RAM hint),
  evaluator weights, and defender scale. Defaults: depth 8, TT bits 24
  (16M entries, ~512 MB).
- **Machine Player 2 Settings...** — same for the Black engine.
- **Reset to default Settings** — restore both engines to the
  compiled-in defaults.

### Help

- **Rules** — open the Breakthrough Wikipedia page in your default
  browser. Falls back to `xdg-open`, then to showing the URL in a
  dialog if neither works.
- **About** — version blurb.

## Engine output panel

A scrolling text area on the right showing engine activity. Each line
is timestamped and labeled:

```
22:14:03  --   New game
22:14:03  --   Mode: Two Machines
22:14:03  MP1  depth=6  best=a2a3  score=+4  nodes=32403  46 ms
22:14:03  MP2  depth=6  best=h7h6  score=+0  nodes=28115  41 ms
22:14:04  MP1  depth=6  best=a3a4  score=+8  nodes=29002  38 ms
...
22:14:08  --   Game over: White wins on move 30
```

- **MP1** = the engine when it plays White; **MP2** = the engine when
  it plays Black. (Matches the Mode menu naming.)
- Lines beginning with `--` are notes (mode changes, new games, game
  over).
- Capped at 500 lines; older lines are trimmed in batches. The Clear
  button resets the panel.
- In Analyse Mode, each iteration of iterative deepening produces one
  line, so you see depth 1, 2, 3, ... as they complete.

## Engine settings dialog

Four fields:

- **Search depth** — spinner, 1 to 15. Higher = stronger but slower.
  Default 8. 6 is interactive (sub-second); 8-10 is much stronger
  (1-10 seconds); 11+ takes noticeable time but plays beautifully.
- **TT size (bits)** — spinner, 16 to 26 (2^N entries). Bigger TT
  helps at deep search; a live hint shows the implied RAM. Default
  24 (16M entries, ~512 MB), comfortable for depth ≤12. Drop to 20
  (1M entries, ~32 MB) on memory-constrained machines.
- **Evaluator weights** — text field, eight comma-separated integers
  (one per advancement row). Validated on OK; bad input re-opens the
  dialog with the same values for editing.
- **Defender scale** — spinner, 0.0 to 2.0 in 0.05 steps. Multiplier
  on the defender-count evaluator term. Default 0 (term disabled).

Settings apply immediately — the next time the relevant engine plays,
it uses the new values. Each setting is independent per side, so you
can put two different evaluators in a Two Machines matchup and watch
them play each other.

## How it's wired

- `Gui.java` — JFrame, menu bar, status bar, JSplitPane holding the
  board on the left and the engine output panel on the right. Renders
  the app window icon by rendering a small board snapshot at startup.
  Owns the south-bar toolbars (Annotate / Edit-Position) that swap in
  depending on mode.
- `BoardPanel.java` — custom-painted 8×8 board. Handles click-to-move,
  drag-and-drop, highlight overlays, coordinate labels. Stateless
  beyond what the controller hands it (plus transient drag state).
- `EngineOutputPanel.java` — JTextArea in a JScrollPane with
  timestamping, MP1/MP2 labeling, and a line cap.
- `EvalGraphPanel.java` — custom-painted evaluation chart. Lives in a
  non-modal JDialog so the user can position it independently. Y-axis
  auto-scales to fit the data; clicks on points jump to that ply in
  Annotate Mode.
- `EngineSettingsDialog.java` — modal settings form, validated on OK.
- `EngineSettings.java` — value type holding depth/TT/weights/scale;
  builds an `Evaluator` and `Search` on demand.
- `Theme.java` — value class bundling all themable colors, plus four
  preset palettes.
- `GameController.java` — the glue between view and engine. Holds the
  live `Board` and move history. Decides what clicks mean. Runs the
  engine on background threads (SwingWorker for normal play, a daemon
  thread for Analyse Mode). Uses a generation token to invalidate any
  in-flight workers when state changes (new game, load, mode switch,
  settings change), so the engine can never apply a move to a position
  that no longer exists.
- Engine sources (`Search`, `Evaluator`, `Board`, etc.) are copied
  in unchanged from the CLI project — except `Search.findBest`, which
  was extended in this GUI tree to support cancellation and per-iteration
  callbacks. Both extensions are also useful for the CLI and could be
  upstreamed.

## Threading model

The EDT owns all UI state, including the `Board` instance in the
controller. The engine never touches anything the EDT can see; instead:

- For a normal-play engine move, a `SwingWorker` clones the board (via
  FEN round-trip), runs the search, and posts the resulting `Move` back
  to the EDT in `done()`. The EDT applies the move.
- For Analyse Mode, a daemon thread runs the iterative-deepening loop.
  Per-iteration callbacks fire on the search thread but are immediately
  re-posted to the EDT via `SwingUtilities.invokeLater` before they
  touch any UI.

State changes (new game, load, mode switch, settings change) bump a
`currentGeneration` counter on the EDT. Each worker snapshots the
counter at launch; on completion it discards its result if the counter
has moved on. This makes mid-think mode changes race-free.

For Analyse Mode specifically, there's also an `AtomicBoolean` cancel
flag that the search consults periodically (every ~4096 nodes), so the
search bails out in milliseconds when the user switches mode or makes
a move. The completed iterations' best moves are returned; the
partial iteration is discarded and the TT is wiped to prevent
poisoning future searches with half-explored entries.

## File formats

The same formats the CLI uses, so files are interchangeable.

**Game file** (`.game`): free-form text with move tokens like `b2b3`,
optional `#` comments, and an optional header block. The header may
include both auto-generated entries (`# Saved: ...`, `# Plies: N`,
`# Final FEN: ...`) and user-supplied tags from Edit → Edit Tags
(`# White: ...`, `# Date: ...`, etc.). Move tokens — one per ply,
whitespace-separated — make up the body. The CLI tools and the GUI
both read and write this format; tags are preserved across round-trips.

**Position file** (`.fen`): one line of FEN-style notation, e.g.
`OOOOOOOO/OOOOOOOO/8/8/8/8/XXXXXXXX/XXXXXXXX W`. Ranks from 8 down to
1, `/` separated; `X` = White, `O` = Black, digits = run of empties;
trailing `W` or `B` is the side to move. Optional `#` comments are
ignored.

## Layout

```
Breakthrough-GUI/
├── Makefile
├── README.md
├── packaging/
│   ├── AppRun                       AppImage launcher script
│   └── breakthrough-gui.desktop     .desktop file for the AppImage
├── src/
│   ├── Gui.java                    GUI entry point, menus, status bar
│   ├── BoardPanel.java             Custom-painted board (click + drag)
│   ├── EngineOutputPanel.java      Right-side scrolling output panel
│   ├── EvalGraphPanel.java         Floating evaluation graph
│   ├── EngineSettings.java         Per-engine config value type
│   ├── EngineSettingsDialog.java   Modal settings dialog
│   ├── GameController.java         View ↔ engine glue, threading
│   ├── Theme.java                  Color themes
│   ├── IconGen.java                Build-time icon generator
│   │
│   ├── Bitboards.java              ── Engine, copied from CLI project ──
│   ├── Board.java
│   ├── Evaluator.java
│   ├── GameReplay.java
│   ├── GameWriter.java
│   ├── Main.java                   (CLI entry, not used by the GUI)
│   ├── Move.java
│   ├── MoveGenerator.java
│   ├── PositionIO.java
│   ├── Search.java                 (extended with cancel + iteration callbacks)
│   ├── TT.java
│   ├── Tuner.java                  (CLI tuner, not used by the GUI)
│   └── Zobrist.java
├── saves/                          created on first save
└── positions/                      created on first save
```
