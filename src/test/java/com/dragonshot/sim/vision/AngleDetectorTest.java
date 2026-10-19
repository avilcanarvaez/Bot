package com.dragonshot.sim.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonshot.sim.capture.Frame;
import com.dragonshot.sim.geometry.Vec2;
import com.dragonshot.sim.testutil.TestImages;
import org.junit.jupiter.api.Test;

class AngleDetectorTest {

    private static final Vec2 PIVOT = new Vec2(120, 120);

    @Test
    void detectsBarrelInMultipleDirections() {
        for (double angle : new double[]{0, 30, 90, 135, 200, 300}) {
            Frame frame = syntheticBarrel(angle);
            AngleResult result = AngleDetector.detect(frame, PIVOT, 15, 75, 0.05);
            assertTrue(result.valid(), "angle " + angle + ": " + result.reason());
            assertTrue(result.confidence() > 0.15,
                    "confidence too low at " + angle + ": " + result.confidence());
            double error = angularDistance(result.screenAngleDeg(), angle);
            assertTrue(error < 4.0,
                    "expected " + angle + " but got " + result.screenAngleDeg()
                    + " (error " + error + ")");
        }
    }

    @Test
    void doesNotReturnOppositeDirection() {
        AngleResult result = AngleDetector.detect(syntheticBarrel(200), PIVOT, 15, 75, 0.05);
        assertTrue(result.valid());
        assertTrue(angularDistance(result.screenAngleDeg(), 200) < 4);
        assertTrue(angularDistance(result.screenAngleDeg(), 20) > 90);
    }

    @Test
    void blankImageIsUnavailable() {
        Frame blank = TestImages.blank(240, 240, 100);
        AngleResult result = AngleDetector.detect(blank, PIVOT, 15, 75, 0.05);
        assertFalse(result.valid());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void pivotOutsideFrameIsUnavailable() {
        AngleResult result = AngleDetector.detect(
                TestImages.blank(100, 100, 50), new Vec2(150, 50), 10, 40, 0.1);
        assertFalse(result.valid());
    }

    @Test
    void trackerSmoothsAndDeclaresUnavailableAfterRepeatedFailures() {
        AngleTracker tracker = new AngleTracker();
        assertTrue(tracker.accept(valid(30.0)).available());
        assertTrue(tracker.accept(valid(31.5)).available());
        AngleTracker.Update smoothed = tracker.accept(valid(30.5));
        assertTrue(smoothed.available());
        assertEquals(30.5, smoothed.angleDeg(), 0.6);

        AngleTracker.Update firstFailure = tracker.accept(
                AngleResult.invalid(PIVOT, "sin bordes"));
        assertTrue(firstFailure.available()); // histeresis de un frame
        AngleTracker.Update secondFailure = tracker.accept(
                AngleResult.invalid(PIVOT, "sin bordes"));
        assertFalse(secondFailure.available());
    }

    @Test
    void angleNormalizationWrapsCorrectly() {
        assertEquals(350, AngleDetector.normalize360(-10), 1e-9);
        assertEquals(10, AngleDetector.normalize360(370), 1e-9);
    }

    private static AngleResult valid(double angle) {
        return new AngleResult(true, angle, 0.8, 100, PIVOT,
                new Vec2(PIVOT.x() + 50, PIVOT.y()), 0, "");
    }

    private static Frame syntheticBarrel(double angle) {
        int size = 240;
        byte[] data = new byte[size * size];
        TestImages.fillRect(data, size, 0, 0, size, size, 25);
        TestImages.drawCircle(data, size, size, PIVOT.x(), PIVOT.y(), 14, 110);
        double rad = Math.toRadians(angle);
        TestImages.drawLine(data, size, size,
                PIVOT.x(), PIVOT.y(),
                PIVOT.x() + Math.cos(rad) * 70,
                PIVOT.y() - Math.sin(rad) * 70,
                4.0, 230);
        TestImages.addNoise(data, (int) (angle * 100) + 7, 4);
        return TestImages.fromData(size, size, data);
    }

    private static double angularDistance(double a, double b) {
        double delta = Math.abs(a - b) % 360.0;
        return delta > 180.0 ? 360.0 - delta : delta;
    }
}
