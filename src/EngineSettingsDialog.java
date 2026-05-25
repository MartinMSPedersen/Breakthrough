import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog for editing one engine's settings (depth, TT bits, weights,
 * defender scale). On OK, validates the input and either returns the new
 * EngineSettings or shows an error and stays open.
 *
 * Usage:
 *   EngineSettings result = EngineSettingsDialog.show(parent, "Machine Player 1", current);
 *   if (result != null) { ... }   // null = user cancelled
 */
public final class EngineSettingsDialog {

    private EngineSettingsDialog() {}

    public static EngineSettings show(Component parent, String title, EngineSettings current) {
        JSpinner   depthSpin    = new JSpinner(new SpinnerNumberModel(current.depth, 1, 15, 1));
        JSpinner   ttSpin       = new JSpinner(new SpinnerNumberModel(current.ttBits, 16, 26, 1));
        JTextField weightsField = new JTextField(current.weightsSpec(), 30);
        JSpinner   dsSpin       = new JSpinner(new SpinnerNumberModel(
                                       current.defenderScale, 0.0, 2.0, 0.05));
        JLabel     ttRamLabel   = new JLabel(ramHint(current.ttBits));

        ttSpin.addChangeListener(e ->
            ttRamLabel.setText(ramHint((Integer) ttSpin.getValue())));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(form, g, row++, "Search depth:",
               depthSpin, "Higher = stronger, slower. 6 is interactive; 8-10 is strong.");
        addRow(form, g, row++, "TT size (bits):",
               ttSpin, null);
        // RAM hint sits under the TT spinner on its own row to keep the form tidy
        g.gridx = 1; g.gridy = row++; g.gridwidth = 2;
        g.fill = GridBagConstraints.HORIZONTAL;
        form.add(ttRamLabel, g);
        g.gridwidth = 1; g.fill = GridBagConstraints.NONE;

        addRow(form, g, row++, "Evaluator weights:",
               weightsField, "Eight comma-separated integers (advancement weights per row).");
        addRow(form, g, row++, "Defender scale:",
               dsSpin, "Per-defender eval bonus multiplier. 0 disables; typical 0.05-0.30.");

        // Loop until user enters valid input or cancels.
        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, form, title,
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            try {
                int[] w = EngineSettings.parseWeights(weightsField.getText());
                int depth  = (Integer) depthSpin.getValue();
                int ttBits = (Integer) ttSpin.getValue();
                double ds  = ((Number) dsSpin.getValue()).doubleValue();
                return new EngineSettings(depth, ttBits, w, ds);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(parent,
                    "Invalid input: " + ex.getMessage(),
                    "Settings error", JOptionPane.ERROR_MESSAGE);
                // Loop and show the dialog again with current values preserved.
            }
        }
    }

    private static void addRow(JPanel p, GridBagConstraints g, int row,
                               String label, JComponent field, String tip) {
        g.gridx = 0; g.gridy = row; g.gridwidth = 1;
        g.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1.0;
        if (tip != null) field.setToolTipText(tip);
        p.add(field, g);
        g.weightx = 0;
    }

    /** Human-friendly RAM hint for a given TT-bits value (32 bytes/entry). */
    private static String ramHint(int bits) {
        long entries = 1L << bits;
        long bytes   = entries * 32L;
        String size;
        if      (bytes >= 1L << 30) size = String.format("%.1f GB", bytes / (double) (1L << 30));
        else if (bytes >= 1L << 20) size = String.format("%.0f MB", bytes / (double) (1L << 20));
        else                        size = String.format("%.0f KB", bytes / (double) (1L << 10));
        return String.format("    \u2192 %s entries, ~%s",
                             entries >= 1_000_000 ? (entries / 1_000_000) + "M"
                                                   : (entries / 1024) + "K",
                             size);
    }
}
