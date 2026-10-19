package com.dragonshot.sim.capture;

/** Rectangulo en coordenadas AWT/Swing (las mismas que usa Robot y el overlay). */
public record Rect(int x, int y, int width, int height) {
    public boolean isValid() {
        return width > 0 && height > 0;
    }

    public boolean sameAs(Rect other) {
        return other != null
                && x == other.x
                && y == other.y
                && width == other.width
                && height == other.height;
    }

    public Rect clampTo(int screenWidth, int screenHeight) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(screenWidth, x + width);
        int y1 = Math.min(screenHeight, y + height);
        return new Rect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    public Rect scaled(double factor) {
        return new Rect(
                (int) Math.round(x * factor),
                (int) Math.round(y * factor),
                Math.max(1, (int) Math.round(width * factor)),
                Math.max(1, (int) Math.round(height * factor)));
    }
}
