package com.dragonshot.sim.tools;

import com.dragonshot.sim.capture.CaptureEvent;
import com.dragonshot.sim.capture.Frame;
import com.dragonshot.sim.capture.Rect;
import com.dragonshot.sim.config.AppConfig;
import com.dragonshot.sim.geometry.CoordTransform;
import com.dragonshot.sim.geometry.Vec2;
import com.dragonshot.sim.physics.Ballistics;
import com.dragonshot.sim.pipeline.GamePipeline;
import com.dragonshot.sim.state.SessionState;
import com.dragonshot.sim.testutil.TestImages;
import com.dragonshot.sim.vision.AngleDetector;
import com.dragonshot.sim.vision.AngleResult;
import java.util.Arrays;

/**
 * Benchmark sintetico del coste por frame. No forma parte de la aplicacion.
 *
 * Uso (classpath de pruebas):
 *   java -cp "out\classes;out\test-classes;lib\*" com.dragonshot.sim.tools.PerfSmoke
 */
public final class PerfSmoke {

    private static final int W = 1280;
    private static final int H = 720;
    private static final Rect RECT = new Rect(0, 0, W, H);
    private static final Vec2 PIVOT = new Vec2(900, 500);

    private PerfSmoke() {
    }

    public static void main(String[] args) {
        Frame base = syntheticFrame();
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

        state.setOrigin(new Vec2(PIVOT.x() / W, PIVOT.y() / H));
        state.setTarget(new Vec2(1100.0 / W, 300.0 / H));
        pipeline.onCapture(new CaptureEvent(base, RECT));
        pipeline.setAnchor(base, new Rect(400, 300, 40, 40));

        // Veinte frames precalculados con desplazamientos de camara variables.
        Frame[] frames = new Frame[20];
        for (int i = 0; i < frames.length; i++) {
            int dx = (i % 7) * 2 - 6;
            int dy = ((i / 7) % 5) * 2 - 4;
            frames[i] = TestImages.translate(base, dx, dy, 25);
        }

        for (int i = 0; i < 30; i++) {
            pipeline.onCapture(new CaptureEvent(frames[i % frames.length], RECT));
        }

        int iterations = 150;
        long[] times = new long[iterations];
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            pipeline.onCapture(new CaptureEvent(frames[i % frames.length], RECT));
            times[i] = System.nanoTime() - t0;
        }
        long total = System.nanoTime() - start;
        Arrays.sort(times);
        System.out.printf("GamePipeline.onCapture: media %.2f ms, p95 %.2f ms, max %.2f ms%n",
                total / 1e6 / iterations, times[(int) (iterations * 0.95)] / 1e6,
                times[iterations - 1] / 1e6);
        System.out.println("  sync=" + state.isSyncValid()
                + ", angleValid=" + state.isAngleValid()
                + ", angle=" + state.screenAngleDeg()
                + ", origin=" + state.originScene());

        // Detector de angulo aislado.
        long angleTotal = 0;
        int angleIterations = 200;
        for (int i = 0; i < angleIterations; i++) {
            long t0 = System.nanoTime();
            AngleResult result = AngleDetector.detect(base, PIVOT, 15, 75, 0.05);
            angleTotal += System.nanoTime() - t0;
            if (!result.valid()) {
                System.out.println("  aviso: angulo invalido en benchmark: " + result.reason());
            }
        }
        System.out.printf("AngleDetector.detect: %.2f ms/frame%n",
                angleTotal / 1e6 / angleIterations);

        // Balistica aislada.
        long physicsTotal = 0;
        int physicsIterations = 1000;
        for (int i = 0; i < physicsIterations; i++) {
            long t0 = System.nanoTime();
            Ballistics.simulate(new Vec2(900, 500), 35, 500, 500, W, H);
            physicsTotal += System.nanoTime() - t0;
        }
        System.out.printf("Ballistics.simulate: %.3f ms/llamada%n",
                physicsTotal / 1e6 / physicsIterations);
    }

    private static Frame syntheticFrame() {
        byte[] data = new byte[W * H];
        TestImages.fillRect(data, W, 0, 0, W, H, 25);
        Frame base = TestImages.fromData(W, H, data);
        Frame texture = TestImages.randomTexture(40, 40, 321);
        Frame withAnchor = TestImages.paste(base, texture, 400, 300);

        byte[] scene = withAnchor.gray().clone();
        TestImages.drawCircle(scene, W, H, PIVOT.x(), PIVOT.y(), 13, 110);
        double rad = Math.toRadians(35);
        TestImages.drawLine(scene, W, H, PIVOT.x(), PIVOT.y(),
                PIVOT.x() + Math.cos(rad) * 65,
                PIVOT.y() - Math.sin(rad) * 65,
                4.0, 230);
        TestImages.addNoise(scene, 991, 3);
        return TestImages.fromData(W, H, scene);
    }
}
