import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Right-side panel showing each engine's latest progress lines.
 *
 * Each line is prefixed with a timestamp and an MP1/MP2 tag so two-machine
 * games are readable. We auto-scroll to the bottom on every append so the
 * latest line is always visible.
 *
 * Capped at ~MAX_LINES; older lines are dropped. This keeps memory bounded
 * during long engine-vs-engine sessions.
 */
public class EngineOutputPanel extends JPanel {

    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextArea area = new JTextArea();
    private int lineCount = 0;

    public EngineOutputPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Engine output"));

        area.setEditable(false);
        area.setLineWrap(false);
        // Monospace font: the engine lines have aligned columns that read
        // much better with a fixed-width font.
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setMargin(new Insets(4, 6, 4, 6));
        setTheme(Theme.CLASSIC);

        JScrollPane sp = new JScrollPane(area,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setPreferredSize(new Dimension(360, 540));
        add(sp, BorderLayout.CENTER);

        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> clear());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        buttons.add(clear);
        add(buttons, BorderLayout.SOUTH);
    }

    /** Apply a theme. Affects background and text colors of the output area. */
    public void setTheme(Theme t) {
        area.setBackground(t.outputBg);
        area.setForeground(t.outputFg);
        area.setCaretColor(t.outputFg);
    }

    /** Append a labeled engine line. Must be called on the EDT. */
    public void append(byte side, String line) {
        String label = (side == Board.WHITE) ? "MP1" : "MP2";
        String stamp = LocalTime.now().format(TS_FMT);
        area.append(stamp + "  " + label + "  " + line + "\n");
        lineCount++;
        if (lineCount > MAX_LINES) {
            // Drop the oldest ~100 lines in one shot so trimming doesn't run
            // on every single append.
            try {
                int cutAt = area.getLineEndOffset(lineCount - MAX_LINES);
                area.replaceRange("", 0, cutAt);
                lineCount = MAX_LINES;
            } catch (javax.swing.text.BadLocationException ignore) {}
        }
        // Auto-scroll to bottom.
        area.setCaretPosition(area.getDocument().getLength());
    }

    /** Append a free-form note (e.g. "New game started"). */
    public void note(String text) {
        String stamp = LocalTime.now().format(TS_FMT);
        area.append(stamp + "  --   " + text + "\n");
        lineCount++;
        area.setCaretPosition(area.getDocument().getLength());
    }

    public void clear() {
        area.setText("");
        lineCount = 0;
    }
}
