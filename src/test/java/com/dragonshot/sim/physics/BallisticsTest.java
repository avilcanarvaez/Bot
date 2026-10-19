package com.dragonshot.sim.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonshot.sim.geometry.Vec2;
import java.util.List;
import org.junit.jupiter.api.Test;

class BallisticsTest {

    private static final double EPS = 1e-9;

    @Test
    void trajectoryStartsAtOriginAndUsesAirZero() {
        Vec2 origin = new Vec2(100, 120);
        Trajectory trajectory = Ballistics.simulate(origin, 35, 400, 500, 800, 600);
        assertEquals(origin.x(), trajectory.points().get(0).x(), EPS);
        assertEquals(origin.y(), trajectory.points().get(0).y(), EPS);
        assertEquals(0.0, Ballistics.AIR_DENSITY, EPS);
        assertEquals(0.0, DragModel.NO_AIR.acceleration(new Vec2(500, -500)).length(), EPS);
    }

    @Test
    void angleZeroGoesRightWithoutVerticalDirection() {
        Vec2 origin = new Vec2(100, 100);
        Trajectory trajectory = Ballistics.simulate(origin, 0, 300, 500, 800, 600);
        Vec2 p1 = trajectory.points().get(10);
        assertTrue(p1.x() > origin.x());
        assertEquals(origin.y(), p1.y(), 1.0);
    }

    @Test
    void angle90GoesUp() {
        Vec2 origin = new Vec2(100, 300);
        Trajectory trajectory = Ballistics.simulate(origin, 90, 300, 500, 800, 600);
        Vec2 p1 = trajectory.points().get(10);
        assertTrue(p1.y() < origin.y());
        assertEquals(origin.x(), p1.x(), 0.2);
    }

    @Test
    void angle180GoesLeft() {
        Vec2 origin = new Vec2(400, 300);
        Trajectory trajectory = Ballistics.simulate(origin, 180, 300, 500, 800, 600);
        Vec2 p1 = trajectory.points().get(10);
        assertTrue(p1.x() < origin.x());
    }

    @Test
    void angle270GoesDown() {
        Vec2 origin = new Vec2(400, 100);
        Trajectory trajectory = Ballistics.simulate(origin, 270, 300, 500, 800, 600);
        Vec2 p1 = trajectory.points().get(10);
        assertTrue(p1.y() > origin.y());
    }

    @Test
    void quadrantsHaveCorrectSigns() {
        Vec2 origin = new Vec2(400, 300);
        assertQuadrant(origin, 30, 1, -1);
        assertQuadrant(origin, 150, -1, -1);
        assertQuadrant(origin, 210, -1, 1);
        assertQuadrant(origin, 330, 1, 1);
    }

    private static void assertQuadrant(Vec2 origin, double angle, int expectedX, int expectedY) {
        Trajectory trajectory = Ballistics.simulate(origin, angle, 300, 500, 800, 600);
        Vec2 p = trajectory.points().get(6);
        assertEquals(expectedX, (int) Math.signum(p.x() - origin.x()), "dx angle " + angle);
        assertEquals(expectedY, (int) Math.signum(p.y() - origin.y()), "dy angle " + angle);
    }

    @Test
    void requiredSpeedAt45DegreesMatchesRangeFormula() {
        Vec2 origin = new Vec2(0, 0);
        Vec2 target = new Vec2(100, 0);
        double speed = Ballistics.requiredSpeedForTarget(origin, target, 45, 500);
        assertEquals(223.60679775, speed, 0.001);
        double range = speed * speed / 500.0;
        assertEquals(100.0, range, 0.001);
    }

    @Test
    void powerScaleRoundTrip() {
        Vec2 origin = new Vec2(0, 0);
        double angle = 45;
        double gravity = 500;
        double speed = 300;
        double power = 50;
        // Punto analitico de la trayectoria a x=100.
        double theta = Math.toRadians(angle);
        double t = 100.0 / (speed * Math.cos(theta));
        double y = origin.y() - speed * Math.sin(theta) * t + 0.5 * gravity * t * t;
        Vec2 impact = new Vec2(100, y);
        double recovered = Ballistics.requiredSpeedForTarget(origin, impact, angle, gravity);
        assertEquals(speed, recovered, 0.05);
        double scale = Ballistics.powerScaleFromImpact(origin, impact, angle, gravity, power);
        assertEquals(6.0, scale, 0.002);
    }

    @Test
    void decimationPreservesEndpoints() {
        Trajectory trajectory = Ballistics.simulate(new Vec2(0, 0), 45, 300, 500, 800, 600);
        List<Vec2> decimated = trajectory.decimated(40);
        assertEquals(trajectory.points().get(0), decimated.get(0));
        assertEquals(trajectory.points().get(trajectory.points().size() - 1),
                decimated.get(decimated.size() - 1));
        assertTrue(decimated.size() <= 40);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Ballistics.simulate(new Vec2(0, 0), 0, -1, 500, 800, 600));
        assertThrows(IllegalArgumentException.class,
                () -> Ballistics.simulate(new Vec2(0, 0), 0, 100, 0, 800, 600));
    }

    @Test
    void horizontalShotDoesNotHaveFiniteSolutionForSameHeight() {
        double speed = Ballistics.requiredSpeedForTarget(
                new Vec2(0, 0), new Vec2(100, 0), 0, 500);
        assertTrue(Double.isNaN(speed));
        assertFalse(Double.isFinite(speed));
    }

    @Test
    void noAirProducesIdenticalTrajectories() {
        Vec2 origin = new Vec2(20, 30);
        Trajectory a = Ballistics.simulate(origin, 55, 360, 480, 900, 700);
        Trajectory b = Ballistics.simulate(origin, 55, 360, 480, 900, 700,
                DragModel.NO_AIR);
        assertEquals(a.points().size(), b.points().size());
        for (int i = 0; i < a.points().size(); i++) {
            assertEquals(a.points().get(i).x(), b.points().get(i).x(), 0.0);
            assertEquals(a.points().get(i).y(), b.points().get(i).y(), 0.0);
        }
    }
}
