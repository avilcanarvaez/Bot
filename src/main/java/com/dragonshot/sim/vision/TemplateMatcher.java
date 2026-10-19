package com.dragonshot.sim.vision;

import com.dragonshot.sim.capture.Frame;

/**
 * Template matching por diferencia absoluta (SAD) con compensacion de brillo y
 * busqueda multi-escala. Se usa para seguir un parche del escenario (ancla) y
 * estimar el desplazamiento de la camara.
 */
public final class TemplateMatcher {

    public record MatchResult(boolean found, double x, double y, double scale, double score) {
        public static MatchResult notFound() {
            return new MatchResult(false, 0, 0, 1, 0);
        }
    }

    private TemplateMatcher() {
    }

    public static MatchResult match(Frame haystack, Frame needle,
            int searchX, int searchY, int searchW, int searchH,
            double[] scales, double minScore) {
        if (haystack == null || needle == null
                || needle.width() < 8 || needle.height() < 8) {
            return MatchResult.notFound();
        }
        if (templateVariance(needle) < 4.0) {
            // Un parche plano no se puede localizar de forma fiable.
            return MatchResult.notFound();
        }

        int hw = haystack.width();
        int hh = haystack.height();
        int sx0 = Math.max(0, searchX);
        int sy0 = Math.max(0, searchY);
        int sx1 = Math.min(hw, searchX + Math.max(1, searchW));
        int sy1 = Math.min(hh, searchY + Math.max(1, searchH));
        if (sx1 <= sx0 || sy1 <= sy0) {
            return MatchResult.notFound();
        }

        MatchResult best = MatchResult.notFound();
        double bestRaw = -Double.MAX_VALUE;

        for (double scale : scales) {
            if (!Double.isFinite(scale) || scale < 0.5 || scale > 2.5) {
                continue;
            }
            int tw = Math.max(8, (int) Math.round(needle.width() * scale));
            int th = Math.max(8, (int) Math.round(needle.height() * scale));
            if (tw > hw || th > hh || tw > sx1 - sx0 || th > sy1 - sy0) {
                continue;
            }
            Frame template = Math.abs(scale - 1.0) < 1e-6
                    ? needle
                    : resize(needle, tw, th);

            int maxX = Math.min(sx1 - tw, hw - tw);
            int maxY = Math.min(sy1 - th, hh - th);
            if (maxX < sx0 || maxY < sy0) {
                continue;
            }

            int localBestX = -1;
            int localBestY = -1;
            double localBest = -Double.MAX_VALUE;

            // Se busca desde el centro de la ventana (posicion esperada del
            // ancla) hacia fuera. Asi el acierto correcto se encuentra pronto y
            // el resto de posiciones se descartan con muy pocos pixeles.
            int centerX = (sx0 + maxX) / 2;
            int centerY = (sy0 + maxY) / 2;
            int[] xs = orderByDistance(sx0, maxX, centerX);
            int[] ys = orderByDistance(sy0, maxY, centerY);
            for (int y : ys) {
                for (int x : xs) {
                    double threshold = Math.max(minScore, localBest);
                    double score = sadScore(haystack, template, x, y, threshold);
                    if (score > localBest) {
                        localBest = score;
                        localBestX = x;
                        localBestY = y;
                    }
                }
            }
            if (localBestX < 0) {
                continue;
            }

            if (localBest > bestRaw) {
                bestRaw = localBest;
                double subX = parabolicOffset(
                        sadScore(haystack, template, localBestX - 1, localBestY, 0),
                        localBest,
                        sadScore(haystack, template, localBestX + 1, localBestY, 0));
                double subY = parabolicOffset(
                        sadScore(haystack, template, localBestX, localBestY - 1, 0),
                        localBest,
                        sadScore(haystack, template, localBestX, localBestY + 1, 0));
                best = new MatchResult(true,
                        localBestX + subX,
                        localBestY + subY,
                        scale,
                        clamp01(localBest));
                if (localBest >= 0.985) {
                    break;
                }
            }
        }

        if (best.score() < minScore) {
            return MatchResult.notFound();
        }
        return best;
    }

    private static int[] orderByDistance(int from, int to, int center) {
        int count = to - from + 1;
        Integer[] boxed = new Integer[count];
        for (int i = 0; i < count; i++) {
            boxed[i] = from + i;
        }
        java.util.Arrays.sort(boxed,
                java.util.Comparator.comparingInt(value -> Math.abs(value - center)));
        int[] ordered = new int[count];
        for (int i = 0; i < count; i++) {
            ordered[i] = boxed[i];
        }
        return ordered;
    }

    private static double sadScore(Frame haystack, Frame template,
            int offsetX, int offsetY, double thresholdScore) {
        int tw = template.width();
        int th = template.height();
        if (offsetX < 0 || offsetY < 0
                || offsetX + tw > haystack.width()
                || offsetY + th > haystack.height()) {
            return -Double.MAX_VALUE;
        }
        // Compensacion de brillo usando el pixel central como referencia.
        int cx = tw / 2;
        int cy = th / 2;
        int offset = haystack.grayAt(offsetX + cx, offsetY + cy) - template.grayAt(cx, cy);
        double threshold = Math.max(0, Math.min(1, thresholdScore));
        long maxSad = (long) ((1.0 - threshold) * 255.0 * tw * th);
        long sad = 0;
        byte[] h = haystack.gray();
        byte[] t = template.gray();
        int hw = haystack.width();
        for (int y = 0; y < th; y++) {
            int hRow = (offsetY + y) * hw + offsetX;
            int tRow = y * tw;
            for (int x = 0; x < tw; x++) {
                int hv = h[hRow + x] & 0xFF;
                int tv = t[tRow + x] & 0xFF;
                int diff = hv - tv - offset;
                sad += diff < 0 ? -diff : diff;
            }
            if (sad > maxSad) {
                return -1;
            }
        }
        return 1.0 - (sad / (255.0 * tw * th));
    }

    private static double parabolicOffset(double left, double center, double right) {
        double denominator = left - 2 * center + right;
        if (Math.abs(denominator) < 1e-9) {
            return 0;
        }
        double offset = 0.5 * (left - right) / denominator;
        if (!Double.isFinite(offset)) {
            return 0;
        }
        return Math.max(-0.5, Math.min(0.5, offset));
    }

    private static Frame resize(Frame source, int width, int height) {
        byte[] out = new byte[width * height];
        double sx = (source.width() - 1.0) / Math.max(1, width - 1.0);
        double sy = (source.height() - 1.0) / Math.max(1, height - 1.0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double v = source.bilinear(x * sx, y * sy);
                out[y * width + x] = (byte) Math.max(0, Math.min(255, (int) Math.round(v)));
            }
        }
        return Frame.ofGray(width, height, out);
    }

    private static double mean(Frame frame) {
        long sum = 0;
        byte[] data = frame.gray();
        for (byte b : data) {
            sum += b & 0xFF;
        }
        return sum / (double) data.length;
    }

    private static double templateVariance(Frame frame) {
        double mean = mean(frame);
        double sum = 0;
        for (byte b : frame.gray()) {
            double d = (b & 0xFF) - mean;
            sum += d * d;
        }
        return sum / frame.gray().length;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
