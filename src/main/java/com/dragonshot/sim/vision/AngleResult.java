package com.dragonshot.sim.vision;

import com.dragonshot.sim.geometry.Vec2;

/** Resultado del detector visual de inclinacion del canon. */
public record AngleResult(boolean valid, double screenAngleDeg, double confidence,
        int edgeCount, Vec2 pivot, Vec2 tip, double lineRho, String reason) {

    public static AngleResult invalid(Vec2 pivot, String reason) {
        return new AngleResult(false, Double.NaN, 0, 0, pivot,
                pivot == null ? Vec2.ZERO : pivot, 0, reason);
    }
}
