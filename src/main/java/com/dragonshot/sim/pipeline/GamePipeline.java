package com.dragonshot.sim.pipeline;

import com.dragonshot.sim.capture.CaptureEvent;
import com.dragonshot.sim.capture.Frame;
import com.dragonshot.sim.capture.Rect;
import com.dragonshot.sim.config.AppConfig;
import com.dragonshot.sim.geometry.CoordTransform;
import com.dragonshot.sim.geometry.SceneRect;
import com.dragonshot.sim.geometry.Vec2;
import com.dragonshot.sim.physics.Ballistics;
import com.dragonshot.sim.physics.Trajectory;
import com.dragonshot.sim.state.SessionState;
import com.dragonshot.sim.vision.AnchorTracker;
import com.dragonshot.sim.vision.AngleDetector;
import com.dragonshot.sim.vision.AngleResult;
import com.dragonshot.sim.vision.AngleTracker;
import java.util.ArrayList;
import java.util.List;

/**
 * Orquesta el flujo por fotograma:
 *
 *   captura -> seguimiento del ancla (desplazamiento/zoom) -> deteccion del
 *   angulo -> estado compartido
 *
 * y el flujo de simulacion:
 *
 *   estado actual -> balistica AIRE=0 -> trayectoria en coordenadas de escena.
 *
 * Se ejecuta en el hilo de captura. No toca Swing directamente.
 */
public final class GamePipeline {

    private final AppConfig config;
    private final SessionState state;
    private final CoordTransform transform;
    private final AngleTracker angleTracker = new AngleTracker();
    private volatile AnchorTracker anchorTracker;
    private volatile AngleResult latestAngle;
    private volatile int lastClientWidth;
    private volatile int lastClientHeight;
    private volatile boolean skipAnchorThisFrame;
    private int anchorSearchRadius;

    private long frameCount;
    private long lastFpsNanos = System.nanoTime();

    public GamePipeline(AppConfig config, SessionState state, CoordTransform transform) {
        this.config = config;
        this.state = state;
        this.transform = transform;
        this.anchorSearchRadius = config.anchorSearchRadius();
        this.anchorTracker = new AnchorTracker(anchorSearchRadius);
        syncConfigToState();
    }

    public void setAnchorSearchRadius(int radius) {
        int normalized = Math.max(24, Math.min(200, radius));
        if (normalized == anchorSearchRadius) {
            return;
        }
        Frame template = anchorTracker.template();
        double x = anchorTracker.lastX();
        double y = anchorTracker.lastY();
        anchorSearchRadius = normalized;
        anchorTracker = new AnchorTracker(normalized);
        if (template != null) {
            anchorTracker.setTemplate(template, x, y);
        }
    }

    public void syncConfigToState() {
        state.setPower(config.power());
        state.setGravity(config.gravity());
        state.setPowerScale(config.powerScale());
        state.setTrajectoryOffsetDeg(config.trajectoryOffsetDeg());
        state.updateAngleMapping(config.angleOffsetDeg(), config.clockwiseAngle());
    }

    public AngleResult latestAngle() {
        return latestAngle;
    }

    public AnchorTracker anchorTracker() {
        return anchorTracker;
    }

    public void onCapture(CaptureEvent event) {
        if (event == null || event.frame() == null || event.clientRect() == null) {
            return;
        }
        Frame frame = event.frame();
        Rect rect = event.clientRect();
        boolean sizeChanged = rect.width() != lastClientWidth || rect.height() != lastClientHeight;
        lastClientWidth = rect.width();
        lastClientHeight = rect.height();
        transform.setClientRect(rect);

        if (sizeChanged) {
            recaptureAnchorAfterResize(frame);
        }

        updateAnchor(frame);
        updateAngle(frame);
        updateFps();
    }

    private void updateAnchor(Frame frame) {
        SceneRect anchorScene = state.anchorScene();
        if (anchorScene == null) {
            state.setSync(false, 0, 0, "Sin ancla: el desplazamiento del escenario no se mide");
            return;
        }
        if (!anchorTracker.hasTemplate()) {
            // Si habia un ancla configurada pero ya no hay plantilla, el origen
            // puede haber quedado en un frame viejo. Se invalida el angulo.
            state.setSync(false, 2, 0, "Ancla sin plantilla: vuelve a marcarla");
            return;
        }
        if (skipAnchorThisFrame) {
            skipAnchorThisFrame = false;
            return;
        }

        Vec2 oldCenter = new Vec2(anchorScene.centerX(), anchorScene.centerY());
        AnchorTracker.Update update = anchorTracker.update(frame);
        if (update.found()) {
            Vec2 deltaScene = transform.screenDeltaToScene(update.dx(), update.dy());
            state.applyAnchorUpdate(deltaScene, update.scaleRatio(), oldCenter);
            state.setSync(true, 0, update.score(), "");
        } else {
            String reason = update.reason();
            state.setSync(false, update.lostFrames(), 0, reason);
        }
    }

    private void updateAngle(Frame frame) {
        Vec2 originScene = state.originScene();
        if (originScene == null) {
            latestAngle = null;
            angleTracker.reset();
            state.markAngleUnavailable("Marca el origen sobre el eje de giro del canon");
            return;
        }
        if (state.anchorScene() != null && state.lostFrames() >= 2) {
            latestAngle = null;
            angleTracker.reset();
            state.markAngleUnavailable("Sincronizacion perdida: vuelve a marcar el ancla");
            return;
        }
        Vec2 pivotLocal = transform.sceneToLocal(originScene);
        AngleResult result = AngleDetector.detect(frame, pivotLocal,
                config.angleRadiusMin(), config.angleRadiusMax(),
                config.minAngleConfidence());
        latestAngle = result;
        AngleTracker.Update update = angleTracker.accept(result);
        if (update.available() && Double.isFinite(update.angleDeg())) {
            double gameAngle = SessionState.normalize360(config.clockwiseAngle()
                    ? config.angleOffsetDeg() - update.angleDeg()
                    : update.angleDeg() + config.angleOffsetDeg());
            state.setAngle(update.angleDeg(), gameAngle, update.confidence());
        } else {
            state.markAngleUnavailable(result.reason());
        }
    }

    private void updateFps() {
        frameCount++;
        long now = System.nanoTime();
        long elapsed = now - lastFpsNanos;
        if (elapsed >= 1_000_000_000L) {
            int fps = (int) Math.round(frameCount * 1_000_000_000.0 / elapsed);
            state.setCaptureStatus(true, fps, "Capturando");
            frameCount = 0;
            lastFpsNanos = now;
        }
    }

    public void onCaptureError(String message) {
        latestAngle = null;
        angleTracker.reset();
        state.setCaptureStatus(true, 0, message);
        state.setSync(false, 0, 0, "Captura no disponible");
        state.markAngleUnavailable(message);
    }

    /** Recaptura el template del ancla cuando cambia el tamano del area cliente. */
    private void recaptureAnchorAfterResize(Frame frame) {
        SceneRect anchorScene = state.anchorScene();
        if (anchorScene == null) {
            return;
        }
        Rect local = transform.sceneRectToLocal(anchorScene);
        Frame template = cropSafe(frame, local);
        if (template != null) {
            anchorTracker.setTemplate(template, local.x(), local.y());
            state.setSync(true, 0, 1, "Ancla recapturada tras cambio de tamano");
        } else {
            anchorTracker.clear();
            state.setSync(false, 2, 0, "Ancla fuera del area visible tras el resize");
        }
        skipAnchorThisFrame = true;
    }

    /**
     * Registra un ancla a partir del frame actual. localRect debe estar en
     * pixeles del area cliente.
     */
    public boolean setAnchor(Frame frame, Rect localRect) {
        Frame template = cropSafe(frame, localRect);
        if (template == null) {
            return false;
        }
        anchorTracker.setTemplate(template, localRect.x(), localRect.y());
        state.setAnchorRect(transform.localRectToScene(
                localRect.x(), localRect.y(), localRect.width(), localRect.height()));
        state.setSync(true, 0, 1, "Ancla lista");
        return true;
    }

    private static Frame cropSafe(Frame frame, Rect rect) {
        if (frame == null || rect == null || rect.width() < 8 || rect.height() < 8) {
            return null;
        }
        int x = Math.max(0, rect.x());
        int y = Math.max(0, rect.y());
        int w = Math.min(rect.width(), frame.width() - x);
        int h = Math.min(rect.height(), frame.height() - y);
        if (w < 8 || h < 8) {
            return null;
        }
        return frame.crop(x, y, w, h);
    }

    public SimulationResult simulate() throws SimulationException {
        SessionState.Snapshot s = state.snapshot();
        if (s.originScene() == null) {
            throw new SimulationException("Marca el ORIGEN sobre el eje de giro del canon.");
        }
        if (!s.angleValid() || !Double.isFinite(s.screenAngleDeg())) {
            throw new SimulationException("Angulo no disponible"
                    + (s.angleReason().isBlank() ? "." : ": " + s.angleReason()));
        }
        if (s.power() <= 0) {
            throw new SimulationException("La potencia debe ser mayor que 0.");
        }
        Rect rect = transform.clientRect();
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            throw new SimulationException("No hay area de juego capturada.");
        }

        double angle = s.screenAngleDeg() + s.trajectoryOffsetDeg();
        double speed = s.power() * s.powerScale();
        Vec2 originLocal = transform.sceneToLocal(s.originScene());
        Trajectory trajectory = Ballistics.simulate(originLocal, angle, speed,
                s.gravity(), rect.width(), rect.height());

        double suggestedPower = Double.NaN;
        String status = String.format(
                "Angulo %.1f deg | potencia %.0f | v0 %.0f px/s | AIRE = 0",
                angle, s.power(), speed);
        if (s.targetScene() != null) {
            Vec2 targetLocal = transform.sceneToLocal(s.targetScene());
            suggestedPower = Ballistics.requiredPower(originLocal, targetLocal, angle,
                    s.gravity(), s.powerScale());
            if (Double.isFinite(suggestedPower)) {
                status += String.format(" | potencia sugerida %.1f", suggestedPower);
            } else {
                status += " | destino inalcanzable con este angulo";
            }
        }

        List<Vec2> scenePoints = new ArrayList<>();
        for (Vec2 point : trajectory.decimated(420)) {
            scenePoints.add(transform.localToScene(point.x(), point.y()));
        }
        state.setTrajectory(scenePoints, suggestedPower, status);
        return new SimulationResult(trajectory, angle, speed);
    }

    public double calibratePowerFromImpact(Vec2 impactScene) throws SimulationException {
        SessionState.Snapshot s = state.snapshot();
        if (s.originScene() == null || !s.angleValid() || !Double.isFinite(s.screenAngleDeg())) {
            throw new SimulationException("Necesitas origen y angulo validos para calibrar la potencia.");
        }
        if (s.power() <= 0) {
            throw new SimulationException("Ajusta una potencia conocida mayor que 0 antes de calibrar.");
        }
        Vec2 originLocal = transform.sceneToLocal(s.originScene());
        Vec2 impactLocal = transform.sceneToLocal(impactScene);
        double angle = s.screenAngleDeg() + s.trajectoryOffsetDeg();
        double scale = Ballistics.powerScaleFromImpact(originLocal, impactLocal, angle,
                s.gravity(), s.power());
        if (!Double.isFinite(scale) || scale <= 0) {
            throw new SimulationException("Ese impacto no permite calibrar con el angulo/potencia actuales. "
                    + "Prueba otro disparo (evita angulos casi horizontales o verticales).");
        }
        config.setPowerScale(scale);
        state.setPowerScale(scale);
        state.setStatus(String.format("Potencia calibrada: %.3f px/s por unidad", scale));
        return scale;
    }

    public double calibrateAngle(double knownGameAngle, boolean clockwise)
            throws SimulationException {
        SessionState.Snapshot s = state.snapshot();
        if (!s.angleValid() || !Double.isFinite(s.screenAngleDeg())) {
            throw new SimulationException("No hay una deteccion de angulo valida en este momento.");
        }
        double offset = clockwise
                ? knownGameAngle + s.screenAngleDeg()
                : knownGameAngle - s.screenAngleDeg();
        offset = SessionState.normalize360(offset);
        if (offset > 180) {
            offset -= 360; // rango [-180, 180) para que sea legible
        }
        config.setAngleOffsetDeg(offset);
        config.setClockwiseAngle(clockwise);
        state.updateAngleMapping(offset, clockwise);
        state.setStatus(String.format("Angulo calibrado: offset %.1f deg (%s)",
                offset, clockwise ? "horario" : "antihorario"));
        return offset;
    }

    public void resetAngleTracking() {
        angleTracker.reset();
        latestAngle = null;
    }

    public void resetViewportTracking() {
        lastClientWidth = 0;
        lastClientHeight = 0;
        skipAnchorThisFrame = false;
    }

    public AppConfig config() {
        return config;
    }
}
