package com.dragonshot.sim.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonshot.sim.capture.CaptureEvent;
import com.dragonshot.sim.capture.Frame;
import com.dragonshot.sim.capture.Rect;
import com.dragonshot.sim.config.AppConfig;
import com.dragonshot.sim.geometry.CoordTransform;
import com.dragonshot.sim.geometry.Vec2;
import com.dragonshot.sim.physics.Ballistics;
import com.dragonshot.sim.state.SessionState;
import com.dragonshot.sim.testutil.TestImages;
import java.util.List;
import org.junit.jupiter.api.Test;

class GamePipelineIntegrationTest {

    private static final int W = 300;
    private static final int H = 200;
    private static final Rect RECT = new Rect(0, 0, W, H);

    @Test
    void cameraShiftKeepsPointsAlignedAndSimulationUsesLatestAngle() throws Exception {
        AppConfig config = new AppConfig();
        config.setPower(50);
        config.setPowerScale(10);
        config.setGravity(500);
        config.setAngleRadiusMin(15);
        config.setAngleRadiusMax(75);
        config.setMinAngleConfidence(0.05);
        SessionState state = new SessionState();
        CoordTransform transform = new CoordTransform();
        GamePipeline pipeline = new GamePipeline(config, state, transform);

        Frame frame = syntheticFrame();
        Vec2 pivotScene = new Vec2(150.0 / W, 140.0 / H);
        Vec2 targetScene = new Vec2(250.0 / W, 100.0 / H);
        state.setOrigin(pivotScene);
        state.setTarget(targetScene);

        pipeline.onCapture(new CaptureEvent(frame, RECT));
        assertTrue(state.isAngleValid(), "initial angle: " + state.snapshot().angleReason());
        assertTrue(Math.abs(state.screenAngleDeg() - 30.0) < 4.0,
                "detected " + state.screenAngleDeg());

        assertTrue(pipeline.setAnchor(frame, new Rect(40, 40, 30, 30)));
        assertTrue(state.isSyncValid());

        Frame shifted = TestImages.translate(frame, 7, -4, 25);
        pipeline.onCapture(new CaptureEvent(shifted, RECT));

        assertTrue(state.isSyncValid(), "sync should survive a 7px pan");
        assertEquals(157.0 / W, state.originScene().x(), 0.002);
        assertEquals(136.0 / H, state.originScene().y(), 0.002);
        assertTrue(state.isAngleValid(), "angle after pan: " + state.snapshot().angleReason());
        assertTrue(Math.abs(state.screenAngleDeg() - 30.0) < 4.0,
                "angle after pan " + state.screenAngleDeg());

        SimulationResult result = pipeline.simulate();
        assertNotNull(result);
        List<Vec2> points = state.snapshot().trajectoryScene();
        assertTrue(points.size() > 2);
        Vec2 first = points.get(0);
        Vec2 second = points.get(1);
        assertEquals(157.0 / W, first.x(), 0.002);
        assertEquals(136.0 / H, first.y(), 0.002);
        assertTrue(second.x() > first.x(), "trajectory must go right for 30 deg");
        assertTrue(second.y() < first.y(), "trajectory must go up for 30 deg");
        assertEquals(0.0, Ballistics.AIR_DENSITY, 1e-9);
        assertFalse(state.snapshot().trajectoryStale());
        assertTrue(Double.isFinite(state.snapshot().suggestedPower()));
    }

    @Test
    void simulationRefusesWhenAngleIsUnavailable() {
        AppConfig config = new AppConfig();
        SessionState state = new SessionState();
        CoordTransform transform = new CoordTransform();
        GamePipeline pipeline = new GamePipeline(config, state, transform);
        state.setOrigin(new Vec2(0.5, 0.5));
        try {
            pipeline.simulate();
            org.junit.jupiter.api.Assertions.fail("Simulation should have failed");
        } catch (SimulationException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("angulo"));
        }
    }

    private static Frame syntheticFrame() {
        byte[] data = new byte[W * H];
        TestImages.fillRect(data, W, 0, 0, W, H, 25);
        Frame base = TestImages.fromData(W, H, data);
        Frame texture = TestImages.randomTexture(30, 30, 123);
        Frame withAnchor = TestImages.paste(base, texture, 40, 40);

        byte[] scene = withAnchor.gray().clone();
        TestImages.drawCircle(scene, W, H, 150, 140, 12, 110);
        double rad = Math.toRadians(30);
        TestImages.drawLine(scene, W, H, 150, 140,
                150 + Math.cos(rad) * 60,
                140 - Math.sin(rad) * 60,
                4.0, 230);
        TestImages.addNoise(scene, 77, 4);
        return TestImages.fromData(W, H, scene);
    }
}
