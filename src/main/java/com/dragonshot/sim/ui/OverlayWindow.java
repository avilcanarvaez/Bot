package com.dragonshot.sim.ui;

import com.dragonshot.sim.capture.Rect;
import com.dragonshot.sim.capture.WindowTracker;
import com.dragonshot.sim.geometry.SceneRect;
import com.dragonshot.sim.geometry.Vec2;
import com.dragonshot.sim.state.SessionState;
import com.dragonshot.sim.vision.AngleResult;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Overlay transparente y siempre-encima sobre el area cliente del juego.
 *
 * La ventana se excluye de las capturas con SetWindowDisplayAffinity, de modo
 * que Robot no captura nuestras propias lineas. En modo marcado se desactiva
 * el paso de clicks para poder colocar origen/destino/ancla.
 */
public final class OverlayWindow extends JFrame {

    public static final String TITLE = "DragonShot Overlay";

    public enum InteractionMode {
        NONE, ORIGIN, TARGET, ANCHOR, IMPACT
    }

    public interface Listener {
        void onScenePoint(InteractionMode mode, Vec2 scenePoint);

        void onAnchorSelected(Rect localRect);
    }

    private final SessionState state;
    private final WindowTracker windowTracker;
    private final Supplier<AngleResult> latestAngle;
    private final java.util.function.Consumer<String> logger;
    private final OverlayPanel panel;
    private final Timer repaintTimer;

    private Listener listener;
    private InteractionMode mode = InteractionMode.NONE;
    private Point dragStart;
    private Point dragCurrent;
    private long lastVersion = -1;
    private AngleResult lastAngle;
    private boolean excludedFromCapture;

    public OverlayWindow(SessionState state, WindowTracker windowTracker,
            Supplier<AngleResult> latestAngle, java.util.function.Consumer<String> logger) {
        super();
        setUndecorated(true);
        setType(Window.Type.UTILITY);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        this.state = state;
        this.windowTracker = windowTracker;
        this.latestAngle = latestAngle;
        this.logger = logger == null ? text -> { } : logger;

        setTitle(TITLE);
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setBackground(new Color(0, 0, 0, 0));
        panel = new OverlayPanel();
        panel.setOpaque(false);
        setContentPane(panel);

        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (mode == InteractionMode.ANCHOR) {
                    dragStart = e.getPoint();
                    dragCurrent = e.getPoint();
                    panel.repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (mode == InteractionMode.ANCHOR && dragStart != null) {
                    dragCurrent = e.getPoint();
                    Rect rect = rectangleFromDrag();
                    dragStart = null;
                    dragCurrent = null;
                    if (rect.width() >= 12 && rect.height() >= 12 && listener != null) {
                        listener.onAnchorSelected(rect);
                    }
                    endInteraction();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (mode == InteractionMode.ORIGIN
                        || mode == InteractionMode.TARGET
                        || mode == InteractionMode.IMPACT) {
                    Vec2 scene = screenToScene(e.getPoint());
                    InteractionMode current = mode;
                    endInteraction();
                    if (listener != null) {
                        listener.onScenePoint(current, scene);
                    }
                }
            }
        });
        panel.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (mode != InteractionMode.NONE) {
                    dragCurrent = e.getPoint();
                    panel.repaint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (mode == InteractionMode.ANCHOR) {
                    dragCurrent = e.getPoint();
                    panel.repaint();
                }
            }
        });

        repaintTimer = new Timer(33, e -> {
            if (!isVisible()) {
                return;
            }
            SessionState.Snapshot snapshot = state.snapshot();
            AngleResult angle = latestAngle.get();
            boolean angleChanged = angle != lastAngle;
            if (snapshot.version() != lastVersion || angleChanged
                    || mode != InteractionMode.NONE || dragStart != null) {
                lastVersion = snapshot.version();
                lastAngle = angle;
                panel.repaint();
            }
        });
        repaintTimer.start();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void showOverlay(Rect bounds) {
        updateBounds(bounds);
        setVisible(true);
        toFront();
        excludedFromCapture = windowTracker.excludeFromCapture(TITLE);
        if (!excludedFromCapture) {
            logger.accept("Aviso: el overlay no se pudo excluir de la captura. "
                    + "Mantén el panel de control fuera del area del juego.");
        }
        if (!windowTracker.setClickThrough(TITLE, true)) {
            // Si el peer aun no esta listo, reintenta solo si el usuario no
            // ha entrado en modo marcado mientras tanto.
            EventQueue.invokeLater(() -> {
                if (mode == InteractionMode.NONE) {
                    windowTracker.setClickThrough(TITLE, true);
                }
            });
        }
    }

    public void hideOverlay() {
        endInteraction();
        setVisible(false);
    }

    public boolean isOverlayVisible() {
        return isVisible();
    }

    public boolean isExcludedFromCapture() {
        return excludedFromCapture;
    }

    public void updateBounds(Rect bounds) {
        if (bounds == null || !bounds.isValid()) {
            return;
        }
        if (getX() == bounds.x() && getY() == bounds.y()
                && getWidth() == bounds.width() && getHeight() == bounds.height()) {
            return;
        }
        setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    public void beginInteraction(InteractionMode newMode) {
        if (newMode == InteractionMode.NONE) {
            endInteraction();
            return;
        }
        mode = newMode;
        dragStart = null;
        dragCurrent = null;
        if (!windowTracker.setClickThrough(TITLE, false)) {
            EventQueue.invokeLater(() -> {
                if (mode != InteractionMode.NONE) {
                    windowTracker.setClickThrough(TITLE, false);
                }
            });
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        toFront();
        panel.repaint();
    }

    public void endInteraction() {
        mode = InteractionMode.NONE;
        dragStart = null;
        dragCurrent = null;
        setCursor(Cursor.getDefaultCursor());
        windowTracker.setClickThrough(TITLE, true);
        panel.repaint();
    }

    public InteractionMode interactionMode() {
        return mode;
    }

    private Rect rectangleFromDrag() {
        if (dragStart == null || dragCurrent == null) {
            return new Rect(0, 0, 0, 0);
        }
        int x0 = Math.min(dragStart.x, dragCurrent.x);
        int y0 = Math.min(dragStart.y, dragCurrent.y);
        int x1 = Math.max(dragStart.x, dragCurrent.x);
        int y1 = Math.max(dragStart.y, dragCurrent.y);
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    private Vec2 screenToScene(Point point) {
        double w = Math.max(1, panel.getWidth());
        double h = Math.max(1, panel.getHeight());
        return new Vec2(point.x / w, point.y / h);
    }

    private final class OverlayPanel extends JPanel {

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                SessionState.Snapshot snapshot = state.snapshot();
                drawStatus(g, snapshot);
                drawAnchor(g, snapshot);
                drawTrajectory(g, snapshot);
                drawDetectedAngle(g, snapshot);
                drawPoints(g, snapshot);
                drawInteraction(g);
            } finally {
                g.dispose();
            }
        }

        private void drawStatus(Graphics2D g, SessionState.Snapshot s) {
            int x = 12;
            int y = 12;
            int w = Math.min(680, Math.max(460, getWidth() - 24));
            int h = 76;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.82f));
            g.setColor(new Color(8, 12, 20));
            g.fill(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(new Color(255, 190, 40));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            g.drawString("DragonShot Simulator", x + 14, y + 22);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(230, 235, 245));
            String capture = s.captureRunning()
                    ? "Captura " + s.fps() + " fps"
                    : "Captura detenida";
            g.drawString(capture + "  |  AIRE = 0  |  " + s.status(), x + 14, y + 42);
            g.setColor(s.syncValid() ? new Color(90, 230, 140) : new Color(255, 150, 90));
            String sync = s.syncValid()
                    ? String.format("Sync OK (ancla %.0f%%)", s.anchorScore() * 100)
                    : "Sync: " + s.syncReason();
            g.drawString(sync, x + 14, y + 60);
            g.setColor(s.angleValid() ? new Color(255, 220, 80) : new Color(255, 120, 120));
            String angle = s.angleValid()
                    ? String.format("Angulo %.1f deg (conf %.0f%%)",
                            s.gameAngleDeg(), s.angleConfidence() * 100)
                    : "Angulo no disponible";
            g.drawString(angle, x + 360, y + 60);
        }

        private void drawAnchor(Graphics2D g, SessionState.Snapshot s) {
            SceneRect anchor = s.anchorScene();
            if (anchor == null) {
                return;
            }
            int x = (int) Math.round(anchor.x() * getWidth());
            int y = (int) Math.round(anchor.y() * getHeight());
            int w = Math.max(6, (int) Math.round(anchor.width() * getWidth()));
            int h = Math.max(6, (int) Math.round(anchor.height() * getHeight()));
            g.setColor(new Color(0, 220, 255, 220));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{6f, 4f}, 0f));
            g.drawRect(x, y, w, h);
            drawLabel(g, "ANCLA", x, Math.max(14, y - 6), new Color(0, 220, 255));
        }

        private void drawTrajectory(Graphics2D g, SessionState.Snapshot s) {
            List<Vec2> points = s.trajectoryScene();
            if (points.isEmpty()) {
                return;
            }
            Path2D path = new Path2D.Double();
            boolean first = true;
            for (Vec2 point : points) {
                double px = point.x() * getWidth();
                double py = point.y() * getHeight();
                if (first) {
                    path.moveTo(px, py);
                    first = false;
                } else {
                    path.lineTo(px, py);
                }
            }
            if (s.trajectoryStale()) {
                g.setColor(new Color(190, 195, 205, 210));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1f, new float[]{9f, 7f}, 0f));
            } else {
                g.setColor(new Color(255, 208, 35, 245));
                g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
            }
            g.draw(path);

            int step = Math.max(1, points.size() / 12);
            g.setColor(s.trajectoryStale()
                    ? new Color(220, 220, 230, 160)
                    : new Color(255, 245, 160, 220));
            for (int i = 0; i < points.size(); i += step) {
                Vec2 point = points.get(i);
                int px = (int) Math.round(point.x() * getWidth());
                int py = (int) Math.round(point.y() * getHeight());
                g.fillOval(px - 2, py - 2, 4, 4);
            }
            Vec2 last = points.get(points.size() - 1);
            int lx = (int) Math.round(last.x() * getWidth());
            int ly = (int) Math.round(last.y() * getHeight());
            g.setColor(new Color(255, 120, 60, 230));
            g.fillOval(lx - 6, ly - 6, 12, 12);
            drawLabel(g, s.trajectoryStale() ? "TRAYECTORIA DESACTUALIZADA" : "TRAYECTORIA",
                    lx + 10, ly - 8, s.trajectoryStale()
                            ? new Color(220, 220, 230) : new Color(255, 220, 90));
        }

        private void drawDetectedAngle(Graphics2D g, SessionState.Snapshot s) {
            AngleResult result = latestAngle.get();
            Vec2 origin = s.originScene();
            if (origin == null) {
                return;
            }
            int ox = (int) Math.round(origin.x() * getWidth());
            int oy = (int) Math.round(origin.y() * getHeight());
            if (result != null && result.valid()) {
                double rad = Math.toRadians(result.screenAngleDeg());
                int length = 90;
                int tx = (int) Math.round(ox + Math.cos(rad) * length);
                int ty = (int) Math.round(oy - Math.sin(rad) * length);
                g.setColor(new Color(255, 80, 220, 235));
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(ox, oy, tx, ty);
                g.fillOval(tx - 4, ty - 4, 8, 8);
                drawLabel(g, String.format("%.1f deg", result.screenAngleDeg()),
                        tx + 8, ty - 8, new Color(255, 120, 230));
            } else {
                drawLabel(g, "angulo sin confianza", ox + 12, oy - 28,
                        new Color(255, 150, 100));
            }
        }

        private void drawPoints(Graphics2D g, SessionState.Snapshot s) {
            if (s.originScene() != null) {
                drawCrosshair(g, s.originScene(), new Color(80, 240, 140), "ORIGEN");
            }
            if (s.targetScene() != null) {
                drawCrosshair(g, s.targetScene(), new Color(255, 95, 95), "DESTINO");
            }
        }

        private void drawCrosshair(Graphics2D g, Vec2 scene, Color color, String label) {
            int x = (int) Math.round(scene.x() * getWidth());
            int y = (int) Math.round(scene.y() * getHeight());
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(x - 9, y - 9, 18, 18);
            g.drawLine(x - 15, y, x - 4, y);
            g.drawLine(x + 4, y, x + 15, y);
            g.drawLine(x, y - 15, x, y - 4);
            g.drawLine(x, y + 4, x, y + 15);
            drawLabel(g, label, x + 14, y + 6, color);
        }

        private void drawInteraction(Graphics2D g) {
            if (mode == InteractionMode.NONE) {
                return;
            }
            String instruction = switch (mode) {
                case ORIGIN -> "Haz clic sobre el eje de giro del canon (ORIGEN)";
                case TARGET -> "Haz clic sobre el DESTINO";
                case ANCHOR -> "Arrastra un rectangulo sobre un elemento FIJO del escenario (no HUD)";
                case IMPACT -> "Haz clic sobre el punto de IMPACTO del disparo real";
                default -> "";
            };
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(instruction) + 30;
            int x = Math.max(10, (getWidth() - w) / 2);
            int y = Math.max(100, getHeight() / 2 - 120);
            g.setColor(new Color(20, 25, 40, 220));
            g.fill(new RoundRectangle2D.Double(x, y, w, 32, 12, 12));
            g.setColor(new Color(255, 220, 100));
            g.drawString(instruction, x + 15, y + 21);

            if (mode == InteractionMode.ANCHOR && dragStart != null && dragCurrent != null) {
                int x0 = Math.min(dragStart.x, dragCurrent.x);
                int y0 = Math.min(dragStart.y, dragCurrent.y);
                int w0 = Math.abs(dragCurrent.x - dragStart.x);
                int h0 = Math.abs(dragCurrent.y - dragStart.y);
                g.setColor(new Color(0, 230, 255, 235));
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1f, new float[]{6f, 4f}, 0f));
                g.drawRect(x0, y0, w0, h0);
            } else if (dragCurrent != null) {
                g.setColor(new Color(255, 255, 255, 180));
                g.drawLine(dragCurrent.x - 10, dragCurrent.y, dragCurrent.x + 10, dragCurrent.y);
                g.drawLine(dragCurrent.x, dragCurrent.y - 10, dragCurrent.x, dragCurrent.y + 10);
            }
        }

        private void drawLabel(Graphics2D g, String text, int x, int y, Color color) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            FontMetrics fm = g.getFontMetrics();
            int width = fm.stringWidth(text) + 10;
            int height = fm.getHeight() + 2;
            int drawX = Math.max(2, Math.min(getWidth() - width - 2, x));
            int drawY = Math.max(2, Math.min(getHeight() - height - 2, y - height + 4));
            g.setColor(new Color(10, 14, 22, 190));
            g.fill(new RoundRectangle2D.Double(drawX, drawY, width, height, 7, 7));
            g.setColor(color);
            g.drawString(text, drawX + 5, drawY + fm.getAscent() + 1);
        }
    }
}
