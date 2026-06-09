import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom-painted graph of evaluation across plies.
 *
 * Data is held as a list of {@link EvalPoint} ({ply, score}); scores are
 * always from White's perspective (positive = White ahead). The graph is
 * static — no zoom, no pan — but in Annotate Mode the user can click a
 * point to jump to that ply.
 *
 * Mate scores get clamped for display (otherwise a single +99000 flattens
 * the rest of the line into nothing visible).
 */
public class EvalGraphPanel extends JPanel {

    /** Single data point on the graph. score is always from White's perspective. */
    public static final class EvalPoint {
        public final int ply;
        public final int score;
        public EvalPoint(int ply, int score) { this.ply = ply; this.score = score; }
    }

    /** Click handler — receives the ply number of the clicked point. Used in
     *  Annotate Mode to jump there; ignored otherwise. */
    public interface PointClickListener { void onPointClicked(int ply); }

    /** Minimum displayed range — so quiet games don't look like a flat line. */
    private static final int MIN_RANGE = 1000;
    /** Mate-threshold cutoff: anything past this is treated as a mate.
     *  Mate scores are clamped to the displayed range, so they sit at the
     *  axis edge regardless of how the range auto-fits. */
    private static final int MATE_THRESHOLD = 99000;

    private final List<EvalPoint>   points = new ArrayList<>();
    private int                     currentPly = -1;   // -1 = don't draw a cursor
    private Theme                   theme = Theme.CLASSIC;
    private PointClickListener      listener;
    private int                     maxPly = 60;       // x-axis extent
    /** Auto-fit Y-axis range. Recomputed when points change. Always symmetric. */
    private int                     yRange = MIN_RANGE;

    public EvalGraphPanel() {
        setPreferredSize(new Dimension(480, 240));
        setBackground(theme.outputBg);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (listener == null || points.isEmpty()) return;
                // Hit-test: which point is closest within 12 pixels?
                int bestPly = -1;
                int bestDist = Integer.MAX_VALUE;
                for (EvalPoint p : points) {
                    Point pt = plotPos(p);
                    int dx = pt.x - e.getX(), dy = pt.y - e.getY();
                    int dist = dx*dx + dy*dy;
                    if (dist < bestDist && dist <= 12*12) {
                        bestDist = dist;
                        bestPly = p.ply;
                    }
                }
                if (bestPly >= 0) listener.onPointClicked(bestPly);
            }
        });
    }

    public void setTheme(Theme t)                       { this.theme = t; setBackground(t.outputBg); repaint(); }
    public void setClickListener(PointClickListener l)  { this.listener = l; }
    public void setCurrentPly(int ply)                  { this.currentPly = ply; repaint(); }

    /** Replace all data points. Adjusts X-axis extent if needed. */
    public void setPoints(List<EvalPoint> pts) {
        this.points.clear();
        this.points.addAll(pts);
        int max = 60;
        for (EvalPoint p : pts) if (p.ply > max) max = p.ply;
        this.maxPly = Math.max(60, ((max + 19) / 20) * 20);
        recomputeYRange();
        repaint();
    }

    public void addPoint(EvalPoint p) {
        // If there's already a point for this ply (e.g. annotate revisit),
        // replace it rather than duplicate.
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).ply == p.ply) {
                points.set(i, p);
                recomputeYRange();
                repaint();
                return;
            }
        }
        points.add(p);
        if (p.ply > maxPly) maxPly = ((p.ply + 19) / 20) * 20;
        recomputeYRange();
        repaint();
    }

    public void clear() {
        points.clear();
        currentPly = -1;
        maxPly = 60;
        yRange = MIN_RANGE;
        repaint();
    }

    /** Pick a Y-axis range that fits the data (excluding mate scores).
     *  Rounds up to a nice number so the axis labels are readable; the range
     *  never shrinks below MIN_RANGE so a quiet game still shows detail. */
    private void recomputeYRange() {
        int peak = 0;
        for (EvalPoint p : points) {
            if (Math.abs(p.score) >= MATE_THRESHOLD) continue;  // ignore mates
            int abs = Math.abs(p.score);
            if (abs > peak) peak = abs;
        }
        if (peak <= MIN_RANGE)        yRange = MIN_RANGE;
        else if (peak <= 2000)        yRange = 2000;
        else if (peak <= 5000)        yRange = 5000;
        else if (peak <= 10000)       yRange = 10000;
        else                          yRange = ((peak + 4999) / 5000) * 5000;
    }

    /** Convert a data point to its pixel position. */
    private Point plotPos(EvalPoint p) {
        Insets m = plotMargins();
        int w = getWidth(), h = getHeight();
        int plotW = w - m.left - m.right;
        int plotH = h - m.top  - m.bottom;
        int s = clampScore(p.score);
        int x = m.left + (int) ((double)(p.ply) / Math.max(1, maxPly) * plotW);
        // Y: zero at the middle. Positive = up = White advantage.
        int y = m.top + plotH / 2 - (int) ((double) s / yRange * (plotH / 2));
        return new Point(x, y);
    }

    private Insets plotMargins() { return new Insets(10, 48, 22, 10); }

    /** Clamp a raw score to the displayed range. Mate scores get pinned to
     *  the extreme (so they're at the axis edge regardless of yRange). */
    private int clampScore(int s) {
        if (s >  MATE_THRESHOLD) return  yRange;
        if (s < -MATE_THRESHOLD) return -yRange;
        if (s >  yRange) return  yRange;
        if (s < -yRange) return -yRange;
        return s;
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        Insets m = plotMargins();
        int plotW = w - m.left - m.right;
        int plotH = h - m.top  - m.bottom;
        int midY  = m.top + plotH / 2;
        int leftX = m.left, rightX = m.left + plotW;
        int topY  = m.top, botY = m.top + plotH;

        // Axis labels colour: use the panel foreground (themable).
        Color axisColor = theme.outputFg;

        // Grid lines at ±¼, ±½ of yRange (no line at ±full, since that's the panel edge).
        // The zero line is drawn last with extra emphasis.
        int[] gridFractions = { -2, -1, 1, 2 };  // multiples of yRange/4
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(axisColor.getRed(), axisColor.getGreen(), axisColor.getBlue(), 64));
        for (int k : gridFractions) {
            int y = midY - (int) ((double) k / 4 * (plotH / 2));
            g.drawLine(leftX, y, rightX, y);
        }
        // Zero line: stronger.
        g.setColor(new Color(axisColor.getRed(), axisColor.getGreen(), axisColor.getBlue(), 160));
        g.drawLine(leftX, midY, rightX, midY);

        // Y-axis labels at ±full, ±½, 0.
        g.setColor(axisColor);
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g.getFontMetrics();
        int[] labelFractions = { -4, -2, 0, 2, 4 };  // -yRange, -yRange/2, 0, +yRange/2, +yRange
        for (int k : labelFractions) {
            int score = (yRange * k) / 4;
            int y = midY - (int) ((double) k / 4 * (plotH / 2));
            String txt = formatScore(score);
            g.drawString(txt, leftX - fm.stringWidth(txt) - 4, y + fm.getAscent() / 2 - 1);
        }
        // X-axis label.
        String xlab = "ply";
        g.drawString(xlab, leftX - fm.stringWidth(xlab) - 4, botY + fm.getAscent() + 4);
        // X-axis ticks every 10 plies.
        for (int p = 10; p <= maxPly; p += 10) {
            int x = leftX + (int) ((double) p / maxPly * plotW);
            g.drawLine(x, botY, x, botY + 4);
            String t = String.valueOf(p);
            g.drawString(t, x - fm.stringWidth(t) / 2, botY + fm.getAscent() + 4);
        }

        // Current-ply marker (vertical line).
        if (currentPly >= 0 && currentPly <= maxPly) {
            int x = leftX + (int) ((double) currentPly / maxPly * plotW);
            g.setColor(new Color(0xFF, 0xC1, 0x07, 0xB0));  // amber
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(x, topY, x, botY);
        }

        // Data line.
        if (points.size() >= 2) {
            g.setColor(axisColor);
            g.setStroke(new BasicStroke(1.8f));
            Point prev = plotPos(points.get(0));
            for (int i = 1; i < points.size(); i++) {
                Point cur = plotPos(points.get(i));
                g.drawLine(prev.x, prev.y, cur.x, cur.y);
                prev = cur;
            }
        }
        // Data points.
        for (EvalPoint p : points) {
            Point pt = plotPos(p);
            // Slightly different colors for mate scores so they stand out.
            boolean mate = Math.abs(p.score) >= MATE_THRESHOLD;
            g.setColor(mate ? new Color(0xE5, 0x39, 0x35) : axisColor);
            g.fillOval(pt.x - 3, pt.y - 3, 6, 6);
        }

        g.dispose();
    }

    /** Currently-applied Y-axis range; useful for tests. */
    int yRangeForTest() { return yRange; }

    /** Format a raw score for axis labels. Compact form ("+1.5k") keeps the
     *  label column narrow. */
    private static String formatScore(int s) {
        if (s == 0) return "0";
        String sign = s > 0 ? "+" : "-";
        int abs = Math.abs(s);
        if (abs >= 1000) {
            if (abs % 1000 == 0) return sign + (abs / 1000) + "k";
            return sign + String.format("%.1fk", abs / 1000.0);
        }
        return sign + abs;
    }
}
