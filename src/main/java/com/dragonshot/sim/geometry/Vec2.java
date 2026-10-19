package com.dragonshot.sim.geometry;

/** Punto/vector inmutable de doble precision. */
public record Vec2(double x, double y) {

    public static final Vec2 ZERO = new Vec2(0, 0);

    public Vec2 add(Vec2 other) {
        return new Vec2(x + other.x, y + other.y);
    }

    public Vec2 add(double dx, double dy) {
        return new Vec2(x + dx, y + dy);
    }

    public Vec2 sub(Vec2 other) {
        return new Vec2(x - other.x, y - other.y);
    }

    public Vec2 mul(double factor) {
        return new Vec2(x * factor, y * factor);
    }

    public double length() {
        return Math.hypot(x, y);
    }

    public double distanceTo(Vec2 other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    public Vec2 normalized() {
        double len = length();
        if (len < 1e-12) {
            return ZERO;
        }
        return new Vec2(x / len, y / len);
    }

    public double dot(Vec2 other) {
        return x * other.x + y * other.y;
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y);
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)", x, y);
    }
}
