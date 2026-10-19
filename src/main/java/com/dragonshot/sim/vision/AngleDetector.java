package com.dragonshot.sim.vision;

import com.dragonshot.sim.capture.Frame;
import com.dragonshot.sim.geometry.Vec2;
import java.util.List;

/**
 * Detecta la direccion del canon a partir de los bordes de la imagen.
 *
 * Estrategia:
 *  1. Sobel en una region alrededor del pivote del canon (el punto ORIGEN).
 *  2. Transformada de Hough de lineas, limitada a lineas que pasan a menos de
 *     rhoTolerance pixeles del pivote (el canon puede estar ligeramente
 *     desplazado respecto del centro de giro marcado).
 *  3. La direccion de la linea se elige hacia el lado con mas soporte de bordes
 *     a radios grandes, para no devolver la direccion opuesta.
 *
 * No se inventa un angulo: si no hay suficientes bordes o la linea ganadora no
 * destaca sobre las demas, se devuelve un resultado invalido con el motivo.
 */
public final class AngleDetector {

    private static final int ANGLE_BINS = 720; // 0.5 grados
    private static final double ANGLE_STEP_DEG = 180.0 / ANGLE_BINS; // lineas no dirigidas
    private static final int RHO_TOLERANCE = 25;
    private static final int RHO_BINS = RHO_TOLERANCE * 2 + 1;
    private static final double[] COS = new double[ANGLE_BINS];
    private static final double[] SIN = new double[ANGLE_BINS];

    static {
        for (int i = 0; i < ANGLE_BINS; i++) {
            double theta = Math.toRadians(i * ANGLE_STEP_DEG);
            COS[i] = Math.cos(theta);
            SIN[i] = Math.sin(theta);
        }
    }

    private AngleDetector() {
    }

    public static AngleResult detect(Frame frame, Vec2 pivot, int rMin, int rMax,
            double minConfidence) {
        if (frame == null || pivot == null || !pivot.isFinite()) {
            return AngleResult.invalid(pivot, "Frame o pivote invalido");
        }
        if (pivot.x() < 0 || pivot.y() < 0
                || pivot.x() >= frame.width() || pivot.y() >= frame.height()) {
            return AngleResult.invalid(pivot, "El pivote esta fuera del area capturada");
        }
        int minRadius = Math.max(6, rMin);
        int maxRadius = Math.max(minRadius + 10, rMax);
        EdgeMap edgeMap = EdgeMap.compute(frame, pivot.x(), pivot.y(), maxRadius + 4, 0.75);
        List<EdgeMap.EdgePoint> edges = edgeMap.points();
        if (edges.size() < 15) {
            return AngleResult.invalid(pivot, "Muy pocos bordes en la region del canon");
        }

        double[][] accumulator = new double[ANGLE_BINS][RHO_BINS];
        double[] angleScore = new double[ANGLE_BINS];
        int used = 0;
        double magnitudeSum = 0;
        for (EdgeMap.EdgePoint point : edges) {
            double dx = point.x() - pivot.x();
            double dy = point.y() - pivot.y();
            double radius = Math.hypot(dx, dy);
            if (radius < minRadius || radius > maxRadius) {
                continue;
            }
            used++;
            magnitudeSum += point.magnitude();
            double weight = Math.min(1020.0, point.magnitude());
            for (int i = 0; i < ANGLE_BINS; i++) {
                double rho = dx * COS[i] + dy * SIN[i];
                if (rho < -RHO_TOLERANCE || rho > RHO_TOLERANCE) {
                    continue;
                }
                int rhoBin = (int) Math.round(rho) + RHO_TOLERANCE;
                accumulator[i][rhoBin] += weight;
                if (accumulator[i][rhoBin] > angleScore[i]) {
                    angleScore[i] = accumulator[i][rhoBin];
                }
            }
        }
        if (used < 15 || magnitudeSum <= 0) {
            return AngleResult.invalid(pivot, "Sin bordes suficientes alrededor del pivote");
        }

        int bestIndex = 0;
        for (int i = 1; i < ANGLE_BINS; i++) {
            if (angleScore[i] > angleScore[bestIndex]) {
                bestIndex = i;
            }
        }
        double bestScore = angleScore[bestIndex];
        double secondScore = 0;
        for (int i = 0; i < ANGLE_BINS; i++) {
            int delta = Math.min(Math.abs(i - bestIndex), ANGLE_BINS - Math.abs(i - bestIndex));
            if (delta > 40 && angleScore[i] > secondScore) { // excluye +-10 grados
                secondScore = angleScore[i];
            }
        }

        double meanMagnitude = magnitudeSum / used;
        double alignment = bestScore / Math.max(1e-9, used * meanMagnitude);
        double prominence = (bestScore - secondScore) / Math.max(1e-9, bestScore);
        double confidence = clamp01(0.55 * prominence + 0.45 * Math.min(1.0, alignment * 2.0));
        if (alignment < 0.10 || confidence < minConfidence) {
            return new AngleResult(false, Double.NaN, confidence, used, pivot, pivot, 0,
                    String.format("Confianza insuficiente (alineacion %.2f, confianza %.2f)",
                            alignment, confidence));
        }

        int bestRhoBin = 0;
        for (int r = 1; r < RHO_BINS; r++) {
            if (accumulator[bestIndex][r] > accumulator[bestIndex][bestRhoBin]) {
                bestRhoBin = r;
            }
        }
        int bestRho = bestRhoBin - RHO_TOLERANCE;

        // En coordenadas de pantalla (Y hacia abajo), una linea con direccion
        // alpha tiene normal theta = 90 - alpha. Por tanto alpha = 90 - theta.
        double normalDeg = bestIndex * ANGLE_STEP_DEG;
        double directionDeg = normalize360(90.0 - normalDeg);
        double dirRad = Math.toRadians(directionDeg);
        double ux = Math.cos(dirRad);
        double uy = -Math.sin(dirRad);

        double farPositive = 0;
        double farNegative = 0;
        double maxPositive = 0;
        double maxNegative = 0;
        for (EdgeMap.EdgePoint point : edges) {
            double dx = point.x() - pivot.x();
            double dy = point.y() - pivot.y();
            double radius = Math.hypot(dx, dy);
            if (radius < minRadius || radius > maxRadius) {
                continue;
            }
            double rho = dx * COS[bestIndex] + dy * SIN[bestIndex];
            if (Math.abs(rho - bestRho) > 4.0) {
                continue;
            }
            double projection = dx * ux + dy * uy;
            double weight = Math.min(1020.0, point.magnitude());
            if (projection > 0) {
                maxPositive = Math.max(maxPositive, projection);
                if (projection > maxRadius * 0.45) {
                    farPositive += weight;
                }
            } else if (projection < 0) {
                maxNegative = Math.max(maxNegative, -projection);
                if (-projection > maxRadius * 0.45) {
                    farNegative += weight;
                }
            }
        }
        if (farNegative > farPositive * 1.15
                || (Math.abs(farNegative - farPositive) < 1e-9 && maxNegative > maxPositive * 1.15)) {
            directionDeg = normalize360(directionDeg + 180.0);
        }

        double dirRadFinal = Math.toRadians(directionDeg);
        Vec2 tip = new Vec2(
                pivot.x() + Math.cos(dirRadFinal) * maxRadius,
                pivot.y() - Math.sin(dirRadFinal) * maxRadius);
        return new AngleResult(true, directionDeg, confidence, used, pivot, tip, bestRho, "");
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    public static double normalize360(double angle) {
        double a = angle % 360.0;
        if (a < 0) {
            a += 360.0;
        }
        return a;
    }
}
