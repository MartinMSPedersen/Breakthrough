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

    /* ----- colors ----- */
    private static final Color  LIGHT_SQ  = new Color(0xEED9B5);
    private static final Color  DARK_SQ   = new Color(0xB58863);
    private static final Color  SEL_SQ    = new Color(0x6BAED6, true);
    private static final Color  HL_DEST   = new Color(0x66BB6A, true);
    private static final Color  LAST_MV   = new Color(0xFFEB3B, true);
    private static final Color  WHITE_PC  = new Color(0xF5F5F5);
    private static final Color  BLACK_PC  = new Color(0x2E2E2E);
    private static final Color  PC_EDGE   = new Color(0x202020);
    private static final Color  LABEL     = new Color(0x303030);

    /* ----- model snapshot ----- */
    private Board       board       = Board.initial();
    private int         selectedSq  = -1;      // -1 = nothing selected
    private long        highlights  = 0L;      // bitboard of legal destinations
    private int         lastFromSq  = -1;
    private int         lastToSq    = -1;
    private boolean     flipped     = false;

    private SquareClickListener listener;

    public BoardPanel() {
        setPreferredSize(new Dimension(560, 560));
        setBackground(new Color(0x303030));
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { onClick(e.getX(), e.getY()); }
        });
    }

    public void setBoard(Board b)                  { this.board = b; repaint(); }
    public void setSelected(int row, int col)      { this.selectedSq = (row < 0) ? -1 : row * 8 + col; repaint(); }
    public void clearSelection()                   { this.selectedSq = -1; this.highlights = 0L; repaint(); }
    public void setHighlights(long bb)             { this.highlights = bb; repaint(); }
    public void setLastMove(int fromSq, int toSq)  { this.lastFromSq = fromSq; this.lastToSq = toSq; repaint(); }
    public void clearLastMove()                    { this.lastFromSq = -1; this.lastToSq = -1; repaint(); }
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
                g.setColor(((r + c) & 1) == 0 ? DARK_SQ : LIGHT_SQ);
                g.fillRect(sx, sy, cell, cell);
            }
        }

        // Last-move highlight
        if (lastFromSq >= 0) paintSquareOverlay(g, lastFromSq, x0, y0, cell, LAST_MV);
        if (lastToSq   >= 0) paintSquareOverlay(g, lastToSq,   x0, y0, cell, LAST_MV);

        // Selected square
        if (selectedSq >= 0) paintSquareOverlay(g, selectedSq, x0, y0, cell, SEL_SQ);

        // Highlighted destinations
        long bb = highlights;
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            paintSquareOverlay(g, sq, x0, y0, cell, HL_DEST);
            bb &= bb - 1L;
        }

        // Pieces
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                byte p = board.get(r, c);
                if (p == Board.EMPTY) continue;
                int sx = x0 + c * cell;
                int sy = y0 + (flipped ? r : (7 - r)) * cell;
                int pad = Math.max(4, cell / 8);
                int d = cell - 2 * pad;
                g.setColor(p == Board.WHITE ? WHITE_PC : BLACK_PC);
                g.fillOval(sx + pad, sy + pad, d, d);
                g.setColor(PC_EDGE);
                g.setStroke(new BasicStroke(Math.max(1f, cell / 28f)));
                g.drawOval(sx + pad, sy + pad, d, d);
            }
        }

        // File/rank labels
        g.setColor(LABEL);
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

    private void onClick(int mx, int my) {
        int w = getWidth(), h = getHeight();
        int side = Math.min(w, h);
        int margin = side / 16;
        int boardSide = side - 2 * margin;
        int cell = boardSide / 8;
        int boardSize = cell * 8;
        int x0 = (w - boardSize) / 2;
        int y0 = (h - boardSize) / 2;

        int cx = mx - x0, cy = my - y0;
        if (cx < 0 || cy < 0 || cx >= boardSize || cy >= boardSize) return;
        int col = cx / cell;
        int rowFromTop = cy / cell;
        int row = flipped ? rowFromTop : (7 - rowFromTop);
        if (listener != null) listener.squareClicked(row, col);
    }
}
