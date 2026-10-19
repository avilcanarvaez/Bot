package com.dragonshot.sim.geometry;

/**
 * Rectangulo en coordenadas de escena normalizadas [0..1].
 *
 * Es independiente del tamano de la ventana: si la ventana se mueve, el
 * rectangulo conserva su posicion relativa; si cambia de tamano, se reescala
 * de forma uniforme con el area cliente.
 */
public record SceneRect(double x, double y, double width, double height) {

    public static SceneRect fromLocal(double x, double y, double w, double h,
            double clientWidth, double clientHeight) {
        if (clientWidth <= 0 || clientHeight <= 0) {
            throw new IllegalArgumentException("Dimension cliente invalida");
        }
        return new SceneRect(
                x / clientWidth,
                y / clientHeight,
                w / clientWidth,
                h / clientHeight);
    }

    public SceneRect shift(double dx, double dy) {
        return new SceneRect(x + dx, y + dy, width, height);
    }

    public SceneRect scaleAround(double pivotX, double pivotY, double scale) {
        return new SceneRect(
                pivotX + (x - pivotX) * scale,
                pivotY + (y - pivotY) * scale,
                width * scale,
                height * scale);
    }

    public double centerX() {
        return x + width / 2.0;
    }

    public double centerY() {
        return y + height / 2.0;
    }

    public double pixelWidth(double clientWidth) {
        return width * clientWidth;
    }

    public double pixelHeight(double clientHeight) {
        return height * clientHeight;
    }
}
