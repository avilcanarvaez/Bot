package com.dragonshot.sim.capture;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Enumeracion y seguimiento de la ventana objetivo.
 *
 * La ventana se localiza por HWND, no por titulo, de modo que el seguimiento
 * sigue siendo valido aunque el titulo cambie (por ejemplo, el titulo de una
 * pestana del navegador).
 */
public final class WindowTracker {

    public static final int WDA_NONE = 0x00000000;
    public static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    private final double dpiScale;

    public WindowTracker(double dpiScale) {
        this.dpiScale = dpiScale;
    }

    public double dpiScale() {
        return dpiScale;
    }

    /** Lista ventanas top-level visibles, no minimizadas y con titulo. */
    public List<WindowInfo> listWindows() {
        List<WindowInfo> result = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            try {
                if (!User32.INSTANCE.IsWindowVisible(hwnd) || WinUserEx.INSTANCE.IsIconic(hwnd)) {
                    return true;
                }
                int length = User32.INSTANCE.GetWindowTextLength(hwnd);
                if (length <= 0) {
                    return true;
                }
                char[] buffer = new char[length + 1];
                User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
                String title = new String(buffer).trim();
                if (title.isEmpty()) {
                    return true;
                }
                String lower = title.toLowerCase(Locale.ROOT);
                if (lower.startsWith("dragonshot")) {
                    return true; // nuestras propias ventanas
                }
                IntByReference pid = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
                result.add(new WindowInfo(hwnd, title, pid.getValue()));
            } catch (Throwable ignored) {
                // Una ventana puede desaparecer mientras se enumera.
            }
            return true;
        }, null);
        result.sort(Comparator.comparing(WindowInfo::toString, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Devuelve el area cliente de la ventana en coordenadas AWT (las mismas
     * que Robot.createScreenCapture y JWindow.setBounds).
     */
    public Rect clientRect(WindowInfo window) {
        if (window == null || window.hwnd() == null) {
            return null;
        }
        WinDef.HWND hwnd = window.hwnd();
        if (WinUserEx.INSTANCE.IsIconic(hwnd)) {
            return null;
        }
        WinDef.RECT rect = new WinDef.RECT();
        if (!User32.INSTANCE.GetClientRect(hwnd, rect)) {
            return null;
        }
        WinDef.POINT origin = new WinDef.POINT(0, 0);
        if (!WinUserEx.INSTANCE.ClientToScreen(hwnd, origin)) {
            return null;
        }
        int w = rect.right - rect.left;
        int h = rect.bottom - rect.top;
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new Rect(
                (int) Math.round(origin.x * dpiScale),
                (int) Math.round(origin.y * dpiScale),
                Math.max(1, (int) Math.round(w * dpiScale)),
                Math.max(1, (int) Math.round(h * dpiScale)));
    }

    /** Rectangulo completo (incluye borde y barra de titulo), solo para diagnostico. */
    public Rect windowRect(WindowInfo window) {
        if (window == null || window.hwnd() == null) {
            return null;
        }
        WinDef.RECT rect = new WinDef.RECT();
        if (!User32.INSTANCE.GetWindowRect(window.hwnd(), rect)) {
            return null;
        }
        int w = rect.right - rect.left;
        int h = rect.bottom - rect.top;
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new Rect(
                (int) Math.round(rect.left * dpiScale),
                (int) Math.round(rect.top * dpiScale),
                Math.max(1, (int) Math.round(w * dpiScale)),
                Math.max(1, (int) Math.round(h * dpiScale)));
    }

    public static WinDef.HWND findWindowByTitle(String title) {
        return User32.INSTANCE.FindWindow(null, title);
    }

    /**
     * Activa/desactiva el paso de clicks a traves del overlay.
     * Mantiene WS_EX_LAYERED para no perder la transparencia.
     */
    public boolean setClickThrough(String exactTitle, boolean clickThrough) {
        WinDef.HWND hwnd = findWindowByTitle(exactTitle);
        if (hwnd == null) {
            return false;
        }
        int ex = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        final int wsExNoActivate = 0x08000000;
        int updated = ex | WinUser.WS_EX_LAYERED | wsExNoActivate;
        if (clickThrough) {
            updated |= WinUser.WS_EX_TRANSPARENT;
        } else {
            updated &= ~WinUser.WS_EX_TRANSPARENT;
        }
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, updated);
        return true;
    }

    /** Excluye la ventana de Robot.createScreenCapture (Windows 10 2004+). */
    public boolean excludeFromCapture(String exactTitle) {
        WinDef.HWND hwnd = findWindowByTitle(exactTitle);
        if (hwnd == null) {
            return false;
        }
        try {
            return WinUserEx.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean includeInCapture(String exactTitle) {
        WinDef.HWND hwnd = findWindowByTitle(exactTitle);
        if (hwnd == null) {
            return false;
        }
        try {
            return WinUserEx.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_NONE);
        } catch (Throwable t) {
            return false;
        }
    }
}
