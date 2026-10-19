package com.dragonshot.sim.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonshot.sim.capture.Rect;
import org.junit.jupiter.api.Test;

class CoordTransformTest {

    private static final double EPS = 1e-9;

    @Test
    void sceneToScreenAndBack() {
        CoordTransform transform = new CoordTransform();
        transform.setClientRect(new Rect(100, 50, 800, 600));
        Vec2 screen = transform.sceneToScreen(new Vec2(0.5, 0.5));
        assertEquals(500, screen.x(), EPS);
        assertEquals(350, screen.y(), EPS);
        Vec2 scene = transform.screenToScene(500, 350);
        assertEquals(0.5, scene.x(), EPS);
        assertEquals(0.5, scene.y(), EPS);
    }

    @Test
    void movingWindowDoesNotChangeScenePoint() {
        CoordTransform transform = new CoordTransform();
        transform.setClientRect(new Rect(100, 50, 800, 600));
        Vec2 before = transform.sceneToScreen(new Vec2(0.25, 0.75));
        transform.setClientRect(new Rect(220, 140, 800, 600));
        Vec2 after = transform.sceneToScreen(new Vec2(0.25, 0.75));
        assertEquals(120, after.x() - before.x(), EPS);
        assertEquals(90, after.y() - before.y(), EPS);
    }

    @Test
    void resizingWindowRescalesScenePoint() {
        CoordTransform transform = new CoordTransform();
        transform.setClientRect(new Rect(0, 0, 800, 600));
        transform.setClientRect(new Rect(0, 0, 1600, 1200));
        Vec2 screen = transform.sceneToScreen(new Vec2(0.5, 0.25));
        assertEquals(800, screen.x(), EPS);
        assertEquals(300, screen.y(), EPS);
    }

    @Test
    void anchorDeltaIsNormalizedPerAxis() {
        CoordTransform transform = new CoordTransform();
        transform.setClientRect(new Rect(0, 0, 800, 600));
        Vec2 delta = transform.screenDeltaToScene(80, -60);
        assertEquals(0.1, delta.x(), EPS);
        assertEquals(-0.1, delta.y(), EPS);
    }

    @Test
    void localAndSceneRectRoundTrip() {
        CoordTransform transform = new CoordTransform();
        transform.setClientRect(new Rect(10, 20, 1000, 500));
        SceneRect scene = transform.localRectToScene(100, 50, 200, 100);
        assertEquals(0.1, scene.x(), EPS);
        assertEquals(0.1, scene.y(), EPS);
        assertEquals(0.2, scene.width(), EPS);
        assertEquals(0.2, scene.height(), EPS);
        Rect local = transform.sceneRectToLocal(scene);
        assertEquals(100, local.x());
        assertEquals(50, local.y());
        assertEquals(200, local.width());
        assertEquals(100, local.height());
    }

    @Test
    void sceneRectScaleAroundPivot() {
        SceneRect rect = new SceneRect(0.2, 0.2, 0.1, 0.1);
        SceneRect scaled = rect.scaleAround(0.25, 0.25, 2.0);
        assertEquals(0.15, scaled.x(), EPS);
        assertEquals(0.15, scaled.y(), EPS);
        assertEquals(0.2, scaled.width(), EPS);
        assertTrue(Math.abs(scaled.centerX() - 0.25) < EPS);
    }
}
