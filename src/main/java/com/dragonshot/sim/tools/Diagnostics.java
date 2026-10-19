package com.dragonshot.sim.tools;

import com.dragonshot.sim.capture.DpiScale;
import com.dragonshot.sim.capture.Rect;
import com.dragonshot.sim.capture.WindowInfo;
import com.dragonshot.sim.capture.WindowTracker;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Diagnostico de entorno Windows/AWT. No forma parte del flujo normal.
 *
 * Uso:
 *   java -cp "out\classes;lib\*" com.dragonshot.sim.tools.Diagnostics
 *
 * Comprueba:
 *  - coordenadas AWT frente a metricas Win32 (DPI),
 *  - enumeracion de ventanas,
 *  - area cliente de una ventana de prueba,
 *  - que SetWindowDisplayAffinity excluye de verdad la ventana de Robot.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("Headless: " + GraphicsEnvironment.isHeadless());
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Sin escritorio: no se pueden probar overlay/captura.");
            return;
        }
        double dpiScale = DpiScale.detect();
        System.out.println("AWT screen: " + Toolkit.getDefaultToolkit().getScreenSize());
        System.out.println("Win32 SM_CXSCREEN: " + User32.INSTANCE.GetSystemMetrics(0));
        System.out.println("Win32 SM_CYSCREEN: " + User32.INSTANCE.GetSystemMetrics(1));
        System.out.println("DPI scale aplicada: " + dpiScale);

        WindowTracker tracker = new WindowTracker(dpiScale);
        List<WindowInfo> windows = tracker.listWindows();
        System.out.println("Ventanas top-level detectadas: " + windows.size());
        windows.stream().limit(12).forEach(window -> System.out.println("  " + window));

        String title = "DS Diagnostic Window";
        JFrame frame = new JFrame(title);
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setSize(300, 200);
        frame.setLocation(120, 120);
        JPanel panel = new JPanel();
        panel.setBackground(new Color(0, 255, 0));
        frame.setContentPane(panel);
        SwingUtilities.invokeAndWait(() -> {
            frame.setVisible(true);
            frame.toFront();
        });
        Thread.sleep(600);

        WinDef.HWND hwnd = WindowTracker.findWindowByTitle(title);
        System.out.println("FindWindow('" + title + "') = " + hwnd);
        if (hwnd == null) {
            System.out.println("FALLO: no se pudo localizar la ventana de prueba por titulo.");
            System.exit(2);
        }
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        WindowInfo info = new WindowInfo(hwnd, title, pid.getValue());
        Rect client = tracker.clientRect(info);
        System.out.println("Area cliente Win32->AWT: " + client);

        Robot robot = new Robot();
        Rectangle captureRect = client == null ? new Rectangle(120, 120, 300, 200)
                : new Rectangle(client.x(), client.y(), client.width(), client.height());
        int greenBefore = countGreen(robot.createScreenCapture(captureRect));
        System.out.println("Pixeles verdes antes de excluir: " + greenBefore
                + " / " + (captureRect.width * captureRect.height));

        boolean excluded = tracker.excludeFromCapture(title);
        System.out.println("SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE) = " + excluded);
        Thread.sleep(400);
        int greenAfter = countGreen(robot.createScreenCapture(captureRect));
        System.out.println("Pixeles verdes despues de excluir: " + greenAfter
                + " / " + (captureRect.width * captureRect.height));
        System.out.println(greenBefore > 1000 && greenAfter < greenBefore / 10
                ? "OK: la ventana se excluye de Robot.createScreenCapture."
                : "AVISO: no se pudo confirmar la exclusion visual.");

        boolean clickThrough = tracker.setClickThrough(title, true);
        int style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        boolean transparent = (style & WinUser.WS_EX_TRANSPARENT) != 0;
        System.out.println("setClickThrough = " + clickThrough
                + ", WS_EX_TRANSPARENT = " + transparent);

        tracker.includeInCapture(title);
        SwingUtilities.invokeAndWait(frame::dispose);
        System.out.println("Diagnostico terminado.");
        System.exit(0);
    }

    private static int countGreen(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (g > 200 && r < 80 && b < 80) {
                    count++;
                }
            }
        }
        return count;
    }
}
