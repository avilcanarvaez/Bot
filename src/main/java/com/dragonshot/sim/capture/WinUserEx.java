package com.dragonshot.sim.capture;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Funciones de user32 que no estan expuestas por la interfaz User32 de JNA.
 * Se cargan con el mismo mapeo Unicode que usa JNA para el resto de la API.
 */
public interface WinUserEx extends StdCallLibrary {

    WinUserEx INSTANCE = Native.load("user32", WinUserEx.class, W32APIOptions.DEFAULT_OPTIONS);

    /** Convierte coordenadas cliente a coordenadas de pantalla. */
    boolean ClientToScreen(WinDef.HWND hWnd, WinDef.POINT point);

    /** Indica si la ventana esta minimizada. */
    boolean IsIconic(WinDef.HWND hWnd);

    /**
     * Excluye (o incluye) una ventana de las capturas de pantalla.
     * WDA_EXCLUDEFROMCAPTURE = 0x00000011 (Windows 10 2004+).
     * Devuelve false si el sistema no soporta la operacion.
     */
    boolean SetWindowDisplayAffinity(WinDef.HWND hWnd, int affinity);

    /** Fallback para sistemas sin SetProcessDpiAwarenessContext. */
    boolean SetProcessDPIAware();

    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = (HANDLE)-4. */
    boolean SetProcessDpiAwarenessContext(Pointer value);
}
