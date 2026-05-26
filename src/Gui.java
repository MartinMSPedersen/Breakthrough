import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Top-level Swing window: menu bar, board panel, status bar.
 *
 * The menu structure matches the design spec:
 *   File: New Game, Load Game, Load Position, Save Game, Save Position, Quit
 *   Edit: Edit Tags, Edit Position
 *   View: Flip View, Engine Output, Evalution Graph, Colors
 *   Mode: Machine Player 1, Machine Player 2, Two Machines, Analyse Mode, Annotate Game
 *   Engine: Machine Player 1 Settings, Machine Player 2 Settings, Reset to default Settings
 *   Help: About
 *
 * Items implemented in v1: New Game, Quit, Flip View, the three Mode entries
 * (Machine Player 1/2, Two Machines), About. The rest stub out with a
 * "not implemented yet" dialog so the menu is fully discoverable.
 */
public class Gui extends JFrame implements GameController.Listener {

    private final BoardPanel        boardPanel  = new BoardPanel();
    private final EngineOutputPanel outputPanel = new EngineOutputPanel();
    private final GameController    controller  = new GameController();
    private final JLabel            status      = new JLabel(" ");
    private JSplitPane              split;

    /* Mode radio buttons live as fields so we can keep them in sync. */
    private JRadioButtonMenuItem modeMP1, modeMP2, modeTwoMachines, modeAnalyse;

    /* Last-used directories for the file choosers, remembered for the
     * lifetime of this window. Initialized lazily on first open/save so we
     * default to ./saves and ./positions if those exist. */
    private Path lastGameDir     = null;
    private Path lastPositionDir = null;

    public Gui() {
        super("Breakthrough");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        setJMenuBar(buildMenuBar());

        boardPanel.setClickListener(controller::onClick);
        controller.setListener(this);

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 13f));

        setLayout(new BorderLayout());
        // Board on the left, engine output panel on the right.
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, boardPanel, outputPanel);
        split.setResizeWeight(1.0);   // extra space goes to the board
        split.setContinuousLayout(true);
        split.setDividerSize(6);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);

        // Default: human plays White, engine plays Black.
        controller.setSides(GameController.Side.HUMAN, GameController.Side.ENGINE);
        modeMP2.setSelected(true);
        controller.newGame();
    }

    /* ----- menu construction ----- */

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        // File
        JMenu file = new JMenu("File");
        file.add(action("New Game",        KeyEvent.VK_N, e -> { outputPanel.note("New game"); controller.newGame(); }));
        file.add(action("Load Game...",    KeyEvent.VK_O, e -> loadGame()));
        file.add(action("Load Position...", 0,            e -> loadPosition()));
        file.addSeparator();
        file.add(action("Save Game...",    KeyEvent.VK_S, e -> saveGame()));
        file.add(action("Save Position...", 0,            e -> savePosition()));
        file.addSeparator();
        file.add(action("Quit",            KeyEvent.VK_Q, e -> dispose()));
        mb.add(file);

        // Edit
        JMenu edit = new JMenu("Edit");
        edit.add(stub("Edit Tags"));
        edit.add(stub("Edit Position"));
        mb.add(edit);

        // View
        JMenu view = new JMenu("View");
        JCheckBoxMenuItem flip = new JCheckBoxMenuItem("Flip View");
        flip.addActionListener(e -> boardPanel.setFlipped(flip.isSelected()));
        view.add(flip);
        JCheckBoxMenuItem coords = new JCheckBoxMenuItem("Coordinates", true);
        coords.setToolTipText("Show a-h file letters and 1-8 rank numbers around the board");
        coords.addActionListener(e -> boardPanel.setShowLabels(coords.isSelected()));
        view.add(coords);
        JCheckBoxMenuItem engOut = new JCheckBoxMenuItem("Engine Output", true);
        engOut.setToolTipText("Show or hide the engine output panel on the right");
        engOut.addActionListener(e -> setEngineOutputVisible(engOut.isSelected()));
        view.add(engOut);
        view.add(stub("Evaluation Graph"));
        view.add(stub("Colors"));
        mb.add(view);

        // Mode
        JMenu mode = new JMenu("Mode");
        ButtonGroup mg = new ButtonGroup();
        modeMP1 = radioMode("Machine Player 1",
                            "Engine plays White, human plays Black",
                            () -> { outputPanel.note("Mode: Machine Player 1 (engine=W)");
                                    controller.setMode(GameController.Mode.PLAY);
                                    controller.setSides(GameController.Side.ENGINE, GameController.Side.HUMAN); });
        modeMP2 = radioMode("Machine Player 2",
                            "Human plays White, engine plays Black",
                            () -> { outputPanel.note("Mode: Machine Player 2 (engine=B)");
                                    controller.setMode(GameController.Mode.PLAY);
                                    controller.setSides(GameController.Side.HUMAN, GameController.Side.ENGINE); });
        modeTwoMachines = radioMode("Two Machines",
                            "Engine vs engine",
                            () -> { outputPanel.note("Mode: Two Machines");
                                    controller.setMode(GameController.Mode.PLAY);
                                    controller.setSides(GameController.Side.ENGINE, GameController.Side.ENGINE); });
        modeAnalyse = radioMode("Analyse Mode",
                            "Engine searches the current position continuously; click pieces to explore variations",
                            () -> { outputPanel.note("Mode: Analyse");
                                    controller.setMode(GameController.Mode.ANALYSE); });
        mg.add(modeMP1); mg.add(modeMP2); mg.add(modeTwoMachines); mg.add(modeAnalyse);
        mode.add(modeMP1); mode.add(modeMP2); mode.add(modeTwoMachines); mode.add(modeAnalyse);
        mode.addSeparator();
        mode.add(stub("Annotate Game"));
        mb.add(mode);

        // Engine
        // "Machine Player 1" = the engine when it plays White (matching Mode menu).
        // "Machine Player 2" = the engine when it plays Black.
        JMenu engine = new JMenu("Engine");
        engine.add(action("Machine Player 1 Settings...", 0, e -> openSettings(true)));
        engine.add(action("Machine Player 2 Settings...", 0, e -> openSettings(false)));
        engine.addSeparator();
        engine.add(action("Reset to default Settings",    0, e -> resetSettings()));
        mb.add(engine);

        // Help
        JMenu help = new JMenu("Help");
        help.add(action("About", 0, e -> showAbout()));
        mb.add(help);

        return mb;
    }

    private JMenuItem action(String name, int mnemonic, ActionListener a) {
        JMenuItem mi = new JMenuItem(name);
        if (mnemonic != 0) {
            mi.setMnemonic(mnemonic);
            mi.setAccelerator(KeyStroke.getKeyStroke(mnemonic, InputEvent.CTRL_DOWN_MASK));
        }
        mi.addActionListener(a);
        return mi;
    }

    private JMenuItem stub(String name) {
        JMenuItem mi = new JMenuItem(name);
        mi.addActionListener(e -> JOptionPane.showMessageDialog(this,
                name + " is not implemented yet.",
                "Not implemented", JOptionPane.INFORMATION_MESSAGE));
        return mi;
    }

    private JRadioButtonMenuItem radioMode(String name, String tip, Runnable action) {
        JRadioButtonMenuItem mi = new JRadioButtonMenuItem(name);
        mi.setToolTipText(tip);
        mi.addActionListener(e -> action.run());
        return mi;
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "Breakthrough GUI\n\nFront-end for the Breakthrough engine.\n"
          + "Click a piece to select it, then click a destination to move.",
            "About Breakthrough", JOptionPane.INFORMATION_MESSAGE);
    }

    /* ----- Engine: Settings ----- */

    /**
     * Open the engine settings dialog for one side.
     * @param white  true → edit Machine Player 1 (the White engine);
     *               false → edit Machine Player 2 (the Black engine).
     */
    private void openSettings(boolean white) {
        String title = white ? "Machine Player 1 Settings (White)"
                             : "Machine Player 2 Settings (Black)";
        EngineSettings current = white ? controller.whiteSettings()
                                        : controller.blackSettings();
        EngineSettings updated = EngineSettingsDialog.show(this, title, current);
        if (updated == null) return;  // user cancelled

        if (white) controller.setWhiteSettings(updated);
        else       controller.setBlackSettings(updated);
        status.setText((white ? "MP1" : "MP2") + " settings updated: " + updated);
    }

    private void resetSettings() {
        int r = JOptionPane.showConfirmDialog(this,
            "Reset both engines to default settings?",
            "Confirm reset", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        controller.resetSettings();
        status.setText("Both engines reset to default settings.");
    }

    /* ----- File: Load / Save ----- */

    private static final DateTimeFormatter FNAME_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Pick a starting directory for the game chooser: last-used, then ./saves, then cwd. */
    private Path gameStartDir() {
        if (lastGameDir != null && Files.isDirectory(lastGameDir)) return lastGameDir;
        Path saves = Paths.get("saves");
        if (Files.isDirectory(saves)) return saves;
        return Paths.get(".");
    }

    private Path positionStartDir() {
        if (lastPositionDir != null && Files.isDirectory(lastPositionDir)) return lastPositionDir;
        Path pos = Paths.get("positions");
        if (Files.isDirectory(pos)) return pos;
        return Paths.get(".");
    }

    private JFileChooser fileChooser(Path startDir, String description, String ext) {
        JFileChooser fc = new JFileChooser(startDir.toFile());
        fc.setFileFilter(new FileNameExtensionFilter(description, ext));
        return fc;
    }

    private void loadGame() {
        JFileChooser fc = fileChooser(gameStartDir(), "Breakthrough game (.game)", "game");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        lastGameDir = p.getParent();
        try {
            List<Move> moves = GameReplay.loadMoves(p);
            controller.loadGame(moves);
            setTitle("Breakthrough — " + p.getFileName());
        } catch (IOException ex) {
            error("Could not read file: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            error("Bad game file: " + ex.getMessage());
        }
    }

    private void loadPosition() {
        JFileChooser fc = fileChooser(positionStartDir(), "Breakthrough position (.fen)", "fen");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        lastPositionDir = p.getParent();
        try {
            Board loaded = PositionIO.load(p);
            controller.loadPosition(loaded);
            setTitle("Breakthrough — " + p.getFileName());
        } catch (IOException ex) {
            error("Could not read file: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            error("Bad position file: " + ex.getMessage());
        }
    }

    private void saveGame() {
        if (controller.playedMoves().isEmpty()) {
            error("No moves to save yet.");
            return;
        }
        JFileChooser fc = fileChooser(gameStartDir(), "Breakthrough game (.game)", "game");
        String defaultName = "breakthrough-" + LocalDateTime.now().format(FNAME_TS) + ".game";
        fc.setSelectedFile(new java.io.File(fc.getCurrentDirectory(), defaultName));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = ensureExtension(fc.getSelectedFile().toPath(), "game");
        lastGameDir = p.getParent();
        if (!confirmOverwrite(p)) return;
        try {
            String result = describeResult();
            String fen    = controller.board().toFen();
            // Save into the parent directory; GameWriter generates its own
            // timestamped name. To honor the user's chosen filename, we save
            // and then rename.
            Path tmp = GameWriter.save(controller.playedMoves(), result, fen, p.getParent());
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setTitle("Breakthrough — " + p.getFileName());
            status.setText("Saved: " + p.getFileName());
        } catch (IOException ex) {
            error("Could not save: " + ex.getMessage());
        }
    }

    private void savePosition() {
        JFileChooser fc = fileChooser(positionStartDir(), "Breakthrough position (.fen)", "fen");
        String defaultName = "breakthrough-" + LocalDateTime.now().format(FNAME_TS) + ".fen";
        fc.setSelectedFile(new java.io.File(fc.getCurrentDirectory(), defaultName));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = ensureExtension(fc.getSelectedFile().toPath(), "fen");
        lastPositionDir = p.getParent();
        if (!confirmOverwrite(p)) return;
        try {
            String fen = controller.board().toFen();
            String content = "# Breakthrough position\n"
                           + "# Saved: " + LocalDateTime.now().format(FNAME_TS) + "\n"
                           + fen + "\n";
            Files.writeString(p, content);
            setTitle("Breakthrough — " + p.getFileName());
            status.setText("Saved: " + p.getFileName());
        } catch (IOException ex) {
            error("Could not save: " + ex.getMessage());
        }
    }

    private Path ensureExtension(Path p, String ext) {
        String fname = p.getFileName().toString();
        if (!fname.toLowerCase().endsWith("." + ext)) {
            p = p.resolveSibling(fname + "." + ext);
        }
        return p;
    }

    private boolean confirmOverwrite(Path p) {
        if (!Files.exists(p)) return true;
        int r = JOptionPane.showConfirmDialog(this,
            p.getFileName() + " already exists. Overwrite?",
            "Confirm overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    /** Build a result line for the game header, matching CLI conventions. */
    private String describeResult() {
        Board b = controller.board();
        byte w = b.winner();
        int moveNum = (controller.playedMoves().size() + 1) / 2;
        if (w != Board.EMPTY) {
            return (w == Board.WHITE ? "White" : "Black") + " wins on move " + moveNum;
        }
        return "in progress";
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    /* ----- GameController.Listener ----- */

    @Override public void boardChanged(Board b, int lastFrom, int lastTo) {
        boardPanel.setBoard(b);
        boardPanel.setLastMove(lastFrom, lastTo);
        if (controller.selectedSq() < 0) {
            boardPanel.clearSelection();
        } else {
            int sq = controller.selectedSq();
            boardPanel.setSelected(sq >>> 3, sq & 7);
            boardPanel.setHighlights(controller.destinations());
        }
    }
    @Override public void statusChanged(String text) { status.setText(text); }
    @Override public void engineProgress(byte side, String line) {
        outputPanel.append(side, line);
    }
    @Override public void gameOver(String result) {
        outputPanel.note("Game over: " + result);
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(this, result, "Game over",
                                          JOptionPane.INFORMATION_MESSAGE));
    }

    /** Show or hide the right-side engine output panel.
     *  We toggle by replacing the split-pane's right component with null so
     *  the divider disappears and the board occupies the full window. */
    private void setEngineOutputVisible(boolean show) {
        if (show) {
            if (split.getRightComponent() == outputPanel) return;
            int dividerPos = split.getDividerLocation();
            split.setRightComponent(outputPanel);
            split.setDividerSize(6);
            if (dividerPos > 0) split.setDividerLocation(dividerPos);
        } else {
            if (split.getRightComponent() == null) return;
            split.setRightComponent(null);
            split.setDividerSize(0);
        }
        revalidate();
    }

    /* ----- entry point ----- */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {}
        SwingUtilities.invokeLater(() -> new Gui().setVisible(true));
    }
}
