package com.dragonshot.sim.state;

import com.dragonshot.sim.geometry.SceneRect;
import com.dragonshot.sim.geometry.Vec2;
import java.util.ArrayList;
import java.util.List;

/**
 * Estado compartido entre el hilo de captura/vision y la UI.
 *
 * Todos los metodos publicos son synchronized. Las instantaneas son inmutables,
 * de modo que el overlay y el panel de control nunca leen una mezcla de valores
 * de dos frames distintos.
 */
public final class SessionState {

    public record Snapshot(
            Vec2 originScene,
            Vec2 targetScene,
            SceneRect anchorScene,
            boolean angleValid,
            double screenAngleDeg,
            double gameAngleDeg,
            double angleConfidence,
            double lastValidScreenAngleDeg,
            double lastValidGameAngleDeg,
            String angleReason,
            boolean syncValid,
            int lostFrames,
            double anchorScore,
            String syncReason,
            double power,
            double gravity,
            double powerScale,
            double angleOffsetDeg,
            double trajectoryOffsetDeg,
            boolean clockwiseAngle,
            List<Vec2> trajectoryScene,
            boolean trajectoryStale,
            String trajectoryStatus,
            double suggestedPower,
            int fps,
            boolean captureRunning,
            String status,
            long version) {
    }

    private Vec2 originScene;
    private Vec2 targetScene;
    private SceneRect anchorScene;

    private boolean angleValid;
    private double screenAngleDeg = Double.NaN;
    private double gameAngleDeg = Double.NaN;
    private double angleConfidence;
    private double lastValidScreenAngleDeg = Double.NaN;
    private double lastValidGameAngleDeg = Double.NaN;
    private String angleReason = "Marca el origen sobre el eje del canon";

    private boolean syncValid;
    private int lostFrames;
    private double anchorScore;
    private String syncReason = "Sin ancla configurada";

    private double power = 50;
    private double gravity = 500;
    private double powerScale = 10;
    private double angleOffsetDeg;
    private double trajectoryOffsetDeg;
    private boolean clockwiseAngle;

    private List<Vec2> trajectoryScene = List.of();
    private boolean trajectoryStale;
    private String trajectoryStatus = "Sin trayectoria";
    private double suggestedPower = Double.NaN;

    private int fps;
    private boolean captureRunning;
    private String status = "Listo";
    private long version;

    public synchronized Vec2 originScene() {
        return originScene;
    }

    public synchronized Vec2 targetScene() {
        return targetScene;
    }

    public synchronized SceneRect anchorScene() {
        return anchorScene;
    }

    public synchronized boolean isSyncValid() {
        return syncValid;
    }

    public synchronized int lostFrames() {
        return lostFrames;
    }

    public synchronized boolean isAngleValid() {
        return angleValid;
    }

    public synchronized double screenAngleDeg() {
        return screenAngleDeg;
    }

    public synchronized double power() {
        return power;
    }

    public synchronized void setOrigin(Vec2 scenePoint) {
        if (scenePoint == null || !scenePoint.isFinite()) {
            return;
        }
        if (originScene != null && originScene.distanceTo(scenePoint) < 1e-6) {
            return;
        }
        originScene = scenePoint;
        markTrajectoryStaleInternal();
        version++;
    }

    public synchronized void setTarget(Vec2 scenePoint) {
        if (scenePoint == null || !scenePoint.isFinite()) {
            return;
        }
        if (targetScene != null && targetScene.distanceTo(scenePoint) < 1e-6) {
            return;
        }
        targetScene = scenePoint;
        // La potencia sugerida y la etiqueta de la trayectoria dependen del
        // destino, aunque la polilinea dibujada no cambie.
        markTrajectoryStaleInternal();
        version++;
    }

    public synchronized void setAnchorRect(SceneRect rect) {
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        anchorScene = rect;
        version++;
    }

    public synchronized void clearAnchor() {
        if (anchorScene == null) {
            return;
        }
        anchorScene = null;
        syncValid = false;
        lostFrames = 0;
        anchorScore = 0;
        syncReason = "Sin ancla configurada";
        version++;
    }

    public synchronized void clearPoints() {
        originScene = null;
        targetScene = null;
        anchorScene = null;
        trajectoryScene = List.of();
        trajectoryStale = false;
        trajectoryStatus = "Sin trayectoria";
        suggestedPower = Double.NaN;
        angleValid = false;
        screenAngleDeg = Double.NaN;
        gameAngleDeg = Double.NaN;
        angleConfidence = 0;
        lastValidScreenAngleDeg = Double.NaN;
        lastValidGameAngleDeg = Double.NaN;
        angleReason = "Marca el origen sobre el eje del canon";
        syncValid = false;
        lostFrames = 0;
        anchorScore = 0;
        syncReason = "Sin ancla configurada";
        version++;
    }

    public synchronized void setAngle(double screenAngle, double gameAngle, double confidence) {
        if (!Double.isFinite(screenAngle) || !Double.isFinite(gameAngle)) {
            return;
        }
        double normalizedScreen = normalize360(screenAngle);
        double normalizedGame = normalize360(gameAngle);
        double previousScreen = screenAngleDeg;
        boolean wasValid = angleValid;
        boolean angleChanged = !wasValid || angleValid
                && circularDistance(normalizedScreen, screenAngleDeg) >= 0.05;
        boolean confidenceChanged = Math.abs(confidence - angleConfidence) >= 0.02;
        if (!angleChanged && !confidenceChanged) {
            lastValidScreenAngleDeg = normalizedScreen;
            lastValidGameAngleDeg = normalizedGame;
            return;
        }
        screenAngleDeg = normalizedScreen;
        gameAngleDeg = normalizedGame;
        angleConfidence = Math.max(0, Math.min(1, confidence));
        lastValidScreenAngleDeg = normalizedScreen;
        lastValidGameAngleDeg = normalizedGame;
        angleValid = true;
        angleReason = "";
        if (!wasValid || Double.isFinite(previousScreen)
                && circularDistance(normalizedScreen, previousScreen) >= 0.5) {
            markTrajectoryStaleInternal();
        }
        version++;
    }

    public synchronized void markAngleUnavailable(String reason) {
        String newReason = reason == null || reason.isBlank()
                ? "Angulo no disponible" : reason;
        boolean changed = angleValid || !newReason.equals(angleReason);
        angleValid = false;
        screenAngleDeg = Double.NaN;
        gameAngleDeg = Double.NaN;
        angleConfidence = 0;
        angleReason = newReason;
        if (changed) {
            markTrajectoryStaleInternal();
            version++;
        }
    }

    public synchronized void updateAngleMapping(double offsetDeg, boolean clockwise) {
        this.angleOffsetDeg = offsetDeg;
        this.clockwiseAngle = clockwise;
        if (angleValid && Double.isFinite(screenAngleDeg)) {
            gameAngleDeg = normalize360(clockwise
                    ? offsetDeg - screenAngleDeg
                    : screenAngleDeg + offsetDeg);
            lastValidGameAngleDeg = gameAngleDeg;
        }
        version++;
    }

    /**
     * Aplica un desplazamiento de camara y, opcionalmente, un cambio de zoom.
     * Los puntos, el ancla y la trayectoria se transforman alrededor del centro
     * del ancla, por lo que no se acumula error relativo.
     */
    public synchronized void applyAnchorUpdate(Vec2 deltaScene, double scaleRatio,
            Vec2 oldAnchorCenterScene) {
        if (deltaScene == null || oldAnchorCenterScene == null) {
            return;
        }
        double scale = Double.isFinite(scaleRatio) && scaleRatio > 0 ? scaleRatio : 1.0;
        boolean negligible = deltaScene.length() < 1e-7 && Math.abs(scale - 1.0) < 1e-4;
        if (negligible) {
            return;
        }
        originScene = transform(originScene, oldAnchorCenterScene, scale, deltaScene);
        targetScene = transform(targetScene, oldAnchorCenterScene, scale, deltaScene);
        if (anchorScene != null) {
            anchorScene = anchorScene
                    .scaleAround(oldAnchorCenterScene.x(), oldAnchorCenterScene.y(), scale)
                    .shift(deltaScene.x(), deltaScene.y());
        }
        if (!trajectoryScene.isEmpty()) {
            List<Vec2> moved = new ArrayList<>(trajectoryScene.size());
            for (Vec2 point : trajectoryScene) {
                moved.add(transform(point, oldAnchorCenterScene, scale, deltaScene));
            }
            trajectoryScene = List.copyOf(moved);
        }
        version++;
    }

    private static Vec2 transform(Vec2 point, Vec2 center, double scale, Vec2 delta) {
        if (point == null) {
            return null;
        }
        return new Vec2(
                center.x() + (point.x() - center.x()) * scale + delta.x(),
                center.y() + (point.y() - center.y()) * scale + delta.y());
    }

    public synchronized void setSync(boolean valid, int lost, double score, String reason) {
        String newReason = reason == null ? "" : reason;
        boolean changed = syncValid != valid
                || lostFrames != lost
                || Math.abs(anchorScore - score) >= 0.02
                || !syncReason.equals(newReason);
        if (!changed) {
            return;
        }
        syncValid = valid;
        lostFrames = Math.max(0, lost);
        anchorScore = Math.max(0, Math.min(1, score));
        syncReason = newReason;
        version++;
    }

    public synchronized void setTrajectory(List<Vec2> scenePoints, double suggested,
            String statusText) {
        trajectoryScene = scenePoints == null ? List.of() : List.copyOf(scenePoints);
        trajectoryStale = false;
        trajectoryStatus = statusText == null ? "" : statusText;
        suggestedPower = suggested;
        version++;
    }

    public synchronized void clearTrajectory() {
        if (trajectoryScene.isEmpty() && !trajectoryStale) {
            return;
        }
        trajectoryScene = List.of();
        trajectoryStale = false;
        trajectoryStatus = "Sin trayectoria";
        suggestedPower = Double.NaN;
        version++;
    }

    public synchronized void markTrajectoryStale() {
        markTrajectoryStaleInternal();
        version++;
    }

    private void markTrajectoryStaleInternal() {
        if (!trajectoryScene.isEmpty() && !trajectoryStale) {
            trajectoryStale = true;
            trajectoryStatus = "Trayectoria desactualizada: pulsa SIMULAR";
        }
    }

    public synchronized void setPower(double power) {
        double clamped = Math.max(0, Math.min(100, power));
        if (Math.abs(this.power - clamped) < 1e-6) {
            return;
        }
        this.power = clamped;
        markTrajectoryStaleInternal();
        version++;
    }

    public synchronized void setGravity(double gravity) {
        if (!Double.isFinite(gravity) || gravity <= 0
                || Math.abs(this.gravity - gravity) < 1e-6) {
            return;
        }
        this.gravity = gravity;
        markTrajectoryStaleInternal();
        version++;
    }

    public synchronized void setPowerScale(double powerScale) {
        if (!Double.isFinite(powerScale) || powerScale <= 0
                || Math.abs(this.powerScale - powerScale) < 1e-6) {
            return;
        }
        this.powerScale = powerScale;
        markTrajectoryStaleInternal();
        version++;
    }

    public synchronized void setTrajectoryOffsetDeg(double offset) {
        if (!Double.isFinite(offset) || Math.abs(trajectoryOffsetDeg - offset) < 1e-6) {
            return;
        }
        trajectoryOffsetDeg = offset;
        markTrajectoryStaleInternal();
        version++;
    }

    public synchronized void setSuggestedPower(double suggested) {
        if (Double.isNaN(suggested) && Double.isNaN(suggestedPower)) {
            return;
        }
        if (Math.abs(suggested - suggestedPower) < 0.05) {
            return;
        }
        suggestedPower = suggested;
        version++;
    }

    public synchronized void setCaptureStatus(boolean running, int fps, String statusText) {
        String newStatus = statusText == null ? "" : statusText;
        if (captureRunning == running && this.fps == fps && status.equals(newStatus)) {
            return;
        }
        captureRunning = running;
        this.fps = Math.max(0, fps);
        status = newStatus;
        version++;
    }

    public synchronized void setStatus(String statusText) {
        String newStatus = statusText == null ? "" : statusText;
        if (status.equals(newStatus)) {
            return;
        }
        status = newStatus;
        version++;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                originScene,
                targetScene,
                anchorScene,
                angleValid,
                screenAngleDeg,
                gameAngleDeg,
                angleConfidence,
                lastValidScreenAngleDeg,
                lastValidGameAngleDeg,
                angleReason,
                syncValid,
                lostFrames,
                anchorScore,
                syncReason,
                power,
                gravity,
                powerScale,
                angleOffsetDeg,
                trajectoryOffsetDeg,
                clockwiseAngle,
                trajectoryScene,
                trajectoryStale,
                trajectoryStatus,
                suggestedPower,
                fps,
                captureRunning,
                status,
                version);
    }

    public static double normalize360(double angle) {
        double value = angle % 360.0;
        if (value < 0) {
            value += 360.0;
        }
        return value;
    }

    private static double circularDistance(double a, double b) {
        double delta = Math.abs(a - b) % 360.0;
        return delta > 180.0 ? 360.0 - delta : delta;
    }
}
