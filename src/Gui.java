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
    private JRadioButtonMenuItem modeMP1, modeMP2, modeTwoMachines, modeAnalyse, modeAnnotate;

    /* Annotate toolbar — south of the board, only visible in Annotate mode. */
    private JPanel  annotateBar;
    private JButton btnStart, btnPrev, btnNext, btnEnd;
    private JLabel  annotateLabel;
    private JPanel  southStack;   // holds annotateBar above the status line

    /* Evaluation graph window — separate non-modal dialog so the user can
     * position it where they want. */
    private JDialog        evalDialog;
    private EvalGraphPanel evalGraph;
    private JCheckBoxMenuItem evalGraphMenuItem;
    /** Current theme, kept so the eval graph window can be re-themed. */
    private Theme currentTheme = Theme.CLASSIC;

    /* Edit-position toolbar — south of the board, only visible in EDIT_POSITION
     * mode. The radio buttons inside it pick what to place on the next click. */
    private JPanel  editPosBar;
    /** What the next click in edit-mode will place. WHITE/BLACK/EMPTY (byte). */
    private byte    editPalette = Board.WHITE;

    /* Last-used directories for the file choosers, remembered for the
     * lifetime of this window. Initialized lazily on first open/save so we
     * default to ./saves and ./positions if those exist. */
    private Path lastGameDir     = null;
    private Path lastPositionDir = null;

    public Gui() {
        super("Breakthrough");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(buildAppIcons());

        setJMenuBar(buildMenuBar());

        boardPanel.setClickListener(this::onBoardClick);
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

        // Annotate and Edit-Position toolbars: hidden by default, only one
        // appears at a time depending on mode.
        annotateBar = buildAnnotateBar();
        annotateBar.setVisible(false);
        editPosBar = buildEditPosBar();
        editPosBar.setVisible(false);
        // Both go on top of the status line via a small vertical Box.
        Box bars = Box.createVerticalBox();
        bars.add(annotateBar);
        bars.add(editPosBar);
        southStack = new JPanel(new BorderLayout());
        southStack.add(bars,   BorderLayout.NORTH);
        southStack.add(status, BorderLayout.SOUTH);
        add(southStack, BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);

        // Default: human plays White, engine plays Black.
        controller.setSides(GameController.Side.HUMAN, GameController.Side.ENGINE);
        modeMP2.setSelected(true);
        controller.newGame();

        installAnnotateKeys();
    }

    /* ----- menu construction ----- */

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        // File
        JMenu file = new JMenu("File");
        file.add(action("New Game",        KeyEvent.VK_N, e -> { outputPanel.note("New game"); clearEvalGraph(); controller.newGame(); }));
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
        edit.add(action("Edit Tags", 0, e -> editTagsDialog()));
        edit.add(action("Edit Position", 0, e -> enterEditPosition()));
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
        evalGraphMenuItem = new JCheckBoxMenuItem("Evaluation Graph", false);
        evalGraphMenuItem.setToolTipText("Show or hide the evaluation graph window");
        evalGraphMenuItem.addActionListener(e -> setEvalGraphVisible(evalGraphMenuItem.isSelected()));
        view.add(evalGraphMenuItem);
        view.add(buildColorsMenu());
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
        modeAnnotate = radioMode("Annotate Game",
                            "Load a saved game and walk through it ply by ply with engine analysis",
                            this::enterAnnotateMode);
        mg.add(modeMP1); mg.add(modeMP2); mg.add(modeTwoMachines); mg.add(modeAnalyse); mg.add(modeAnnotate);
        mode.add(modeMP1); mode.add(modeMP2); mode.add(modeTwoMachines); mode.add(modeAnalyse); mode.add(modeAnnotate);
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
        help.add(action("Rules", 0, e -> openRules()));
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

    /** Build View → Colors submenu. One radio per theme; Classic preselected. */
    private JMenu buildColorsMenu() {
        JMenu colors = new JMenu("Colors");
        ButtonGroup bg = new ButtonGroup();
        for (Theme t : Theme.ALL) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(t.name);
            if (t == Theme.CLASSIC) mi.setSelected(true);
            mi.addActionListener(e -> applyTheme(t));
            bg.add(mi);
            colors.add(mi);
        }
        return colors;
    }

    /** Push a theme out to every panel that paints. */
    private void applyTheme(Theme t) {
        currentTheme = t;
        boardPanel.setTheme(t);
        outputPanel.setTheme(t);
        if (evalGraph != null) evalGraph.setTheme(t);
        outputPanel.note("Theme: " + t.name);
    }

    /* ----- Evaluation graph ----- */

    /** Lazy-create and show/hide the eval graph dialog. */
    private void setEvalGraphVisible(boolean show) {
        if (show) {
            if (evalDialog == null) {
                evalGraph = new EvalGraphPanel();
                evalGraph.setTheme(currentTheme);
                evalGraph.setClickListener(this::onEvalPointClicked);
                evalDialog = new JDialog(this, "Evaluation graph", false);
                evalDialog.setContentPane(evalGraph);
                evalDialog.setSize(560, 280);
                evalDialog.setLocationRelativeTo(this);
                evalDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
                // Sync the menu when the user closes the window directly.
                evalDialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override public void windowClosing(java.awt.event.WindowEvent e) {
                        evalGraphMenuItem.setSelected(false);
                    }
                });
            }
            evalDialog.setVisible(true);
        } else if (evalDialog != null) {
            evalDialog.setVisible(false);
        }
    }

    /** Click handler for points: in Annotate mode, jump to that ply. */
    private void onEvalPointClicked(int ply) {
        if (controller.mode() == GameController.Mode.ANNOTATE) {
            controller.annotateGoto(ply);
        }
    }

    /** Add a point to the graph, converting score to White's perspective.
     *  Engine scores are from the side-to-move's view; we negate when Black is
     *  to move so positive always means White-ahead. */
    private void addEvalPoint(int ply, byte sideToMove, int rawScore) {
        if (evalGraph == null) return;   // graph hasn't been opened yet
        int whiteScore = (sideToMove == Board.WHITE) ? rawScore : -rawScore;
        evalGraph.addPoint(new EvalGraphPanel.EvalPoint(ply, whiteScore));
    }

    /** Clear the graph (called on new game, mode change, etc). */
    private void clearEvalGraph() {
        if (evalGraph != null) evalGraph.clear();
    }

    /** Standard set of game tags that the dialog always exposes. The user can
     *  edit any existing values and optionally fill in blanks; saving with
     *  blank values just drops those tags. */
    private static final String[] STANDARD_TAGS = {
        "White", "Black", "Event", "Site", "Date", "Result"
    };

    /** Edit → Edit Tags: PGN-style metadata for the current game. */
    private void editTagsDialog() {
        java.util.LinkedHashMap<String, String> current = controller.tags();
        // Merge: start with standard tags (empty), overlay existing values.
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String name : STANDARD_TAGS) values.put(name, "");
        values.putAll(current);  // keeps any non-standard tags too

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        java.util.LinkedHashMap<String, JTextField> fields = new java.util.LinkedHashMap<>();
        int row = 0;
        for (var e : values.entrySet()) {
            gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
            form.add(new JLabel(e.getKey() + ":"), gc);
            gc.gridx = 1; gc.weightx = 1;
            JTextField tf = new JTextField(e.getValue(), 24);
            form.add(tf, gc);
            fields.put(e.getKey(), tf);
            row++;
        }

        int rc = JOptionPane.showConfirmDialog(this, form, "Edit tags",
                                               JOptionPane.OK_CANCEL_OPTION,
                                               JOptionPane.PLAIN_MESSAGE);
        if (rc != JOptionPane.OK_OPTION) return;

        java.util.LinkedHashMap<String, String> updated = new java.util.LinkedHashMap<>();
        for (var e : fields.entrySet()) updated.put(e.getKey(), e.getValue().getText());
        controller.setTags(updated);
        outputPanel.note("Tags updated.");
    }

    /* ----- Edit Position ----- */

    /** Build the south-bar widget for edit-position mode. Three radio buttons
     *  pick the palette, then action buttons for Clear/Reset/Flip/OK/Cancel. */
    private JPanel buildEditPosBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xA0, 0xA0, 0xA0)));

        JLabel place = new JLabel("Place:");
        ButtonGroup palette = new ButtonGroup();
        JRadioButton rbW = new JRadioButton("White", true);
        JRadioButton rbB = new JRadioButton("Black");
        JRadioButton rbE = new JRadioButton("Empty");
        rbW.addActionListener(e -> editPalette = Board.WHITE);
        rbB.addActionListener(e -> editPalette = Board.BLACK);
        rbE.addActionListener(e -> editPalette = Board.EMPTY);
        palette.add(rbW); palette.add(rbB); palette.add(rbE);
        bar.add(place); bar.add(rbW); bar.add(rbB); bar.add(rbE);

        bar.add(Box.createHorizontalStrut(8));
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(Box.createHorizontalStrut(8));

        JButton bFlip  = new JButton("Flip side to move");
        JButton bClear = new JButton("Clear board");
        JButton bReset = new JButton("Reset to start");
        bFlip .addActionListener(e -> controller.editFlipSideToMove());
        bClear.addActionListener(e -> controller.editClearBoard());
        bReset.addActionListener(e -> controller.editResetBoard());
        bar.add(bFlip); bar.add(bClear); bar.add(bReset);

        bar.add(Box.createHorizontalStrut(8));
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(Box.createHorizontalStrut(8));

        JButton bOk     = new JButton("OK");
        JButton bCancel = new JButton("Cancel");
        bOk    .addActionListener(e -> commitEditPosition());
        bCancel.addActionListener(e -> cancelEditPosition());
        bar.add(bOk); bar.add(bCancel);

        return bar;
    }

    /** Enter Edit Position mode: tell the controller, show the toolbar. */
    private void enterEditPosition() {
        outputPanel.note("Editing position");
        controller.enterEditPosition();
        editPosBar.setVisible(true);
        southStack.revalidate();
    }

    private void commitEditPosition() {
        controller.editCommit();
        editPosBar.setVisible(false);
        southStack.revalidate();
        outputPanel.note("Position committed (move history cleared)");
        // Restore the radio in the Mode menu since editCommit returns to PLAY.
        syncModeRadio();
    }

    private void cancelEditPosition() {
        controller.editCancel();
        editPosBar.setVisible(false);
        southStack.revalidate();
        outputPanel.note("Edit cancelled");
        syncModeRadio();
    }

    /** Route clicks: in EDIT_POSITION mode, place the palette piece on the
     *  clicked square; otherwise, normal click logic. */
    private void onBoardClick(int row, int col) {
        if (controller.mode() == GameController.Mode.EDIT_POSITION) {
            controller.editPlacePiece(row, col, editPalette);
        } else {
            controller.onClick(row, col);
        }
    }

    /** Render the app icon at several sizes by drawing a small board snapshot.
     *  Java/the desktop will pick the appropriate size for window title bar,
     *  taskbar, alt-tab, etc. We only render sizes large enough for pieces
     *  to actually be visible; the OS bilinearly downscales for small
     *  contexts (16x16 etc.). */
    private static java.util.List<java.awt.image.BufferedImage> buildAppIcons() {
        // Mid-game-ish position so the icon isn't just a checkerboard with two solid rows.
        Board b = Board.fromFen("OOOOOOOO/O1O1OOOO/2O5/4X3/2O1X3/8/XXX1XXXX/XXXXXXXX W");
        int[] sizes = { 64, 128, 256 };
        java.util.List<java.awt.image.BufferedImage> icons = new java.util.ArrayList<>();
        for (int s : sizes) {
            BoardPanel p = new BoardPanel();
            p.setSize(s, s);
            p.setBoard(b);
            p.setShowLabels(false);
            p.setLastMove(3 * 8 + 4, 4 * 8 + 4);  // e4 → e5, splash of yellow
            java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            p.paint(g);
            g.dispose();
            icons.add(img);
        }
        return icons;
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "Breakthrough GUI\n\nFront-end for the Breakthrough engine.\n"
          + "Click a piece to select it, then click a destination to move.",
            "About Breakthrough", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Open the Breakthrough rules (Wikipedia) in the user's default browser. */
    private void openRules() {
        final String url = "https://en.wikipedia.org/wiki/Breakthrough_(board_game)";
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                if (d.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    d.browse(new java.net.URI(url));
                    return;
                }
            }
            // Fallback: try xdg-open. Useful on minimal Linux setups where
            // Desktop.BROWSE isn't supported but xdg-open is available.
            new ProcessBuilder("xdg-open", url).inheritIO().start();
        } catch (Exception ex) {
            // Last resort: show the URL so the user can copy it manually.
            JOptionPane.showMessageDialog(this,
                "Could not open the browser automatically.\nURL:\n" + url,
                "Rules", JOptionPane.INFORMATION_MESSAGE);
        }
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
            // Tags are cleared inside loadGame (it calls newGame's reset path);
            // load and apply any present in the file *after* the move list.
            controller.setTags(GameReplay.loadTags(p));
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
            Path tmp = GameWriter.save(controller.playedMoves(), result, fen,
                                       p.getParent(), controller.tags());
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
    @Override public void annotateStateChanged(int ply, int totalPlies) {
        boolean active = (controller.mode() == GameController.Mode.ANNOTATE);
        annotateBar.setVisible(active);
        if (active) {
            // Move number = ceil(ply / 2); side is determined by ply parity.
            int moveNum = (ply + 1) / 2;
            String label;
            if (ply == 0)                       label = "before move 1";
            else if (ply >= totalPlies)         label = "end of game";
            else if ((ply & 1) == 1)            label = "after " + moveNum + ". White";
            else                                label = "after " + moveNum + "... Black";
            annotateLabel.setText("ply " + ply + " / " + totalPlies + "   (" + label + ")");
            btnStart.setEnabled(ply > 0);
            btnPrev .setEnabled(ply > 0);
            btnNext .setEnabled(ply < totalPlies);
            btnEnd  .setEnabled(ply < totalPlies);
            if (evalGraph != null) evalGraph.setCurrentPly(ply);
        } else {
            if (evalGraph != null) evalGraph.setCurrentPly(-1);
        }
        // revalidate so the toolbar appearing/disappearing is reflected.
        southStack.revalidate();
    }
    @Override public void annotateResult(int ply, Move played, Search.Result r, boolean agrees) {
        String tag = agrees ? "engine agrees" : ("engine prefers " + r.bestMove);
        String line = String.format("Ply %d: played %s — depth %d score %+d  (%s)",
                                    ply, played, r.depth, r.score, tag);
        outputPanel.note(line);
        // The annotated score is from the perspective of the side that played
        // this ply. Ply N: ply 1 = White played, ply 2 = Black played, etc.
        byte sideThatMoved = ((ply & 1) == 1) ? Board.WHITE : Board.BLACK;
        addEvalPoint(ply, sideThatMoved, r.score);
    }
    @Override public void engineMoveCompleted(int ply, byte side, Search.Result r) {
        addEvalPoint(ply, side, r.score);
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

    /* ----- Annotate Mode ----- */

    /** Build the annotate toolbar widget. Buttons fire controller methods;
     *  the label is updated by annotateStateChanged(). */
    private JPanel buildAnnotateBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xA0, 0xA0, 0xA0)));
        btnStart = new JButton("|<");  btnStart.setToolTipText("Go to start (Home)");
        btnPrev  = new JButton("<");   btnPrev.setToolTipText("Previous ply (\u2190)");
        btnNext  = new JButton(">");   btnNext.setToolTipText("Next ply (\u2192)");
        btnEnd   = new JButton(">|");  btnEnd.setToolTipText("Go to end (End)");
        annotateLabel = new JLabel(" ");
        annotateLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

        btnStart.addActionListener(e -> controller.annotateGoto(0));
        btnPrev .addActionListener(e -> controller.annotateStep(-1));
        btnNext .addActionListener(e -> controller.annotateStep(+1));
        btnEnd  .addActionListener(e -> controller.annotateGoto(controller.annotateTotal()));

        bar.add(btnStart);
        bar.add(btnPrev);
        bar.add(annotateLabel);
        bar.add(btnNext);
        bar.add(btnEnd);
        return bar;
    }

    /** Install keyboard shortcuts for stepping. Wired into the frame's root
     *  pane so they fire regardless of which child has focus, but only when
     *  we're in Annotate Mode. */
    private void installAnnotateKeys() {
        JRootPane rp = getRootPane();
        InputMap  im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = rp.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,  0), "ann-prev");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "ann-next");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME,  0), "ann-start");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END,   0), "ann-end");
        am.put("ann-prev",  new AbstractAction() { public void actionPerformed(ActionEvent e) {
            if (controller.mode() == GameController.Mode.ANNOTATE) controller.annotateStep(-1);
        }});
        am.put("ann-next",  new AbstractAction() { public void actionPerformed(ActionEvent e) {
            if (controller.mode() == GameController.Mode.ANNOTATE) controller.annotateStep(+1);
        }});
        am.put("ann-start", new AbstractAction() { public void actionPerformed(ActionEvent e) {
            if (controller.mode() == GameController.Mode.ANNOTATE) controller.annotateGoto(0);
        }});
        am.put("ann-end",   new AbstractAction() { public void actionPerformed(ActionEvent e) {
            if (controller.mode() == GameController.Mode.ANNOTATE) controller.annotateGoto(controller.annotateTotal());
        }});
    }

    /** Handle Mode → Annotate Game: open a file chooser, load the game,
     *  enter annotate mode. If the user cancels or the file is invalid, the
     *  previous mode is restored. */
    private void enterAnnotateMode() {
        JFileChooser fc = fileChooser(gameStartDir(), "Breakthrough game (.game)", "game");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            // User cancelled — restore previous radio selection.
            syncModeRadio();
            return;
        }
        Path p = fc.getSelectedFile().toPath();
        lastGameDir = p.getParent();
        try {
            java.util.List<Move> moves = GameReplay.loadMoves(p);
            if (moves.isEmpty()) {
                error("Game file is empty — nothing to annotate.");
                syncModeRadio();
                return;
            }
            outputPanel.note("Mode: Annotate (" + p.getFileName() + ", " + moves.size() + " plies)");
            clearEvalGraph();
            controller.enterAnnotate(moves);
            setTitle("Breakthrough — Annotate: " + p.getFileName());
        } catch (IOException ex) {
            error("Could not read file: " + ex.getMessage());
            syncModeRadio();
        } catch (IllegalArgumentException ex) {
            error("Bad game file: " + ex.getMessage());
            syncModeRadio();
        }
    }

    /** Re-select the radio button corresponding to the controller's actual mode.
     *  Used after a failed Annotate entry to undo the radio's visual change. */
    private void syncModeRadio() {
        switch (controller.mode()) {
            case PLAY -> {
                GameController.Side w = controller.whiteSide(), b = controller.blackSide();
                if      (w == GameController.Side.ENGINE && b == GameController.Side.HUMAN)  modeMP1.setSelected(true);
                else if (w == GameController.Side.HUMAN  && b == GameController.Side.ENGINE) modeMP2.setSelected(true);
                else                                                                          modeTwoMachines.setSelected(true);
            }
            case ANALYSE  -> modeAnalyse.setSelected(true);
            case ANNOTATE -> modeAnnotate.setSelected(true);
        }
    }

    /* ----- entry point ----- */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {}
        SwingUtilities.invokeLater(() -> new Gui().setVisible(true));
    }
}
