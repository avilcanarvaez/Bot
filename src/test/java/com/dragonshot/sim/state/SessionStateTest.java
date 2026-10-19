package com.dragonshot.sim.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonshot.sim.geometry.SceneRect;
import com.dragonshot.sim.geometry.Vec2;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionStateTest {

    private static final double EPS = 1e-9;

    @Test
    void cameraPanMovesAllWorldAnchoredElements() {
        SessionState state = new SessionState();
        state.setOrigin(new Vec2(0.5, 0.5));
        state.setTarget(new Vec2(0.7, 0.4));
        state.setAnchorRect(new SceneRect(0.1, 0.1, 0.1, 0.1));
        state.setTrajectory(List.of(new Vec2(0.5, 0.5), new Vec2(0.6, 0.4)), Double.NaN, "ok");

        state.applyAnchorUpdate(new Vec2(0.05, -0.02), 1.0, new Vec2(0.15, 0.15));

        assertEquals(0.55, state.originScene().x(), EPS);
        assertEquals(0.48, state.originScene().y(), EPS);
        assertEquals(0.75, state.targetScene().x(), EPS);
        assertEquals(0.38, state.targetScene().y(), EPS);
        assertEquals(0.15, state.anchorScene().x(), EPS);
        assertEquals(0.08, state.anchorScene().y(), EPS);
        assertEquals(0.55, state.snapshot().trajectoryScene().get(0).x(), EPS);
        assertEquals(0.48, state.snapshot().trajectoryScene().get(0).y(), EPS);
        assertFalse(state.snapshot().trajectoryStale());
    }

    @Test
    void cameraZoomScalesAroundAnchorCenter() {
        SessionState state = new SessionState();
        state.setOrigin(new Vec2(0.5, 0.5));
        state.setTarget(new Vec2(0.7, 0.4));
        state.setAnchorRect(new SceneRect(0.1, 0.1, 0.1, 0.1));

        state.applyAnchorUpdate(Vec2.ZERO, 2.0, new Vec2(0.15, 0.15));

        assertEquals(0.85, state.originScene().x(), EPS);
        assertEquals(0.85, state.originScene().y(), EPS);
        assertEquals(1.25, state.targetScene().x(), EPS);
        assertEquals(0.65, state.targetScene().y(), EPS);
        assertEquals(0.2, state.anchorScene().width(), EPS);
        assertEquals(0.2, state.anchorScene().height(), EPS);
    }

    @Test
    void unavailableAngleDoesNotReuseCurrentValue() {
        SessionState state = new SessionState();
        state.setAngle(45, 45, 0.8);
        assertTrue(state.isAngleValid());
        assertEquals(45, state.screenAngleDeg(), EPS);

        state.markAngleUnavailable("sin bordes");
        assertFalse(state.isAngleValid());
        assertTrue(Double.isNaN(state.screenAngleDeg()));
        SessionState.Snapshot snapshot = state.snapshot();
        assertFalse(snapshot.angleValid());
        assertEquals(45, snapshot.lastValidScreenAngleDeg(), EPS);
        assertEquals("sin bordes", snapshot.angleReason());
    }

    @Test
    void changingAngleMarksTrajectoryStale() {
        SessionState state = new SessionState();
        state.setTrajectory(List.of(new Vec2(0.2, 0.2)), Double.NaN, "ok");
        state.setAngle(45, 45, 0.8);
        state.setAngle(46, 46, 0.8);
        assertTrue(state.snapshot().trajectoryStale());
    }

    @Test
    void changingPowerMarksTrajectoryStale() {
        SessionState state = new SessionState();
        state.setTrajectory(List.of(new Vec2(0.2, 0.2)), Double.NaN, "ok");
        state.setPower(80);
        assertTrue(state.snapshot().trajectoryStale());
    }

    @Test
    void changingTargetMarksSuggestedPowerStale() {
        SessionState state = new SessionState();
        state.setTarget(new Vec2(0.7, 0.4));
        state.setTrajectory(List.of(new Vec2(0.2, 0.2)), 42.0, "ok");
        state.setTarget(new Vec2(0.8, 0.4));
        assertTrue(state.snapshot().trajectoryStale());
    }

    @Test
    void angleMappingSupportsClockwiseAndCounterClockwise() {
        SessionState state = new SessionState();
        state.setAngle(45, 45, 0.8);
        state.updateAngleMapping(10, false);
        assertEquals(55, state.snapshot().gameAngleDeg(), EPS);
        state.updateAngleMapping(10, true);
        assertEquals(325, state.snapshot().gameAngleDeg(), EPS);
    }

    @Test
    void clearPointsRemovesEverything() {
        SessionState state = new SessionState();
        state.setOrigin(new Vec2(0.4, 0.4));
        state.setTarget(new Vec2(0.6, 0.4));
        state.setAnchorRect(new SceneRect(0.1, 0.1, 0.1, 0.1));
        state.setTrajectory(List.of(new Vec2(0.4, 0.4)), Double.NaN, "ok");
        state.clearPoints();
        SessionState.Snapshot snapshot = state.snapshot();
        assertTrue(snapshot.originScene() == null);
        assertTrue(snapshot.targetScene() == null);
        assertTrue(snapshot.anchorScene() == null);
        assertTrue(snapshot.trajectoryScene().isEmpty());
        assertFalse(snapshot.angleValid());
    }
}
