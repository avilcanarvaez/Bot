package com.dragonshot.sim.capture;

import java.awt.image.BufferedImage;

/**
 * Fotograma en escala de grises del area cliente de la ventana.
 *
 * Se guarda un unico canal (8 bits) porque tanto el template matching como el
 * detector de angulo trabajan con luminancia. Esto reduce a la cuarta parte el
 * coste de memoria frente a ARGB.
 */
public final class Frame {

    private final int width;
    private final int height;
    private final byte[] gray;
    private final long timestampNanos;

    public Frame(int width, int height, byte[] gray, long timestampNanos) {
        if (gray.length != width * height) {
            throw new IllegalArgumentException("gray.length no coincide con width*height");
        }
        this.width = width;
        this.height = height;
        this.gray = gray;
        this.timestampNanos = timestampNanos;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long timestampNanos() {
        return timestampNanos;
    }

    public byte[] gray() {
        return gray;
    }

    /** Valor 0..255 en coordenadas enteras. */
    public int grayAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return gray[y * width + x] & 0xFF;
    }

    /** Muestreo bilineal con saturacion en los bordes. */
    public double bilinear(double x, double y) {
        if (x < 0) {
            x = 0;
        } else if (x > width - 1.001) {
            x = width - 1.001;
        }
        if (y < 0) {
            y = 0;
        } else if (y > height - 1.001) {
            y = height - 1.001;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        double fx = x - x0;
        double fy = y - y0;
        double v00 = grayAt(x0, y0);
        double v10 = grayAt(x1, y0);
        double v01 = grayAt(x0, y1);
        double v11 = grayAt(x1, y1);
        double top = v00 + (v10 - v00) * fx;
        double bottom = v01 + (v11 - v01) * fx;
        return top + (bottom - top) * fy;
    }

    /** Recorte rectangular; las coordenadas fuera del frame se saturan. */
    public Frame crop(int x, int y, int w, int h) {
        int x0 = Math.max(0, Math.min(width - 1, x));
        int y0 = Math.max(0, Math.min(height - 1, y));
        int x1 = Math.max(x0 + 1, Math.min(width, x + Math.max(1, w)));
        int y1 = Math.max(y0 + 1, Math.min(height, y + Math.max(1, h)));
        int cw = x1 - x0;
        int ch = y1 - y0;
        byte[] out = new byte[cw * ch];
        for (int yy = 0; yy < ch; yy++) {
            System.arraycopy(gray, (y0 + yy) * width + x0, out, yy * cw, cw);
        }
        return new Frame(cw, ch, out, timestampNanos);
    }

    public static Frame fromBufferedImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] gray = new byte[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            gray[i] = (byte) lum;
        }
        return new Frame(w, h, gray, System.nanoTime());
    }

    /** Constructor auxiliar para pruebas y frames sinteticos. */
    public static Frame ofGray(int width, int height, byte[] gray) {
        return new Frame(width, height, gray, System.nanoTime());
    }
}
