package com.dragonshot.sim.vision;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Estabiliza la secuencia de angulos detectados:
 *  - mediana circular de las ultimas muestras validas (reduce ruido visual),
 *  - histeresis ante fallos puntuales (no parpadea por un solo frame malo),
 *  - declara "no disponible" tras varios fallos consecutivos, sin reutilizar
 *    indefinidamente un valor viejo.
 */
public final class AngleTracker {

    public record Update(boolean available, double angleDeg, boolean changed, double confidence) {
        public static Update unavailable() {
            return new Update(false, Double.NaN, false, 0);
        }
    }

    private static final int MAX_SAMPLES = 3;
    private static final int INVALID_LIMIT = 2;

    private final Deque<Double> samples = new ArrayDeque<>();
    private int invalidStreak;
    private double lastPublished = Double.NaN;

    public synchronized Update accept(AngleResult result) {
        if (result != null && result.valid()) {
            invalidStreak = 0;
            samples.addLast(AngleDetector.normalize360(result.screenAngleDeg()));
            while (samples.size() > MAX_SAMPLES) {
                samples.removeFirst();
            }
            double median = circularMedian(samples);
            boolean changed = !Double.isFinite(lastPublished)
                    || circularDistance(median, lastPublished) >= 0.5;
            lastPublished = median;
            return new Update(true, median, changed, result.confidence());
        }

        invalidStreak++;
        if (invalidStreak >= INVALID_LIMIT) {
            samples.clear();
            lastPublished = Double.NaN;
            return Update.unavailable();
        }
        if (!Double.isFinite(lastPublished)) {
            // Nunca hubo una muestra valida: no hay nada que conservar.
            return Update.unavailable();
        }
        // Un fallo aislado: se conserva la ultima muestra valida, pero no se
        // publica como cambio.
        return new Update(true, lastPublished, false, 0);
    }

    public synchronized void reset() {
        samples.clear();
        invalidStreak = 0;
        lastPublished = Double.NaN;
    }

    public synchronized boolean isAvailable() {
        return invalidStreak < INVALID_LIMIT && Double.isFinite(lastPublished);
    }

    public synchronized double lastPublished() {
        return lastPublished;
    }

    private static double circularMedian(Deque<Double> values) {
        double reference = values.peekFirst();
        List<Double> unwrapped = new ArrayList<>(values.size());
        for (double value : values) {
            unwrapped.add(reference + circularDelta(value - reference));
        }
        Collections.sort(unwrapped);
        double median = unwrapped.get(unwrapped.size() / 2);
        return AngleDetector.normalize360(median);
    }

    /** Diferencia con signo en [-180, 180). */
    private static double circularDelta(double delta) {
        double d = delta % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static double circularDistance(double a, double b) {
        return Math.abs(circularDelta(a - b));
    }
}
