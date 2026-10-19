package com.dragonshot.sim.physics;

import com.dragonshot.sim.geometry.Vec2;
import java.util.ArrayList;
import java.util.List;

/**
 * Balistica ideal con AIRE = 0.
 *
 * Convencion de angulos de pantalla:
 *   0   = derecha (+X)
 *   90  = arriba  (-Y en pantalla)
 *   180 = izquierda
 *   270 = abajo
 *
 * Por eso la componente vertical inicial es vy = -v0*sin(theta) y la gravedad
 * se suma con signo positivo sobre Y (la pantalla crece hacia abajo):
 *
 *   x(t) = x0 + vx*t
 *   y(t) = y0 + vy*t + 0.5*g*t^2
 *
 * No se aplica ningun termino de arrastre. AIRE_DENSITY es una constante 0 y
 * el unico DragModel usado por la aplicacion es DragModel.NO_AIR.
 */
public final class Ballistics {

    public static final double AIR_DENSITY = 0.0;
    public static final double DEFAULT_GRAVITY = 500.0;
    public static final double DEFAULT_POWER_SCALE = 10.0;

    private static final double DT = 1.0 / 240.0;
    private static final double MAX_TIME = 10.0;
    private static final double BOUNDS_MARGIN = 160.0;

    private Ballistics() {
    }

    public static Vec2 initialVelocity(double angleDegScreenCCW, double speed) {
        double theta = Math.toRadians(angleDegScreenCCW);
        return new Vec2(speed * Math.cos(theta), -speed * Math.sin(theta));
    }

    /**
     * Simula hasta que el proyectil sale del area visible (con margen) o hasta
     * 10 segundos. Devuelve una polilinea densa; el overlay la submuestrea.
     */
    public static Trajectory simulate(Vec2 origin, double angleDegScreenCCW,
            double speed, double gravity, int viewWidth, int viewHeight) {
        return simulate(origin, angleDegScreenCCW, speed, gravity, viewWidth, viewHeight,
                DragModel.NO_AIR);
    }

    public static Trajectory simulate(Vec2 origin, double angleDegScreenCCW,
            double speed, double gravity, int viewWidth, int viewHeight, DragModel dragModel) {
        if (origin == null || !origin.isFinite()) {
            throw new IllegalArgumentException("Origen invalido");
        }
        if (!Double.isFinite(angleDegScreenCCW) || !Double.isFinite(speed)
                || !Double.isFinite(gravity)) {
            throw new IllegalArgumentException("Parametros no finitos");
        }
        if (speed < 0 || gravity <= 0) {
            throw new IllegalArgumentException("Velocidad/gravedad invalidas");
        }
        DragModel drag = dragModel == null ? DragModel.NO_AIR : dragModel;

        double theta = Math.toRadians(angleDegScreenCCW);
        double vx = speed * Math.cos(theta);
        double vy = -speed * Math.sin(theta);
        double x = origin.x();
        double y = origin.y();

        List<Vec2> points = new ArrayList<>(1024);
        points.add(new Vec2(x, y));
        Vec2 apex = new Vec2(x, y);
        Vec2 last = new Vec2(x, y);
        boolean left = false;
        double t = 0.0;

        while (t < MAX_TIME) {
            Vec2 accel = drag.acceleration(new Vec2(vx, vy));
            double ax = accel.x();
            double ay = accel.y() + gravity;
            vx += ax * DT;
            vy += ay * DT;
            x += vx * DT;
            y += vy * DT;
            t += DT;

            last = new Vec2(x, y);
            points.add(last);
            if (y < apex.y()) {
                apex = last;
            }

            boolean outside = x < -BOUNDS_MARGIN || x > viewWidth + BOUNDS_MARGIN
                    || y < -BOUNDS_MARGIN || y > viewHeight + BOUNDS_MARGIN;
            if (outside && t > DT * 4) {
                left = true;
                break;
            }
        }
        return new Trajectory(origin, points, apex, last, t, left);
    }

    /**
     * Velocidad inicial necesaria para que un proyectil lanzado con este angulo
     * pase exactamente por target. Devuelve NaN si no existe solucion finita
     * (por ejemplo, tiro horizontal al mismo nivel o angulo vertical).
     *
     * Deduccion con Y fisica hacia arriba (Y = -y_pantalla):
     *   Y = tan(theta)*dx - g*dx^2 / (2*v0^2*cos^2(theta))
     * Despejando:
     *   v0^2 = g*dx^2 / (2*cos^2(theta)*(tan(theta)*dx + dy))
     */
    public static double requiredSpeedForTarget(Vec2 origin, Vec2 target,
            double angleDegScreenCCW, double gravity) {
        if (origin == null || target == null || !origin.isFinite() || !target.isFinite()) {
            return Double.NaN;
        }
        double dx = target.x() - origin.x();
        double dy = target.y() - origin.y();
        double theta = Math.toRadians(angleDegScreenCCW);
        double cos = Math.cos(theta);
        double tan = Math.tan(theta);
        if (Math.abs(dx) < 1e-9 || Math.abs(cos) < 1e-9) {
            return Double.NaN;
        }
        double denominator = 2.0 * cos * cos * (tan * dx + dy);
        if (!Double.isFinite(denominator) || denominator <= 1e-9) {
            return Double.NaN;
        }
        double v2 = gravity * dx * dx / denominator;
        if (!Double.isFinite(v2) || v2 <= 0) {
            return Double.NaN;
        }
        return Math.sqrt(v2);
    }

    /**
     * Calibra la escala potencia->velocidad usando un disparo real cuyo punto de
     * impacto se ha marcado en pantalla. La gravedad se mantiene como parametro
     * fijo; un solo disparo determina la relacion g/powerScale^2, no ambas cosas.
     */
    public static double powerScaleFromImpact(Vec2 origin, Vec2 impact,
            double angleDegScreenCCW, double gravity, double power) {
        if (power <= 0 || !Double.isFinite(power)) {
            return Double.NaN;
        }
        double speed = requiredSpeedForTarget(origin, impact, angleDegScreenCCW, gravity);
        if (!Double.isFinite(speed) || speed <= 0) {
            return Double.NaN;
        }
        return speed / power;
    }

    public static double requiredPower(Vec2 origin, Vec2 target, double angleDegScreenCCW,
            double gravity, double powerScale) {
        if (powerScale <= 0 || !Double.isFinite(powerScale)) {
            return Double.NaN;
        }
        double speed = requiredSpeedForTarget(origin, target, angleDegScreenCCW, gravity);
        if (!Double.isFinite(speed)) {
            return Double.NaN;
        }
        return speed / powerScale;
    }
}
