package com.dragonshot.sim.tools;

import com.dragonshot.sim.capture.DpiScale;
import com.dragonshot.sim.capture.Rect;
import com.dragonshot.sim.capture.WindowTracker;
import com.dragonshot.sim.geometry.Vec2;
import com.dragonshot.sim.state.SessionState;
import com.dragonshot.sim.ui.OverlayWindow;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Prueba manual del overlay: visibilidad, exclusion de captura, click-through
 * y recepcion de un clic para marcar el ORIGEN.
 *
 * Uso:
 *   java -cp "out\classes;lib\*" com.dragonshot.sim.tools.OverlaySmoke
 */
public final class OverlaySmoke {

    private OverlaySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Headless: no se puede probar el overlay.");
            return;
        }
        double dpi = DpiScale.detect();
        WindowTracker tracker = new WindowTracker(dpi);
        SessionState state = new SessionState();
        OverlayWindow overlay = new OverlayWindow(state, tracker, () -> null, System.out::println);

        AtomicReference<Vec2> picked = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        overlay.setListener(new OverlayWindow.Listener() {
            @Override
            public void onScenePoint(OverlayWindow.InteractionMode mode, Vec2 scenePoint) {
                picked.set(scenePoint);
                latch.countDown();
            }

            @Override
            public void onAnchorSelected(Rect localRect) {
            }
        });

        JFrame backdrop = new JFrame("DS Overlay Backdrop");
        backdrop.setUndecorated(true);
        backdrop.setSize(300, 200);
        backdrop.setLocation(120, 120);
        JPanel panel = new JPanel();
        panel.setBackground(new Color(0, 180, 0));
        backdrop.setContentPane(panel);
        SwingUtilities.invokeAndWait(() -> backdrop.setVisible(true));
        Thread.sleep(300);

        SwingUtilities.invokeAndWait(() ->
                overlay.showOverlay(new Rect(120, 120, 300, 200)));
        Thread.sleep(500);
        WinDef.HWND hwnd = WindowTracker.findWindowByTitle(OverlayWindow.TITLE);
        System.out.println("Overlay FindWindow: " + hwnd);
        System.out.println("Overlay excluido de captura: " + overlay.isExcludedFromCapture());

        SwingUtilities.invokeAndWait(() ->
                overlay.beginInteraction(OverlayWindow.InteractionMode.ORIGIN));
        Thread.sleep(300);

        Robot robot = new Robot();
        robot.mouseMove(170, 170);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        boolean received = latch.await(2, TimeUnit.SECONDS);
        System.out.println("Clic recibido: " + received);
        Vec2 point = picked.get();
        System.out.println("Punto de escena: " + point
                + " (esperado ~0.167, 0.250)");

        int style = hwnd == null ? 0 : User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        boolean transparent = (style & WinUser.WS_EX_TRANSPARENT) != 0;
        System.out.println("Click-through restaurado tras el clic: " + transparent);

        SwingUtilities.invokeAndWait(() -> {
            overlay.hideOverlay();
            overlay.dispose();
            backdrop.dispose();
        });
        boolean ok = received
                && point != null
                && Math.abs(point.x() - 50.0 / 300.0) < 0.01
                && Math.abs(point.y() - 50.0 / 200.0) < 0.01
                && transparent;
        System.out.println(ok ? "OK: overlay y marcado funcionan." : "FALLO: revisar overlay.");
        System.exit(ok ? 0 : 3);
    }
}
