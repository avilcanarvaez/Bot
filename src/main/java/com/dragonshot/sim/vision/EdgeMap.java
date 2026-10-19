package com.dragonshot.sim.vision;

import com.dragonshot.sim.capture.Frame;
import java.util.ArrayList;
import java.util.List;

/**
 * Mapa de bordes por Sobel en una region cuadrada alrededor de un punto.
 * Devuelve puntos con coordenadas absolutas del frame y su magnitud.
 */
public final class EdgeMap {

    public record EdgePoint(int x, int y, double magnitude) {
    }

    private final List<EdgePoint> points;

    private EdgeMap(List<EdgePoint> points) {
        this.points = points;
    }

    public List<EdgePoint> points() {
        return points;
    }

    public static EdgeMap compute(Frame frame, double centerX, double centerY,
            int radius, double percentile) {
        int x0 = Math.max(1, (int) Math.floor(centerX - radius));
        int y0 = Math.max(1, (int) Math.floor(centerY - radius));
        int x1 = Math.min(frame.width() - 2, (int) Math.ceil(centerX + radius));
        int y1 = Math.min(frame.height() - 2, (int) Math.ceil(centerY + radius));
        if (x1 <= x0 || y1 <= y0) {
            return new EdgeMap(List.of());
        }
        int rw = x1 - x0 + 1;
        int rh = y1 - y0 + 1;
        double[] magnitudes = new double[rw * rh];
        int index = 0;
        double maxMagnitude = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double gx = (frame.grayAt(x + 1, y - 1) + 2.0 * frame.grayAt(x + 1, y)
                        + frame.grayAt(x + 1, y + 1))
                        - (frame.grayAt(x - 1, y - 1) + 2.0 * frame.grayAt(x - 1, y)
                        + frame.grayAt(x - 1, y + 1));
                double gy = (frame.grayAt(x - 1, y + 1) + 2.0 * frame.grayAt(x, y + 1)
                        + frame.grayAt(x + 1, y + 1))
                        - (frame.grayAt(x - 1, y - 1) + 2.0 * frame.grayAt(x, y - 1)
                        + frame.grayAt(x + 1, y - 1));
                double magnitude = Math.hypot(gx, gy);
                magnitudes[index++] = magnitude;
                if (magnitude > maxMagnitude) {
                    maxMagnitude = magnitude;
                }
            }
        }

        double[] sorted = magnitudes.clone();
        java.util.Arrays.sort(sorted);
        int pIndex = Math.max(0, Math.min(sorted.length - 1,
                (int) Math.round(sorted.length * Math.max(0, Math.min(1, percentile)))));
        double threshold = Math.max(40.0, sorted[pIndex]);

        List<EdgePoint> points = new ArrayList<>();
        index = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double magnitude = magnitudes[index++];
                if (magnitude >= threshold) {
                    points.add(new EdgePoint(x, y, magnitude));
                }
            }
        }

        // Limita el coste del Hough en escenas muy texturizadas.
        if (points.size() > 6000) {
            List<EdgePoint> reduced = new ArrayList<>(6000);
            double step = points.size() / 6000.0;
            for (int i = 0; i < 6000; i++) {
                reduced.add(points.get((int) Math.round(i * step)));
            }
            points = reduced;
        }
        return new EdgeMap(points);
    }
}
