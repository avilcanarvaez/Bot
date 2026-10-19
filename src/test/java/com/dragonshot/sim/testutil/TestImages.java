package com.dragonshot.sim.testutil;

import com.dragonshot.sim.capture.Frame;
import java.util.Random;

/** Utilidades para generar frames sinteticos deterministas en las pruebas. */
public final class TestImages {

    private TestImages() {
    }

    public static Frame blank(int width, int height, int value) {
        byte[] data = new byte[width * height];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) clamp(value);
        }
        return Frame.ofGray(width, height, data);
    }

    public static Frame fromData(int width, int height, byte[] data) {
        return Frame.ofGray(width, height, data);
    }

    public static byte[] data(Frame frame) {
        return frame.gray().clone();
    }

    public static void set(byte[] data, int width, int x, int y, int value) {
        if (x < 0 || y < 0 || x >= width || y * width + x >= data.length) {
            return;
        }
        data[y * width + x] = (byte) clamp(value);
    }

    public static void fillRect(byte[] data, int width, int x, int y, int w, int h, int value) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                set(data, width, xx, yy, value);
            }
        }
    }

    public static void drawCircle(byte[] data, int width, int height, double cx, double cy,
            double radius, int value) {
        int x0 = (int) Math.floor(cx - radius);
        int x1 = (int) Math.ceil(cx + radius);
        int y0 = (int) Math.floor(cy - radius);
        int y1 = (int) Math.ceil(cy + radius);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double dx = x - cx;
                double dy = y - cy;
                if (dx * dx + dy * dy <= radius * radius) {
                    set(data, width, x, y, value);
                }
            }
        }
    }

    public static void drawLine(byte[] data, int width, int height,
            double x0, double y0, double x1, double y1, double thickness, int value) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double length = Math.hypot(dx, dy);
        int steps = Math.max(1, (int) Math.ceil(length * 2));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = x0 + dx * t;
            double y = y0 + dy * t;
            drawCircle(data, width, height, x, y, thickness / 2.0, value);
        }
    }

    public static void addNoise(byte[] data, int seed, int amplitude) {
        Random random = new Random(seed);
        for (int i = 0; i < data.length; i++) {
            int value = (data[i] & 0xFF) + random.nextInt(amplitude * 2 + 1) - amplitude;
            data[i] = (byte) clamp(value);
        }
    }

    public static void copyPatch(byte[] src, int srcWidth, byte[] dst, int dstWidth,
            int srcX, int srcY, int dstX, int dstY, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = srcX + x;
                int sy = srcY + y;
                int dx = dstX + x;
                int dy = dstY + y;
                if (sx < 0 || sy < 0 || dx < 0 || dy < 0) {
                    continue;
                }
                int srcIndex = sy * srcWidth + sx;
                int dstIndex = dy * dstWidth + dx;
                if (srcIndex >= 0 && srcIndex < src.length && dstIndex >= 0 && dstIndex < dst.length) {
                    dst[dstIndex] = src[srcIndex];
                }
            }
        }
    }

    public static Frame translate(Frame source, int dx, int dy, int fill) {
        int w = source.width();
        int h = source.height();
        byte[] src = source.gray();
        byte[] dst = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = x - dx;
                int sy = y - dy;
                if (sx >= 0 && sy >= 0 && sx < w && sy < h) {
                    dst[y * w + x] = src[sy * w + sx];
                } else {
                    dst[y * w + x] = (byte) clamp(fill);
                }
            }
        }
        return Frame.ofGray(w, h, dst);
    }

    public static Frame randomTexture(int width, int height, int seed) {
        byte[] data = new byte[width * height];
        Random random = new Random(seed);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (40 + random.nextInt(180));
        }
        return Frame.ofGray(width, height, data);
    }

    public static Frame paste(Frame base, Frame patch, int x, int y) {
        byte[] data = base.gray().clone();
        copyPatch(patch.gray(), patch.width(), data, base.width(),
                0, 0, x, y, patch.width(), patch.height());
        return Frame.ofGray(base.width(), base.height(), data);
    }

    public static Frame resize(Frame source, int width, int height) {
        byte[] data = new byte[width * height];
        double sx = (source.width() - 1.0) / Math.max(1, width - 1.0);
        double sy = (source.height() - 1.0) / Math.max(1, height - 1.0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                data[y * width + x] = (byte) clamp((int) Math.round(
                        source.bilinear(x * sx, y * sy)));
            }
        }
        return Frame.ofGray(width, height, data);
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
