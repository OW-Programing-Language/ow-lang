package dev.oreo;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A native desktop renderer for the Ow UI widget tree. Deliberately flat and
 * modern — custom-painted rounded buttons, antialiased discs, a dark rounded
 * "card", and system fonts — so it does not look like default Swing. It renders
 * exactly the same widget tree as {@link UiRuntime} (the web backend), so a
 * single Ow program can run in either.
 */
public class SwingUiRuntime implements UiHost {

    // palette (shared with the web theme)
    private static final Color BG      = new Color(0x0f1117);
    private static final Color PANEL   = new Color(0x171a23);
    private static final Color PANEL2  = new Color(0x1f2430);
    private static final Color TEXT    = new Color(0xe7e9ee);
    private static final Color MUTED   = new Color(0x9aa3b2);
    private static final Color ACCENT  = new Color(0x6366f1);
    private static final Color ACCENT2 = new Color(0x4f46e5);
    private static final Color RED     = new Color(0xef4444);
    private static final Color REDHI   = new Color(0xff7a7a);
    private static final Color YELLOW  = new Color(0xf6c343);
    private static final Color YELLOWHI= new Color(0xffe08a);
    private static final Color EMPTY   = new Color(0x0b0e14);

    private final Interpreter interp;
    private final PrintStream out;

    private String renderFn;
    private JFrame frame;
    private JPanel content;
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    public SwingUiRuntime(Interpreter interp, PrintStream out) {
        this.interp = interp;
        this.out = out;
    }

    @Override
    public void run(String renderFn, String title) {
        this.renderFn = renderFn;
        try {
            SwingUtilities.invokeAndWait(() -> buildFrame(title));
        } catch (Exception e) {
            throw new RuntimeException("Could not open Ow UI window: " + e.getMessage(), e);
        }
        rerender();
        out.println("Ow native UI window opened: " + title);
        try {
            stopLatch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void requestStop() { shutdown(); }

    @Override
    public void shutdown() {
        stopLatch.countDown();
        if (frame != null) {
            SwingUtilities.invokeLater(() -> {
                frame.setVisible(false);
                frame.dispose();
            });
        }
    }

    private void buildFrame(String title) {
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { stopLatch.countDown(); }
        });

        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG);

        Card card = new Card();
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));

        content = new JPanel();
        content.setOpaque(false);
        card.add(content, BorderLayout.CENTER);

        root.add(card);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(420, 360));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void rerender() {
        Runnable task = () -> {
            Object tree = interp.callFunction(renderFn, java.util.Collections.emptyList());
            content.removeAll();
            content.setLayout(new BorderLayout());
            content.add(build(tree), BorderLayout.CENTER);
            content.revalidate();
            content.repaint();
            if (frame != null) frame.pack();
        };
        if (SwingUtilities.isEventDispatchThread()) task.run();
        else SwingUtilities.invokeLater(task);
    }

    // ---- tree → Swing components ----

    @SuppressWarnings("unchecked")
    private JComponent build(Object node) {
        if (!(node instanceof Map)) {
            JLabel l = new JLabel(interp.formatValue(node));
            l.setForeground(TEXT);
            return l;
        }
        Map<String, Object> m = (Map<String, Object>) node;
        String type = String.valueOf(m.get("type"));
        switch (type) {
            case "heading": {
                JLabel l = new JLabel(str(m.get("text")));
                l.setFont(font(Font.BOLD, 26));
                l.setForeground(ACCENT.brighter());
                l.setAlignmentX(Component.CENTER_ALIGNMENT);
                return l;
            }
            case "text": {
                JLabel l = new JLabel(str(m.get("text")));
                l.setFont(font(Font.PLAIN, 15));
                l.setForeground(MUTED);
                l.setAlignmentX(Component.CENTER_ALIGNMENT);
                return l;
            }
            case "button":
                return new RoundButton(m);
            case "disc":
                return new Disc(str(m.get("color")));
            case "row":
                return container(m, true);
            case "col":
                return container(m, false);
            case "grid":
                return grid(m);
            default: {
                JLabel l = new JLabel(type);
                l.setForeground(TEXT);
                return l;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> children(Map<String, Object> m) {
        Object c = m.get("children");
        return (c instanceof List) ? (List<Object>) c : java.util.Collections.emptyList();
    }

    private JComponent container(Map<String, Object> m, boolean horizontal) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, horizontal ? BoxLayout.X_AXIS : BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.CENTER_ALIGNMENT);
        List<Object> kids = children(m);
        for (int i = 0; i < kids.size(); i++) {
            if (i > 0) p.add(horizontal ? Box.createHorizontalStrut(12) : Box.createVerticalStrut(14));
            JComponent c = build(kids.get(i));
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
            c.setAlignmentY(Component.CENTER_ALIGNMENT);
            p.add(c);
        }
        return p;
    }

    private JComponent grid(Map<String, Object> m) {
        int cols = (m.get("cols") instanceof Integer) ? (Integer) m.get("cols") : 1;
        RoundPanel p = new RoundPanel(PANEL2, 16);
        p.setLayout(new GridLayout(0, Math.max(1, cols), 10, 10));
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        p.setAlignmentX(Component.CENTER_ALIGNMENT);
        for (Object kid : children(m)) p.add(build(kid));
        return p;
    }

    private void fire(Map<String, Object> m) {
        String handler = str(m.get("onClick"));
        boolean hasArg = m.containsKey("arg");
        try {
            interp.callFunction(handler,
                    hasArg ? java.util.Collections.singletonList(m.get("arg"))
                           : java.util.Collections.emptyList());
        } catch (RuntimeException err) {
            out.println("Ow UI handler error: " + err.getMessage());
        }
        rerender();
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static Font font(int style, int size) {
        return new Font("Segoe UI", style, size);
    }

    // ---- custom-painted widgets ----

    /** Rounded dark panel used as the window "card". */
    private static class Card extends JPanel {
        Card() { setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(PANEL);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 28, 28));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class RoundPanel extends JPanel {
        private final Color fill;
        private final int radius;
        RoundPanel(Color fill, int radius) { this.fill = fill; this.radius = radius; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), radius, radius));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private class RoundButton extends JButton {
        RoundButton(Map<String, Object> node) {
            super(str(node.get("label")));
            setFont(font(Font.BOLD, 15));
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(11, 20, 11, 20));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(e -> fire(node));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color top = ACCENT, bot = ACCENT2;
            if (getModel().isPressed()) { top = ACCENT2; bot = ACCENT2.darker(); }
            else if (getModel().isRollover()) { top = ACCENT.brighter(); bot = ACCENT; }
            g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bot));
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
            g2.dispose();
            super.paintComponent(g);
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
    }

    private static class Disc extends JComponent {
        private final String color;
        Disc(String color) { this.color = color == null ? "" : color.toLowerCase(); }
        @Override public Dimension getPreferredSize() { return new Dimension(52, 52); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth() - d) / 2, y = (getHeight() - d) / 2;
            Color base, hi;
            switch (color) {
                case "red"    -> { base = RED;    hi = REDHI; }
                case "yellow" -> { base = YELLOW; hi = YELLOWHI; }
                default        -> { base = EMPTY;  hi = EMPTY; }
            }
            if (base == EMPTY) {
                g2.setColor(EMPTY);
                g2.fillOval(x, y, d, d);
            } else {
                g2.setPaint(new RadialGradientPaint(
                        new Point(x + d / 3, y + d / 3), d * 0.7f,
                        new float[]{0f, 1f}, new Color[]{hi, base}));
                g2.fillOval(x, y, d, d);
            }
            g2.dispose();
        }
    }
}
