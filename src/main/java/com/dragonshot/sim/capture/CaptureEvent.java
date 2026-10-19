package com.dragonshot.sim.capture;

/** Fotograma capturado junto con el rectangulo de pantalla que ocupaba. */
public record CaptureEvent(Frame frame, Rect clientRect) {
}
