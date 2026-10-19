package com.dragonshot.sim.physics;

import com.dragonshot.sim.geometry.Vec2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resultado inmutable de una simulacion balistica. */
public final class Trajectory {

    private final Vec2 origin;
    private final List<Vec2> points;
    private final Vec2 apex;
    private final Vec2 lastPoint;
    private final double flightTimeSeconds;
    private final boolean leftBounds;

    public Trajectory(Vec2 origin, List<Vec2> points, Vec2 apex, Vec2 lastPoint,
            double flightTimeSeconds, boolean leftBounds) {
        this.origin = origin;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.apex = apex;
        this.lastPoint = lastPoint;
        this.flightTimeSeconds = flightTimeSeconds;
        this.leftBounds = leftBounds;
    }

    public Vec2 origin() {
        return origin;
    }

    public List<Vec2> points() {
        return points;
    }

    public Vec2 apex() {
        return apex;
    }

    public Vec2 lastPoint() {
        return lastPoint;
    }

    public double flightTimeSeconds() {
        return flightTimeSeconds;
    }

    public boolean leftBounds() {
        return leftBounds;
    }

    /** Submuestreo uniforme conservando primero y ultimo punto. */
    public List<Vec2> decimated(int maxPoints) {
        if (points.size() <= maxPoints || maxPoints < 2) {
            return points;
        }
        List<Vec2> result = new ArrayList<>(maxPoints);
        double step = (points.size() - 1.0) / (maxPoints - 1.0);
        for (int i = 0; i < maxPoints; i++) {
            result.add(points.get((int) Math.round(i * step)));
        }
        return result;
    }
}
