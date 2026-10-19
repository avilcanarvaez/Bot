package com.dragonshot.sim.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Configuracion persistente y calibrable de la aplicacion. */
public final class AppConfig {

    public static final String FILE_NAME = "dragonshot.properties";

    private double gravity = 500.0;
    private double powerScale = 10.0;
    private double angleOffsetDeg = 0.0;
    private boolean clockwiseAngle = false;
    private double trajectoryOffsetDeg = 0.0;
    private int angleRadiusMin = 12;
    private int angleRadiusMax = 80;
    private double minAngleConfidence = 0.15;
    private int anchorSearchRadius = 70;
    private int captureIntervalMs = 90;
    private double power = 50.0;

    public static AppConfig load(Path path) {
        AppConfig config = new AppConfig();
        if (path == null || !Files.isRegularFile(path)) {
            return config;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            return config;
        }
        config.gravity = readDouble(props, "gravity", config.gravity);
        config.powerScale = readDouble(props, "powerScale", config.powerScale);
        config.angleOffsetDeg = readDouble(props, "angleOffsetDeg", config.angleOffsetDeg);
        config.clockwiseAngle = Boolean.parseBoolean(props.getProperty("clockwiseAngle",
                Boolean.toString(config.clockwiseAngle)));
        config.trajectoryOffsetDeg = readDouble(props, "trajectoryOffsetDeg", config.trajectoryOffsetDeg);
        config.angleRadiusMin = (int) readDouble(props, "angleRadiusMin", config.angleRadiusMin);
        config.angleRadiusMax = (int) readDouble(props, "angleRadiusMax", config.angleRadiusMax);
        config.minAngleConfidence = readDouble(props, "minAngleConfidence", config.minAngleConfidence);
        config.anchorSearchRadius = (int) readDouble(props, "anchorSearchRadius",
                config.anchorSearchRadius);
        config.captureIntervalMs = (int) readDouble(props, "captureIntervalMs", config.captureIntervalMs);
        config.power = readDouble(props, "power", config.power);
        config.sanitize();
        return config;
    }

    public void save(Path path) {
        if (path == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty("gravity", Double.toString(gravity));
        props.setProperty("powerScale", Double.toString(powerScale));
        props.setProperty("angleOffsetDeg", Double.toString(angleOffsetDeg));
        props.setProperty("clockwiseAngle", Boolean.toString(clockwiseAngle));
        props.setProperty("trajectoryOffsetDeg", Double.toString(trajectoryOffsetDeg));
        props.setProperty("angleRadiusMin", Integer.toString(angleRadiusMin));
        props.setProperty("angleRadiusMax", Integer.toString(angleRadiusMax));
        props.setProperty("minAngleConfidence", Double.toString(minAngleConfidence));
        props.setProperty("anchorSearchRadius", Integer.toString(anchorSearchRadius));
        props.setProperty("captureIntervalMs", Integer.toString(captureIntervalMs));
        props.setProperty("power", Double.toString(power));
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "DragonShot Simulator - calibracion");
            }
        } catch (IOException ignored) {
            // La aplicacion puede seguir funcionando sin persistir.
        }
    }

    private void sanitize() {
        if (!Double.isFinite(gravity) || gravity <= 0) {
            gravity = 500;
        }
        if (!Double.isFinite(powerScale) || powerScale <= 0) {
            powerScale = 10;
        }
        if (!Double.isFinite(angleOffsetDeg)) {
            angleOffsetDeg = 0;
        }
        if (!Double.isFinite(trajectoryOffsetDeg)) {
            trajectoryOffsetDeg = 0;
        }
        angleRadiusMin = Math.max(8, Math.min(40, angleRadiusMin));
        angleRadiusMax = Math.max(angleRadiusMin + 20, Math.min(200, angleRadiusMax));
        minAngleConfidence = Math.max(0.02, Math.min(0.9, minAngleConfidence));
        anchorSearchRadius = Math.max(24, Math.min(200, anchorSearchRadius));
        captureIntervalMs = Math.max(50, Math.min(500, captureIntervalMs));
        if (!Double.isFinite(power) || power < 0 || power > 100) {
            power = 50;
        }
    }

    private static double readDouble(Properties props, String key, double fallback) {
        String value = props.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double gravity() {
        return gravity;
    }

    public void setGravity(double gravity) {
        this.gravity = gravity;
        sanitize();
    }

    public double powerScale() {
        return powerScale;
    }

    public void setPowerScale(double powerScale) {
        this.powerScale = powerScale;
        sanitize();
    }

    public double angleOffsetDeg() {
        return angleOffsetDeg;
    }

    public void setAngleOffsetDeg(double angleOffsetDeg) {
        this.angleOffsetDeg = angleOffsetDeg;
        sanitize();
    }

    public boolean clockwiseAngle() {
        return clockwiseAngle;
    }

    public void setClockwiseAngle(boolean clockwiseAngle) {
        this.clockwiseAngle = clockwiseAngle;
    }

    public double trajectoryOffsetDeg() {
        return trajectoryOffsetDeg;
    }

    public void setTrajectoryOffsetDeg(double trajectoryOffsetDeg) {
        this.trajectoryOffsetDeg = trajectoryOffsetDeg;
        sanitize();
    }

    public int angleRadiusMin() {
        return angleRadiusMin;
    }

    public void setAngleRadiusMin(int angleRadiusMin) {
        this.angleRadiusMin = angleRadiusMin;
        sanitize();
    }

    public int angleRadiusMax() {
        return angleRadiusMax;
    }

    public void setAngleRadiusMax(int angleRadiusMax) {
        this.angleRadiusMax = angleRadiusMax;
        sanitize();
    }

    public double minAngleConfidence() {
        return minAngleConfidence;
    }

    public void setMinAngleConfidence(double minAngleConfidence) {
        this.minAngleConfidence = minAngleConfidence;
        sanitize();
    }

    public int anchorSearchRadius() {
        return anchorSearchRadius;
    }

    public void setAnchorSearchRadius(int anchorSearchRadius) {
        this.anchorSearchRadius = anchorSearchRadius;
        sanitize();
    }

    public int captureIntervalMs() {
        return captureIntervalMs;
    }

    public void setCaptureIntervalMs(int captureIntervalMs) {
        this.captureIntervalMs = captureIntervalMs;
        sanitize();
    }

    public double power() {
        return power;
    }

    public void setPower(double power) {
        this.power = power;
        sanitize();
    }
}
