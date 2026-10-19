package com.dragonshot.sim.capture;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captura periodica del area cliente de una ventana con java.awt.Robot.
 *
 * El overlay se excluye de la captura con SetWindowDisplayAffinity (ver
 * MainFrame/OverlayWindow), por lo que Robot puede capturar la ventana sin
 * contaminar los frames con nuestras lineas.
 */
public final class CaptureService {

    public interface Listener {
        void onCapture(CaptureEvent event);

        void onCaptureError(String message);
    }

    private final WindowTracker windowTracker;
    private volatile int intervalMs;
    private final Object robotLock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<CaptureEvent> latest = new AtomicReference<>();
    private Thread thread;
    private Robot robot;
    private volatile String lastError = "";

    public CaptureService(WindowTracker windowTracker, int intervalMs) {
        this.windowTracker = windowTracker;
        this.intervalMs = Math.max(33, intervalMs);
    }

    public CaptureEvent latest() {
        return latest.get();
    }

    public void setIntervalMs(int intervalMs) {
        this.intervalMs = Math.max(33, intervalMs);
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized void start(WindowInfo window, Listener listener) {
        if (running.get()) {
            stop();
        }
        try {
            synchronized (robotLock) {
                if (robot == null) {
                    robot = new Robot();
                }
            }
        } catch (Exception e) {
            listener.onCaptureError("No se pudo inicializar java.awt.Robot: " + e.getMessage());
            return;
        }
        running.set(true);
        lastError = "";
        thread = new Thread(() -> loop(window, listener), "dragonshot-capture");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running.set(false);
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Captura puntual (por ejemplo para congelar el template del ancla).
     * Devuelve null si la ventana no es capturable en este momento.
     */
    public CaptureEvent captureOnce(WindowInfo window) {
        Rect rect = windowTracker.clientRect(window);
        if (rect == null) {
            return null;
        }
        Rectangle screen = virtualScreenBounds();
        if (!screen.contains(rect.x(), rect.y(), rect.width(), rect.height())) {
            return null;
        }
        try {
            synchronized (robotLock) {
                if (robot == null) {
                    robot = new Robot();
                }
                BufferedImage image = robot.createScreenCapture(
                        new Rectangle(rect.x(), rect.y(), rect.width(), rect.height()));
                Frame frame = Frame.fromBufferedImage(image);
                Rect actual = new Rect(rect.x(), rect.y(), frame.width(), frame.height());
                CaptureEvent event = new CaptureEvent(frame, actual);
                latest.set(event);
                return event;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void loop(WindowInfo window, Listener listener) {
        while (running.get()) {
            long started = System.nanoTime();
            try {
                Rect rect = windowTracker.clientRect(window);
                if (rect == null) {
                    publishError(listener, "Ventana minimizada, cerrada o sin area cliente.");
                } else {
                    Rectangle screen = virtualScreenBounds();
                    if (!screen.contains(rect.x(), rect.y(), rect.width(), rect.height())) {
                        publishError(listener, "La ventana no esta completamente visible en pantalla.");
                    } else {
                        BufferedImage image;
                        synchronized (robotLock) {
                            image = robot.createScreenCapture(
                                    new Rectangle(rect.x(), rect.y(), rect.width(), rect.height()));
                        }
                        Frame frame = Frame.fromBufferedImage(image);
                        Rect actual = new Rect(rect.x(), rect.y(), frame.width(), frame.height());
                        CaptureEvent event = new CaptureEvent(frame, actual);
                        latest.set(event);
                        lastError = "";
                        if (!running.get()) {
                            break;
                        }
                        listener.onCapture(event);
                    }
                }
            } catch (Throwable t) {
                publishError(listener, "Error de captura: " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " - " + t.getMessage()));
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            long sleepMs = Math.max(1L, intervalMs - elapsedMs);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void publishError(Listener listener, String message) {
        if (!running.get()) {
            return;
        }
        if (!message.equals(lastError)) {
            lastError = message;
            listener.onCaptureError(message);
        }
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Rectangle virtualScreenBounds() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle union = null;
        for (GraphicsDevice device : ge.getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            union = union == null ? new Rectangle(bounds) : union.union(bounds);
        }
        return union == null ? new Rectangle(0, 0, 1920, 1080) : union;
    }
}
