package com.dragonshot.sim.vision;

import com.dragonshot.sim.capture.Frame;

/**
 * Sigue un parche del escenario (ancla) a traves de fotogramas consecutivos.
 *
 * El ancla debe ser un elemento DEL MUNDO (suelo, decorado fijo), no un HUD:
 * precisamente lo que se quiere medir es el desplazamiento de la camara.
 */
public final class AnchorTracker {

    public record Update(boolean found, double dx, double dy, double scaleRatio,
            double newX, double newY, double score, int lostFrames, String reason) {
        public static Update notFound(int lostFrames, String reason) {
            return new Update(false, 0, 0, 1, 0, 0, 0, lostFrames, reason);
        }
    }

    private static final double[] SCALES = {1.0, 0.96, 1.04, 0.92, 1.08, 0.88, 1.12};
    private static final double MIN_SCORE = 0.55;
    private static final double MAX_JUMP_PX = 180.0;

    private Frame template;
    private double lastX;
    private double lastY;
    private double lastScale = 1.0;
    private int lostFrames;
    private final int searchRadiusPx;

    public AnchorTracker(int searchRadiusPx) {
        this.searchRadiusPx = Math.max(24, searchRadiusPx);
    }

    public synchronized boolean hasTemplate() {
        return template != null;
    }

    public synchronized Frame template() {
        return template;
    }

    public synchronized double lastX() {
        return lastX;
    }

    public synchronized double lastY() {
        return lastY;
    }

    public synchronized double lastScale() {
        return lastScale;
    }

    public synchronized int lostFrames() {
        return lostFrames;
    }

    public synchronized void setTemplate(Frame template, double x, double y) {
        this.template = template;
        this.lastX = x;
        this.lastY = y;
        this.lastScale = 1.0;
        this.lostFrames = 0;
    }

    public synchronized void clear() {
        template = null;
        lostFrames = 0;
    }

    /** Reajusta la posicion esperada sin alterar el template (tras un resize). */
    public synchronized void resetExpectedPosition(double x, double y) {
        this.lastX = x;
        this.lastY = y;
        this.lastScale = 1.0;
        this.lostFrames = 0;
    }

    public synchronized Update update(Frame frame) {
        if (template == null) {
            lostFrames++;
            return Update.notFound(lostFrames, "Sin ancla configurada");
        }
        if (frame == null) {
            return Update.notFound(lostFrames + 1, "Frame nulo");
        }
        int tw = template.width();
        int th = template.height();
        int sx = (int) Math.round(lastX - searchRadiusPx);
        int sy = (int) Math.round(lastY - searchRadiusPx);
        int sw = searchRadiusPx * 2 + tw;
        int sh = searchRadiusPx * 2 + th;

        TemplateMatcher.MatchResult match = TemplateMatcher.match(
                frame, template, sx, sy, sw, sh, SCALES, MIN_SCORE);
        if (!match.found()) {
            lostFrames++;
            return Update.notFound(lostFrames, "Ancla no localizada (score bajo)");
        }

        double dx = match.x() - lastX;
        double dy = match.y() - lastY;
        if (Math.abs(dx) > MAX_JUMP_PX || Math.abs(dy) > MAX_JUMP_PX) {
            lostFrames++;
            return Update.notFound(lostFrames, "Salto de ancla demasiado grande");
        }

        double ratio = match.scale() / lastScale;
        if (ratio < 0.8 || ratio > 1.25) {
            // Un salto de escala grande es mas probablemente un falso positivo
            // que un zoom real. No se aplica a ciegas: se declara perdido.
            lostFrames++;
            return Update.notFound(lostFrames, "Cambio de escala fuera de rango");
        }
        lastX = match.x();
        lastY = match.y();
        lastScale = match.scale();
        lostFrames = 0;
        return new Update(true, dx, dy, ratio, lastX, lastY, match.score(), 0, "");
    }
}
