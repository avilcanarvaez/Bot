package com.dragonshot.sim.geometry;

import com.dragonshot.sim.capture.Rect;

/**
 * Transformaciones entre los tres espacios de coordenadas del programa:
 *
 *  - ESCENA: normalizado [0..1] respecto del area cliente. Es el espacio que
 *    persiste y que se desplaza cuando la camara del juego se mueve.
 *  - LOCAL: pixeles dentro del area cliente (0,0 = esquina superior izquierda).
 *  - PANTALLA: coordenadas AWT absolutas; es lo que usan Robot y el overlay.
 *
 * El eje Y crece hacia abajo en los tres espacios. La fisica convierte a un
 * eje Y "hacia arriba" solo dentro de Ballistics.
 */
public final class CoordTransform {

    private Rect clientRect = new Rect(0, 0, 1, 1);

    public synchronized void setClientRect(Rect rect) {
        if (rect == null || !rect.isValid()) {
            return;
        }
        this.clientRect = rect;
    }

    public synchronized Rect clientRect() {
        return clientRect;
    }

    public synchronized Vec2 sceneToLocal(Vec2 scene) {
        return new Vec2(scene.x() * clientRect.width(), scene.y() * clientRect.height());
    }

    public synchronized Vec2 sceneToScreen(Vec2 scene) {
        return new Vec2(
                clientRect.x() + scene.x() * clientRect.width(),
                clientRect.y() + scene.y() * clientRect.height());
    }

    public synchronized Vec2 screenToScene(double screenX, double screenY) {
        return new Vec2(
                (screenX - clientRect.x()) / Math.max(1, clientRect.width()),
                (screenY - clientRect.y()) / Math.max(1, clientRect.height()));
    }

    public synchronized Vec2 localToScene(double localX, double localY) {
        return new Vec2(
                localX / Math.max(1, clientRect.width()),
                localY / Math.max(1, clientRect.height()));
    }

    public synchronized Vec2 screenDeltaToScene(double dx, double dy) {
        return new Vec2(
                dx / Math.max(1, clientRect.width()),
                dy / Math.max(1, clientRect.height()));
    }

    public synchronized SceneRect localRectToScene(double x, double y, double w, double h) {
        return SceneRect.fromLocal(x, y, w, h, clientRect.width(), clientRect.height());
    }

    public synchronized Rect sceneRectToLocal(SceneRect sceneRect) {
        int x = (int) Math.round(sceneRect.x() * clientRect.width());
        int y = (int) Math.round(sceneRect.y() * clientRect.height());
        int w = Math.max(1, (int) Math.round(sceneRect.width() * clientRect.width()));
        int h = Math.max(1, (int) Math.round(sceneRect.height() * clientRect.height()));
        return new Rect(x, y, w, h);
    }
}
