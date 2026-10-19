package com.dragonshot.sim.capture;

import com.sun.jna.platform.win32.WinDef;

/** Ventana visible del sistema operativo. */
public record WindowInfo(WinDef.HWND hwnd, String title, int processId) {
    @Override
    public String toString() {
        return title + "  (PID " + processId + ")";
    }
}
