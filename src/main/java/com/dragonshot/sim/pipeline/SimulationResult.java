package com.dragonshot.sim.pipeline;

import com.dragonshot.sim.physics.Trajectory;

public record SimulationResult(Trajectory trajectory, double angleDeg, double speedPxPerSecond) {
}
