# Breakthrough GUI

Swing front-end for the Breakthrough engine. Same engine code as the
CLI project (`Search`, `Evaluator`, `Board`, etc.), with a graphical
board and menu-driven interface on top.

## Build & run

```sh
make          # compile everything to build/
make gui      # launch the GUI
```

Requires JDK 21 (uses the same features as the CLI: records, switch
expressions). No external dependencies — Swing is in the JDK.

## What's in v1

- **Board view**: click a piece to select, then click a destination
  square (highlighted in green) to play the move. Click the selected
  piece again to deselect. The last move is highlighted in yellow.
- **Menu structure**: all menus from the spec are present so the
  layout is set. Items not yet implemented show a "not implemented"
  dialog.
- **File → New Game** resets to the starting position.
- **File → Quit** exits.
- **View → Flip View** rotates the board (so Black's home row is at
  the bottom).
- **Mode → Machine Player 1**: engine plays White, human plays Black.
- **Mode → Machine Player 2**: human plays White, engine plays Black
  (the default at startup).
- **Mode → Two Machines**: engine vs engine; press *File → New Game*
  to watch.
- **Help → About**: brief blurb.

The engine runs on a background thread (Swing `SwingWorker`) so the
UI stays responsive during deep searches.

## What's coming

- **v2**: Engine output side panel, Analyse Mode, evaluation graph.
- **v3**: Load/Save game and position (reusing the existing `.game`
  and FEN formats), Edit Position, per-engine settings dialogs,
  Annotate Mode.

## Layout

```
Breakthrough-GUI/
├── Makefile
├── README.md
├── src/
│   ├── Gui.java                    # JFrame, menus, status bar
│   ├── BoardPanel.java             # Custom-painted 8x8 board
│   ├── GameController.java         # Click handling, engine threading
│   │
│   ├── Bitboards.java              # Engine sources (copied from CLI project)
│   ├── Board.java
│   ├── Evaluator.java
│   ├── Move.java
│   ├── MoveGenerator.java
│   ├── PositionIO.java
│   ├── Search.java
│   ├── TT.java
│   ├── Tuner.java
│   ├── Zobrist.java
│   ├── GameReplay.java
│   ├── GameWriter.java
│   └── Main.java                   # CLI; not used by the GUI but kept for tests
```
