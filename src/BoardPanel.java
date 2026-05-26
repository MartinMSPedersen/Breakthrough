import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

/**
 * Custom-painted Breakthrough board.
 *
 * Pure view layer: holds no game state of its own. The controller hands it
 * a Board snapshot (via setBoard), an optional "selected square" and an
 * optional set of "highlight destinations" (legal moves from the selected
 * square), plus an optional last-move pair to draw faintly. The panel paints
 * those and converts mouse clicks back into (row, col) and forwards them to
 * a listener.
 *
 * Flip-view: if flipped, row 0 is drawn at the top instead of the bottom.
 * Coordinate conversion happens only at the paint/click boundary so the
 * model coordinates stay canonical.
 */
public class BoardPanel extends JPanel {

    public interface SquareClickListener { void squareClicked(int row, int col); }

    /* ----- theme ----- */
    private Theme theme = Theme.CLASSIC;
    public void setTheme(Theme t) {
        this.theme = t;
        setBackground(t.panelBg);
        repaint();
    }
    public Theme theme() { return theme; }

    /* ----- model snapshot ----- */
    private Board       board       = Board.initial();
    private int         selectedSq  = -1;      // -1 = nothing selected
    private long        highlights  = 0L;      // bitboard of legal destinations
    private int         lastFromSq  = -1;
    private int         lastToSq    = -1;
    private boolean     flipped     = false;
    private boolean     showLabels  = true;

    /* ----- drag state -----
     * Two phases of a mouse-down gesture:
     *   1. Pressed:  pressSq != -1, dragging == false.  Click on release plays
     *                through as a normal click-select / click-move.
     *   2. Dragging: pressSq != -1, dragging == true.   The piece on pressSq
     *                is hidden during paint and a floating piece tracks the
     *                cursor (dragX, dragY). Release acts on the destination
     *                square (or snap back if dropped outside the board / on
     *                an illegal square).
     * The threshold (in pixels) keeps tiny mouse jitter during a click from
     * being misclassified as a drag.
     */
    private static final int DRAG_THRESHOLD = 5;
    private int         pressSq     = -1;
    private int         pressX, pressY;
    private boolean     dragging    = false;
    private int         dragX, dragY;

    private SquareClickListener listener;

    public BoardPanel() {
        setPreferredSize(new Dimension(560, 560));
        setBackground(theme.panelBg);
        MouseAdapter h = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int sq = squareAt(e.getX(), e.getY());
                if (sq < 0) return;
                pressSq = sq;
                pressX  = e.getX();
                pressY  = e.getY();
                dragging = false;
                // Notify the controller of the press as a "click" — this
                // selects the piece (or deselects, depending on state).
                if (listener != null) listener.squareClicked(sq >>> 3, sq & 7);
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pressSq < 0) return;
                dragX = e.getX();
                dragY = e.getY();
                if (!dragging) {
                    int dx = dragX - pressX, dy = dragY - pressY;
                    if (dx*dx + dy*dy >= DRAG_THRESHOLD * DRAG_THRESHOLD) {
                        // Only enter drag mode if a real piece-of-our-own was
                        // selected by the press. If the controller didn't pick
                        // up the press (selectedSq still -1), this is not a
                        // valid drag — ignore.
                        if (selectedSq == pressSq) {
                            dragging = true;
                            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        }
                    }
                }
                if (dragging) repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                int releaseSq = squareAt(e.getX(), e.getY());
                boolean wasDragging = dragging;
                int srcSq = pressSq;
                pressSq = -1;
                dragging = false;
                setCursor(Cursor.getDefaultCursor());
                if (wasDragging) {
                    if (releaseSq == srcSq || releaseSq < 0) {
                        // Dropped on source or outside the board: just snap
                        // back. The controller still has selectedSq == srcSq;
                        // we leave it that way so the user can click a
                        // destination if they want.
                        repaint();
                        return;
                    }
                    // Dropped elsewhere — communicate as a click on the
                    // destination. The controller decides whether it's legal.
                    if (listener != null) listener.squareClicked(releaseSq >>> 3, releaseSq & 7);
                }
                // If !wasDragging, mousePressed already fired the click;
                // a plain release does nothing further.
            }
            @Override public void mouseMoved(MouseEvent e) {
                int sq = squareAt(e.getX(), e.getY());
                Cursor c = Cursor.getDefaultCursor();
                if (sq >= 0) {
                    byte p = board.get(sq >>> 3, sq & 7);
                    if (p == board.side()) c = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
                }
                if (getCursor().getType() != c.getType()) setCursor(c);
            }
        };
        addMouseListener(h);
        addMouseMotionListener(h);
    }

    public void setBoard(Board b)                  { this.board = b; repaint(); }
    public void setSelected(int row, int col)      { this.selectedSq = (row < 0) ? -1 : row * 8 + col; repaint(); }
    public void clearSelection()                   { this.selectedSq = -1; this.highlights = 0L; repaint(); }
    public void setHighlights(long bb)             { this.highlights = bb; repaint(); }
    public void setLastMove(int fromSq, int toSq)  { this.lastFromSq = fromSq; this.lastToSq = toSq; repaint(); }
    public void clearLastMove()                    { this.lastFromSq = -1; this.lastToSq = -1; repaint(); }
    public void setShowLabels(boolean s)           { this.showLabels = s; repaint(); }
    public boolean isShowingLabels()               { return showLabels; }
    public void setFlipped(boolean f)              { this.flipped = f; repaint(); }
    public boolean isFlipped()                     { return flipped; }
    public void setClickListener(SquareClickListener l) { this.listener = l; }

    /* ----- painting ----- */

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        int side = Math.min(w, h);
        int margin = side / 16;
        int boardSide = side - 2 * margin;
        int cell = boardSide / 8;
        int boardSize = cell * 8;
        int x0 = (w - boardSize) / 2;
        int y0 = (h - boardSize) / 2;

        // Squares
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int sx = x0 + c * cell;
                int sy = y0 + (flipped ? r : (7 - r)) * cell;
                g.setColor(((r + c) & 1) == 0 ? theme.darkSq : theme.lightSq);
                g.fillRect(sx, sy, cell, cell);
            }
        }

        // Last-move highlight
        if (lastFromSq >= 0) paintSquareOverlay(g, lastFromSq, x0, y0, cell, theme.lastMv);
        if (lastToSq   >= 0) paintSquareOverlay(g, lastToSq,   x0, y0, cell, theme.lastMv);

        // Selected square
        if (selectedSq >= 0) paintSquareOverlay(g, selectedSq, x0, y0, cell, theme.selSq);

        // Highlighted destinations
        long bb = highlights;
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            paintSquareOverlay(g, sq, x0, y0, cell, theme.hlDest);
            bb &= bb - 1L;
        }

        // Pieces. While dragging, suppress the piece on the source square
        // (we'll paint a floating copy at the cursor instead).
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                byte p = board.get(r, c);
                if (p == Board.EMPTY) continue;
                int thisSq = r * 8 + c;
                if (dragging && thisSq == pressSq) continue;
                int sx = x0 + c * cell;
                int sy = y0 + (flipped ? r : (7 - r)) * cell;
                int pad = Math.max(4, cell / 8);
                int d = cell - 2 * pad;
                g.setColor(p == Board.WHITE ? theme.whitePc : theme.blackPc);
                g.fillOval(sx + pad, sy + pad, d, d);
                g.setColor(theme.pcEdge);
                g.setStroke(new BasicStroke(Math.max(1f, cell / 28f)));
                g.drawOval(sx + pad, sy + pad, d, d);
            }
        }

        // File/rank labels — drawn in the margin around the board.
        // The margin is always present so toggling labels doesn't resize the board.
        if (showLabels) {
            g.setColor(theme.label);
            g.setFont(getFont().deriveFont(Font.PLAIN, Math.max(10f, cell / 5f)));
            FontMetrics fm = g.getFontMetrics();
            for (int c = 0; c < 8; c++) {
                String s = String.valueOf((char) ('a' + c));
                int sx = x0 + c * cell + (cell - fm.stringWidth(s)) / 2;
                g.drawString(s, sx, y0 + boardSize + fm.getAscent() + 2);
            }
            for (int r = 0; r < 8; r++) {
                String s = String.valueOf(r + 1);
                int sy = y0 + (flipped ? r : (7 - r)) * cell + (cell + fm.getAscent()) / 2 - 2;
                g.drawString(s, x0 - fm.stringWidth(s) - 4, sy);
            }
        }

        // Floating drag piece. Drawn last so it appears on top of squares,
        // overlays, and other pieces. Centered on the cursor.
        if (dragging && pressSq >= 0) {
            int r = pressSq >>> 3, c = pressSq & 7;
            byte p = board.get(r, c);
            if (p != Board.EMPTY) {
                int pad = Math.max(4, cell / 8);
                int d   = cell - 2 * pad;
                int dx  = dragX - d / 2;
                int dy  = dragY - d / 2;
                g.setColor(p == Board.WHITE ? theme.whitePc : theme.blackPc);
                g.fillOval(dx, dy, d, d);
                g.setColor(theme.pcEdge);
                g.setStroke(new BasicStroke(Math.max(1f, cell / 28f)));
                g.drawOval(dx, dy, d, d);
            }
        }

        g.dispose();
    }

    private void paintSquareOverlay(Graphics2D g, int sq, int x0, int y0, int cell, Color c) {
        int r = sq >>> 3, col = sq & 7;
        int sx = x0 + col * cell;
        int sy = y0 + (flipped ? r : (7 - r)) * cell;
        g.setColor(c);
        g.fillRect(sx, sy, cell, cell);
    }

    /* ----- click handling ----- */

    /** Convert mouse coordinates to a square index 0..63, or -1 if outside the board. */
    private int squareAt(int mx, int my) {
        int w = getWidth(), h = getHeight();
        int side = Math.min(w, h);
        int margin = side / 16;
        int boardSide = side - 2 * margin;
        int cell = boardSide / 8;
        int boardSize = cell * 8;
        int x0 = (w - boardSize) / 2;
        int y0 = (h - boardSize) / 2;

        int cx = mx - x0, cy = my - y0;
        if (cx < 0 || cy < 0 || cx >= boardSize || cy >= boardSize) return -1;
        int col = cx / cell;
        int rowFromTop = cy / cell;
        int row = flipped ? rowFromTop : (7 - rowFromTop);
        return row * 8 + col;
    }
}
