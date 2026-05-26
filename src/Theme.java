import java.awt.Color;

/**
 * Bundle of all themable colors used across the GUI.
 *
 * Instances are immutable — to switch themes, swap in a new Theme instance
 * via {@link BoardPanel#setTheme} and {@link EngineOutputPanel#setTheme}.
 *
 * Color channels are in the standard sRGB space; overlays use explicit
 * alpha so the underlying square color shows through.
 */
public final class Theme {

    public final String name;

    /* board */
    public final Color panelBg;       // dark frame around the board
    public final Color lightSq;
    public final Color darkSq;
    public final Color selSq;         // selection overlay (semi-opaque)
    public final Color hlDest;        // legal-destination overlay (semi-opaque)
    public final Color lastMv;        // last-move overlay (semi-opaque)
    public final Color whitePc;
    public final Color blackPc;
    public final Color whiteEdge;     // outline around white pieces
    public final Color blackEdge;     // outline around black pieces
    public final Color label;         // coordinate text

    /* engine output panel */
    public final Color outputBg;
    public final Color outputFg;

    /** Both edges the same color (most themes). */
    public Theme(String name,
                 Color panelBg, Color lightSq, Color darkSq,
                 Color selSq, Color hlDest, Color lastMv,
                 Color whitePc, Color blackPc, Color pcEdge,
                 Color label,
                 Color outputBg, Color outputFg) {
        this(name, panelBg, lightSq, darkSq, selSq, hlDest, lastMv,
             whitePc, blackPc, pcEdge, pcEdge, label, outputBg, outputFg);
    }

    /** Per-piece edge colors (for themes where they need to differ, like High Contrast). */
    public Theme(String name,
                 Color panelBg, Color lightSq, Color darkSq,
                 Color selSq, Color hlDest, Color lastMv,
                 Color whitePc, Color blackPc,
                 Color whiteEdge, Color blackEdge,
                 Color label,
                 Color outputBg, Color outputFg) {
        this.name      = name;
        this.panelBg   = panelBg;
        this.lightSq   = lightSq;
        this.darkSq    = darkSq;
        this.selSq     = selSq;
        this.hlDest    = hlDest;
        this.lastMv    = lastMv;
        this.whitePc   = whitePc;
        this.blackPc   = blackPc;
        this.whiteEdge = whiteEdge;
        this.blackEdge = blackEdge;
        this.label     = label;
        this.outputBg  = outputBg;
        this.outputFg  = outputFg;
    }

    /** The default colors — matches what the GUI looked like before themes existed. */
    public static final Theme CLASSIC = new Theme(
        "Classic",
        new Color(0x30, 0x30, 0x30),      // panelBg: dark gray frame
        new Color(0xEE, 0xD9, 0xB5),      // lightSq: cream
        new Color(0xB5, 0x88, 0x63),      // darkSq:  warm brown
        new Color(0x6B, 0xAE, 0xD6, 0xB0),// selSq:   sky blue
        new Color(0x66, 0xBB, 0x6A, 0xA0),// hlDest:  green
        new Color(0xFF, 0xEB, 0x3B, 0x80),// lastMv:  yellow
        new Color(0xF5, 0xF5, 0xF5),      // whitePc
        new Color(0x2E, 0x2E, 0x2E),      // blackPc
        new Color(0x20, 0x20, 0x20),      // pcEdge
        new Color(0xC8, 0xC8, 0xC8),      // label
        new Color(0x1E, 0x1E, 0x1E),      // outputBg
        new Color(0xE0, 0xE0, 0xE0)       // outputFg
    );

    /** Cool gray-blue palette; modern look. */
    public static final Theme SLATE = new Theme(
        "Slate",
        new Color(0x1F, 0x29, 0x37),      // panelBg
        new Color(0xCB, 0xD5, 0xE1),      // lightSq
        new Color(0x47, 0x55, 0x69),      // darkSq
        new Color(0x60, 0xA5, 0xFA, 0xC0),// selSq
        new Color(0x4A, 0xDE, 0x80, 0xB0),// hlDest
        new Color(0xFC, 0xD3, 0x4D, 0x90),// lastMv
        new Color(0xF1, 0xF5, 0xF9),
        new Color(0x1E, 0x29, 0x3B),
        new Color(0x0B, 0x12, 0x1F),
        new Color(0xCB, 0xD5, 0xE1),
        new Color(0x0F, 0x17, 0x24),
        new Color(0xCB, 0xD5, 0xE1)
    );

    /** Accessibility-oriented: maximum contrast. White pieces get a dark
     *  outline so they're visible on light squares; black pieces get a light
     *  outline so they're visible on dark squares. Highlights stay bright. */
    public static final Theme HIGH_CONTRAST = new Theme(
        "High Contrast",
        new Color(0x00, 0x00, 0x00),
        new Color(0xFF, 0xFF, 0xFF),      // lightSq: pure white
        new Color(0x40, 0x40, 0x40),      // darkSq:  dark gray
        new Color(0x00, 0x80, 0xFF, 0xE0),// selSq
        new Color(0x00, 0xFF, 0x00, 0xC0),// hlDest
        new Color(0xFF, 0xFF, 0x00, 0xC0),// lastMv
        new Color(0xFF, 0xFF, 0xFF),      // whitePc
        new Color(0x00, 0x00, 0x00),      // blackPc
        new Color(0x00, 0x00, 0x00),      // whiteEdge: black outline on white pieces
        new Color(0xFF, 0xFF, 0xFF),      // blackEdge: white outline on black pieces
        new Color(0xFF, 0xFF, 0xFF),      // label
        new Color(0x00, 0x00, 0x00),      // outputBg
        new Color(0xFF, 0xFF, 0xFF)       // outputFg
    );

    /** Warm, low-glare; easy on the eyes for long sessions. */
    public static final Theme SEPIA = new Theme(
        "Sepia",
        new Color(0x4A, 0x37, 0x28),      // panelBg: warm dark brown
        new Color(0xF1, 0xE0, 0xC0),      // lightSq: cream
        new Color(0x8C, 0x68, 0x4B),      // darkSq:  caramel
        new Color(0xD9, 0x9F, 0x4A, 0xC0),// selSq:   amber
        new Color(0xA1, 0xC9, 0x65, 0xB0),// hlDest:  olive
        new Color(0xE5, 0xC4, 0x4C, 0x90),// lastMv:  mustard
        new Color(0xFB, 0xF1, 0xDC),      // whitePc: pale cream
        new Color(0x4A, 0x32, 0x1F),      // blackPc: deep brown
        new Color(0x2C, 0x1A, 0x10),
        new Color(0xE8, 0xD4, 0xB0),
        new Color(0x2C, 0x20, 0x18),
        new Color(0xE8, 0xD4, 0xB0)
    );

    /** All four presets in a fixed display order. */
    public static final Theme[] ALL = { CLASSIC, SLATE, HIGH_CONTRAST, SEPIA };
}
