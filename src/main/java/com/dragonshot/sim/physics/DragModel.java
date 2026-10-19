package com.dragonshot.sim.physics;

import com.dragonshot.sim.geometry.Vec2;

/**
 * Modelo de resistencia del aire. En esta version el unico modelo permitido es
 * NO_AIR (densidad 0). Se deja la interfaz para una futura ampliacion sin
 * introducir efectos implicitos en el calculo actual.
 */
public interface DragModel {

    DragModel NO_AIR = velocity -> Vec2.ZERO;

    /** Aceleracion en pixeles/s^2, en el mismo sistema que la velocidad (Y hacia abajo). */
    Vec2 acceleration(Vec2 velocity);
}
