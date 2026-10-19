package com.dragonshot.sim.capture;

import com.sun.jna.platform.win32.User32;
import java.awt.Toolkit;

/**
 * Windows y AWT no siempre comparten la misma escala cuando hay DPI scaling.
 * GetSystemMetrics devuelve pixeles fisicos si el proceso es DPI-aware, mientras
 * Robot/Swing trabajan en el espacio logico de AWT. Calculamos una unica escala
 * y la aplicamos a todos los rectangulos Win32 antes de usarlos en AWT.
 *
 * De este modo captura, overlay, clicks y puntos comparten exactamente el mismo
 * sistema de coordenadas.
 */
public final class DpiScale {

    private DpiScale() {
    }

    public static double detect() {
        try {
            int winWidth = User32.INSTANCE.GetSystemMetrics(0); // SM_CXSCREEN
            int awtWidth = Toolkit.getDefaultToolkit().getScreenSize().width;
            if (winWidth <= 0 || awtWidth <= 0) {
                return 1.0;
            }
            double scale = (double) awtWidth / (double) winWidth;
            // Valores fuera de rango indican una API no disponible o un entorno raro.
            if (!Double.isFinite(scale) || scale < 0.5 || scale > 3.0) {
                return 1.0;
            }
            return scale;
        } catch (Throwable t) {
            return 1.0;
        }
    }
}
