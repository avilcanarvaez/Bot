package com.dragonshot.sim.ui;

import com.dragonshot.sim.capture.CaptureEvent;
import com.dragonshot.sim.capture.CaptureService;
import com.dragonshot.sim.capture.DpiScale;
import com.dragonshot.sim.capture.Rect;
import com.dragonshot.sim.capture.WindowInfo;
import com.dragonshot.sim.capture.WindowTracker;
import com.dragonshot.sim.config.AppConfig;
import com.dragonshot.sim.geometry.CoordTransform;
import com.dragonshot.sim.geometry.Vec2;
import com.dragonshot.sim.pipeline.GamePipeline;
import com.dragonshot.sim.pipeline.SimulationException;
import com.dragonshot.sim.pipeline.SimulationResult;
import com.dragonshot.sim.state.SessionState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;

/** Panel de control principal. */
public final class MainFrame extends JFrame {

    public static final String TITLE = "DragonShot Control";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path configPath = Path.of(System.getProperty("user.dir"), AppConfig.FILE_NAME);
    private final AppConfig config = AppConfig.load(configPath);
    private final double dpiScale = DpiScale.detect();
    private final WindowTracker windowTracker = new WindowTracker(dpiScale);
    private final SessionState state = new SessionState();
    private final CoordTransform transform = new CoordTransform();
    private final GamePipeline pipeline = new GamePipeline(config, state, transform);
    private final CaptureService captureService = new CaptureService(windowTracker,
            config.captureIntervalMs());
    private final OverlayWindow overlay;

    private final JComboBox<WindowInfo> windowCombo = new JComboBox<>();
    private final JButton refreshButton = new JButton("Actualizar");
    private final JButton startButton = new JButton("Iniciar captura");
    private final JButton stopButton = new JButton("Detener");

    private final JLabel captureLabel = new JLabel("Captura detenida");
    private final JLabel syncLabel = new JLabel("Sync: sin ancla");
    private final JLabel angleValueLabel = new JLabel("—", SwingConstants.LEFT);
    private final JLabel angleDetailLabel = new JLabel("Marca el ORIGEN sobre el eje del canon");
    private final JProgressBar confidenceBar = new JProgressBar(0, 100);
    private final JLabel originLabel = new JLabel("sin marcar");
    private final JLabel targetLabel = new JLabel("sin marcar");
    private final JLabel suggestedPowerLabel = new JLabel("—");
    private final JLabel trajectoryLabel = new JLabel("Sin trayectoria");

    private final JSlider powerSlider = new JSlider(0, 100, 50);
    private final JSpinner powerSpinner = new JSpinner(
            new SpinnerNumberModel(50.0, 0.0, 100.0, 1.0));
    private final JTextField gravityField = new JTextField("500", 7);
    private final JTextField powerScaleField = new JTextField("10", 7);
    private final JTextField trajectoryOffsetField = new JTextField("0", 7);
    private final JTextField radiusMinField = new JTextField("12", 5);
    private final JTextField radiusMaxField = new JTextField("80", 5);
    private final JTextField confidenceField = new JTextField("0.15", 5);
    private final JTextField captureIntervalField = new JTextField("90", 5);
    private final JTextField anchorRadiusField = new JTextField("70", 5);

    private final JTextArea logArea = new JTextArea(8, 50);
    private final JLabel statusBar = new JLabel(" ");

    private WindowInfo selectedWindow;
    private boolean syncingPower;
    private boolean updatingFields;
    private String lastLoggedStatus = "";

    public MainFrame() {
        super(TITLE);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(580, 760));
        setSize(620, 820);
        setLocationRelativeTo(null);

        overlay = new OverlayWindow(state, windowTracker, pipeline::latestAngle, this::log);
        overlay.setListener(new OverlayWindow.Listener() {
            @Override
            public void onScenePoint(OverlayWindow.InteractionMode mode, Vec2 scenePoint) {
                handleScenePoint(mode, scenePoint);
            }

            @Override
            public void onAnchorSelected(Rect localRect) {
                handleAnchorSelected(localRect);
            }
        });

        buildUi();
        wireActions();
        refreshWindows();
        syncFieldsFromConfig();
        updateWindowTitle();
        stopButton.setEnabled(false);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        Timer uiTimer = new Timer(100, e -> refreshUi());
        uiTimer.start();
        log("Aplicacion iniciada. Escala DPI detectada: " + String.format("%.3f", dpiScale));
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        JPanel top = new JPanel(new BorderLayout(6, 4));
        JPanel windowRow = new JPanel(new BorderLayout(6, 0));
        windowRow.add(new JLabel("Ventana del juego:"), BorderLayout.WEST);
        windowCombo.setPreferredSize(new Dimension(360, 28));
        windowRow.add(windowCombo, BorderLayout.CENTER);
        JPanel windowButtons = new JPanel();
        windowButtons.add(refreshButton);
        windowButtons.add(startButton);
        windowButtons.add(stopButton);
        windowRow.add(windowButtons, BorderLayout.EAST);
        top.add(windowRow, BorderLayout.NORTH);

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(captureLabel, BorderLayout.WEST);
        syncLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        statusRow.add(syncLabel, BorderLayout.EAST);
        top.add(statusRow, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(group("Puntos de referencia", buildPointsPanel()));
        content.add(Box.createVerticalStrut(6));
        content.add(group("Angulo del carrito (deteccion automatica)", buildAnglePanel()));
        content.add(Box.createVerticalStrut(6));
        content.add(group("Disparo (AIRE = 0)", buildShotPanel()));
        content.add(Box.createVerticalStrut(6));
        content.add(group("Opciones de vision y captura", buildAdvancedPanel()));
        content.add(Box.createVerticalStrut(6));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Registro"));
        logScroll.setPreferredSize(new Dimension(580, 140));
        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(logScroll, BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        south.add(statusBar, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
    }

    private JPanel buildPointsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("Origen:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(originLabel, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton originButton = new JButton("Marcar origen");
        panel.add(originButton, c);
        originButton.addActionListener(e -> beginInteraction(OverlayWindow.InteractionMode.ORIGIN));

        c.gridx = 0;
        c.gridy = 1;
        panel.add(new JLabel("Destino:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(targetLabel, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton targetButton = new JButton("Marcar destino");
        panel.add(targetButton, c);
        targetButton.addActionListener(e -> beginInteraction(OverlayWindow.InteractionMode.TARGET));

        c.gridx = 0;
        c.gridy = 2;
        panel.add(new JLabel("Ancla de escenario:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel anchorHint = new JLabel("elemento fijo del mundo, no HUD");
        anchorHint.setForeground(new Color(90, 90, 90));
        panel.add(anchorHint, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton anchorButton = new JButton("Marcar ancla");
        panel.add(anchorButton, c);
        anchorButton.addActionListener(e -> beginInteraction(OverlayWindow.InteractionMode.ANCHOR));

        c.gridx = 1;
        c.gridy = 3;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        JButton clearPoints = new JButton("Limpiar puntos");
        panel.add(clearPoints, c);
        clearPoints.addActionListener(e -> {
            state.clearPoints();
            pipeline.resetAngleTracking();
            overlay.endInteraction();
            log("Puntos y ancla eliminados.");
        });
        return panel;
    }

    private JPanel buildAnglePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;

        angleValueLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        panel.add(angleValueLabel, c);

        c.gridy = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        angleDetailLabel.setForeground(new Color(70, 70, 70));
        panel.add(angleDetailLabel, c);

        c.gridy = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        confidenceBar.setStringPainted(true);
        confidenceBar.setString("confianza");
        panel.add(confidenceBar, c);

        c.gridy = 3;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        JButton calibrateAngle = new JButton("Calibrar angulo con valor conocido");
        panel.add(calibrateAngle, c);
        calibrateAngle.addActionListener(e -> calibrateAngleDialog());

        c.gridx = 1;
        c.anchor = GridBagConstraints.EAST;
        JLabel note = new JLabel("La simulacion usa el angulo de pantalla detectado.");
        note.setForeground(new Color(90, 90, 90));
        panel.add(note, c);
        return panel;
    }

    private JPanel buildShotPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("Potencia:"), c);
        c.gridx = 1;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        powerSlider.setPaintTicks(true);
        powerSlider.setPaintLabels(true);
        powerSlider.setMajorTickSpacing(25);
        powerSlider.setMinorTickSpacing(5);
        panel.add(powerSlider, c);
        c.gridx = 4;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        panel.add(powerSpinner, c);

        c.gridx = 0;
        c.gridy = 1;
        panel.add(new JLabel("Gravedad (px/s2):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(gravityField, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Escala potencia:"), c);
        c.gridx = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(powerScaleField, c);
        c.gridx = 4;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("px/s por unidad"), c);

        c.gridx = 0;
        c.gridy = 2;
        panel.add(new JLabel("Correccion trayectoria (deg):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(trajectoryOffsetField, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        JButton apply = new JButton("Aplicar");
        panel.add(apply, c);
        apply.addActionListener(e -> applyShotParams());

        c.gridx = 3;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        JButton calibratePower = new JButton("Calibrar potencia con impacto");
        panel.add(calibratePower, c);
        calibratePower.addActionListener(e -> calibratePowerDialog());

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 1;
        panel.add(new JLabel("AIRE:"), c);
        c.gridx = 1;
        JTextField airField = new JTextField("0", 7);
        airField.setEditable(false);
        airField.setEnabled(false);
        airField.setToolTipText("En esta version AIRE es una constante 0; no hay arrastre.");
        panel.add(airField, c);
        c.gridx = 2;
        c.gridwidth = 3;
        JLabel airNote = new JLabel("constante 0 en todos los calculos de esta version");
        airNote.setForeground(new Color(90, 90, 90));
        panel.add(airNote, c);

        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 1;
        JButton simulate = new JButton("SIMULAR");
        simulate.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        panel.add(simulate, c);
        simulate.addActionListener(e -> simulate());
        c.gridx = 1;
        c.gridwidth = 1;
        JButton clearTrajectory = new JButton("Limpiar trayectoria");
        panel.add(clearTrajectory, c);
        clearTrajectory.addActionListener(e -> {
            state.clearTrajectory();
            log("Trayectoria eliminada.");
        });
        c.gridx = 2;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(trajectoryLabel, c);

        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Potencia sugerida para el destino:"), c);
        c.gridx = 2;
        c.gridwidth = 1;
        panel.add(suggestedPowerLabel, c);
        c.gridx = 3;
        c.gridwidth = 2;
        JButton useSuggested = new JButton("Usar potencia sugerida");
        panel.add(useSuggested, c);
        useSuggested.addActionListener(e -> useSuggestedPower());
        return panel;
    }

    private JPanel buildAdvancedPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("Radio minimo canon:"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(radiusMinField, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Radio maximo:"), c);
        c.gridx = 3;
        panel.add(radiusMaxField, c);
        c.gridx = 4;
        panel.add(new JLabel("Confianza minima:"), c);
        c.gridx = 5;
        panel.add(confidenceField, c);

        c.gridx = 0;
        c.gridy = 1;
        panel.add(new JLabel("Intervalo captura (ms):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(captureIntervalField, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Busqueda ancla (px):"), c);
        c.gridx = 3;
        panel.add(anchorRadiusField, c);
        c.gridx = 4;
        c.gridwidth = 2;
        JButton apply = new JButton("Aplicar opciones");
        panel.add(apply, c);
        apply.addActionListener(e -> applyAdvancedParams());

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 6;
        JLabel note = new JLabel("Los cambios de radio/busqueda se aplican a la siguiente captura o al reiniciar.");
        note.setForeground(new Color(90, 90, 90));
        panel.add(note, c);
        return panel;
    }

    private static JPanel group(String title, JPanel content) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title,
                TitledBorder.LEFT, TitledBorder.TOP));
        wrapper.add(content, BorderLayout.CENTER);
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        return wrapper;
    }

    private void wireActions() {
        refreshButton.addActionListener(e -> refreshWindows());
        startButton.addActionListener(e -> startCapture());
        stopButton.addActionListener(e -> stopCapture());
        windowCombo.addActionListener(e ->
                selectedWindow = (WindowInfo) windowCombo.getSelectedItem());

        powerSlider.addChangeListener(e -> {
            if (syncingPower) {
                return;
            }
            syncingPower = true;
            try {
                double value = powerSlider.getValue();
                powerSpinner.setValue(value);
                config.setPower(value);
                state.setPower(value);
            } finally {
                syncingPower = false;
            }
        });
        powerSpinner.addChangeListener(e -> {
            if (syncingPower) {
                return;
            }
            syncingPower = true;
            try {
                double value = ((Number) powerSpinner.getValue()).doubleValue();
                powerSlider.setValue((int) Math.round(value));
                config.setPower(value);
                state.setPower(value);
            } finally {
                syncingPower = false;
            }
        });
    }

    private void refreshWindows() {
        WindowInfo previous = selectedWindow;
        List<WindowInfo> windows = windowTracker.listWindows();
        windowCombo.removeAllItems();
        for (WindowInfo window : windows) {
            windowCombo.addItem(window);
        }
        if (previous != null) {
            for (int i = 0; i < windowCombo.getItemCount(); i++) {
                WindowInfo item = windowCombo.getItemAt(i);
                if (sameHwnd(item, previous)) {
                    windowCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        selectedWindow = (WindowInfo) windowCombo.getSelectedItem();
        log("Ventanas detectadas: " + windows.size());
    }

    private static boolean sameHwnd(WindowInfo a, WindowInfo b) {
        if (a == null || b == null) {
            return false;
        }
        return a.hwnd().equals(b.hwnd());
    }

    private void startCapture() {
        WindowInfo selected = (WindowInfo) windowCombo.getSelectedItem();
        if (selected == null) {
            warn("Selecciona una ventana del juego.");
            return;
        }
        Rect rect = windowTracker.clientRect(selected);
        if (rect == null) {
            warn("La ventana seleccionada no tiene un area cliente capturable. "
                    + "Restáurala y vuelve a intentarlo.");
            return;
        }
        selectedWindow = selected;
        pipeline.resetViewportTracking();
        pipeline.resetAngleTracking();
        state.markAngleUnavailable("Esperando deteccion del canon");
        state.clearTrajectory();
        state.setSync(false, 0, 0, "Esperando primer frame");
        overlay.showOverlay(rect);
        captureService.start(selected, new CaptureService.Listener() {
            @Override
            public void onCapture(CaptureEvent event) {
                pipeline.onCapture(event);
            }

            @Override
            public void onCaptureError(String message) {
                pipeline.onCaptureError(message);
            }
        });
        if (!captureService.isRunning()) {
            overlay.hideOverlay();
            state.setCaptureStatus(false, 0, "No se pudo iniciar la captura");
            state.markAngleUnavailable("No se pudo iniciar java.awt.Robot");
            log("No se pudo iniciar la captura.");
            updateWindowTitle();
            return;
        }
        state.setCaptureStatus(true, 0, "Iniciando captura");
        log("Captura iniciada sobre: " + selected.title());
        updateWindowTitle();
    }

    private void stopCapture() {
        captureService.stop();
        overlay.hideOverlay();
        state.setCaptureStatus(false, 0, "Captura detenida");
        state.setSync(false, 0, 0, "Captura detenida");
        state.markAngleUnavailable("Captura detenida");
        log("Captura detenida.");
        updateWindowTitle();
    }

    private void beginInteraction(OverlayWindow.InteractionMode mode) {
        if (!captureService.isRunning()) {
            warn("Inicia la captura antes de marcar puntos.");
            return;
        }
        if (!overlay.isOverlayVisible()) {
            CaptureEvent latest = captureService.latest();
            if (latest != null) {
                overlay.showOverlay(latest.clientRect());
            } else {
                warn("Todavia no hay un frame capturado.");
                return;
            }
        }
        overlay.beginInteraction(mode);
        switch (mode) {
            case ORIGIN -> log("Haz clic sobre el eje de giro del canon (ORIGEN).");
            case TARGET -> log("Haz clic sobre el DESTINO.");
            case ANCHOR -> log("Arrastra un rectangulo sobre un elemento fijo del escenario.");
            case IMPACT -> log("Haz clic sobre el punto de impacto del disparo real.");
            default -> {
            }
        }
    }

    private void handleScenePoint(OverlayWindow.InteractionMode mode, Vec2 scenePoint) {
        switch (mode) {
            case ORIGIN -> {
                state.setOrigin(scenePoint);
                pipeline.resetAngleTracking();
                log("Origen marcado.");
                updateWindowTitle();
            }
            case TARGET -> {
                state.setTarget(scenePoint);
                log("Destino marcado.");
            }
            case IMPACT -> calibratePowerAt(scenePoint);
            default -> {
            }
        }
    }

    private void handleAnchorSelected(Rect localRect) {
        if (selectedWindow == null) {
            return;
        }
        log("Capturando plantilla del ancla...");
        Rect fallbackRect = windowTracker.clientRect(selectedWindow);
        overlay.hideOverlay();
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        CaptureEvent event = captureService.captureOnce(selectedWindow);
        if (event != null) {
            overlay.showOverlay(event.clientRect());
        } else if (fallbackRect != null) {
            overlay.showOverlay(fallbackRect);
        }
        if (event == null) {
            warn("No se pudo capturar el frame para el ancla. La ventana debe estar visible.");
            return;
        }
        if (!pipeline.setAnchor(event.frame(), localRect)) {
            warn("El rectangulo del ancla es demasiado pequeno o esta fuera de la imagen.");
            return;
        }
        log("Ancla registrada. El desplazamiento del escenario se medira con ella.");
    }

    private void calibrateAngleDialog() {
        SessionState.Snapshot s = state.snapshot();
        if (!s.angleValid()) {
            warn("No hay una deteccion valida. Apunta el canon y espera a que el angulo se estabilice.");
            return;
        }
        JSpinner angleSpinner = new JSpinner(new SpinnerNumberModel(
                Math.round(s.gameAngleDeg() * 10.0) / 10.0, 0.0, 360.0, 0.1));
        JCheckBox clockwise = new JCheckBox("El angulo del juego aumenta en sentido horario",
                config.clockwiseAngle());
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JPanel row = new JPanel();
        row.add(new JLabel("Angulo que muestra el juego:"));
        row.add(angleSpinner);
        panel.add(row, BorderLayout.NORTH);
        panel.add(clockwise, BorderLayout.SOUTH);
        int result = JOptionPane.showConfirmDialog(this, panel, "Calibrar angulo",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            double known = ((Number) angleSpinner.getValue()).doubleValue();
            double offset = pipeline.calibrateAngle(known, clockwise.isSelected());
            saveConfig();
            log(String.format("Angulo calibrado. Offset = %.2f deg, sentido %s.",
                    offset, clockwise.isSelected() ? "horario" : "antihorario"));
        } catch (SimulationException ex) {
            warn(ex.getMessage());
        }
    }

    private void calibratePowerDialog() {
        SessionState.Snapshot s = state.snapshot();
        if (!s.angleValid()) {
            warn("Necesitas un angulo valido para calibrar la potencia.");
            return;
        }
        if (s.power() <= 0) {
            warn("Ajusta una potencia conocida mayor que 0.");
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
                "1) Dispara en el juego con potencia " + String.format("%.0f", s.power())
                + " y el angulo actual.\n"
                + "2) Pulsa OK y haz clic en el punto de impacto del proyectil.\n\n"
                + "La gravedad se mantiene como parametro fijo; un solo disparo calibra\n"
                + "la relacion entre potencia y velocidad.",
                "Calibrar potencia con impacto",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            beginInteraction(OverlayWindow.InteractionMode.IMPACT);
        }
    }

    private void calibratePowerAt(Vec2 scenePoint) {
        try {
            double scale = pipeline.calibratePowerFromImpact(scenePoint);
            config.setPowerScale(scale);
            powerScaleField.setText(String.format("%.3f", scale));
            saveConfig();
            log(String.format("Potencia calibrada: %.3f px/s por unidad.", scale));
        } catch (SimulationException ex) {
            warn(ex.getMessage());
        }
    }

    private void simulate() {
        try {
            SimulationResult result = pipeline.simulate();
            saveConfig();
            log(String.format("SIMULAR: angulo %.1f deg, v0 %.0f px/s, aire 0, phi %.2f s.",
                    result.angleDeg(), result.speedPxPerSecond(),
                    result.trajectory().flightTimeSeconds()));
        } catch (SimulationException ex) {
            warn(ex.getMessage());
        }
    }

    private void useSuggestedPower() {
        SessionState.Snapshot s = state.snapshot();
        if (!Double.isFinite(s.suggestedPower())) {
            warn("No hay una potencia sugerida. Marca el DESTINO y pulsa SIMULAR.");
            return;
        }
        double value = Math.max(0, Math.min(100, s.suggestedPower()));
        if (s.suggestedPower() > 100) {
            warn("La potencia sugerida supera 100; se ajusta a 100 y probablemente no alcance el destino.");
        }
        syncingPower = true;
        try {
            powerSpinner.setValue(value);
            powerSlider.setValue((int) Math.round(value));
            config.setPower(value);
            state.setPower(value);
        } finally {
            syncingPower = false;
        }
        log(String.format("Potencia ajustada a %.1f.", value));
    }

    private void applyShotParams() {
        try {
            double gravity = parsePositive(gravityField.getText(), "gravedad");
            double powerScale = parsePositive(powerScaleField.getText(), "escala de potencia");
            double offset = parseFinite(trajectoryOffsetField.getText(), "correccion de trayectoria");
            config.setGravity(gravity);
            config.setPowerScale(powerScale);
            config.setTrajectoryOffsetDeg(offset);
            state.setGravity(gravity);
            state.setPowerScale(powerScale);
            state.setTrajectoryOffsetDeg(offset);
            saveConfig();
            log("Parametros de disparo aplicados.");
        } catch (IllegalArgumentException ex) {
            warn(ex.getMessage());
        }
    }

    private void applyAdvancedParams() {
        try {
            int radiusMin = (int) parsePositive(radiusMinField.getText(), "radio minimo");
            int radiusMax = (int) parsePositive(radiusMaxField.getText(), "radio maximo");
            if (radiusMax <= radiusMin + 10) {
                throw new IllegalArgumentException("El radio maximo debe superar al minimo en al menos 10 px.");
            }
            double minConfidence = parsePositive(confidenceField.getText(), "confianza minima");
            if (minConfidence > 0.9) {
                throw new IllegalArgumentException("La confianza minima debe estar entre 0 y 0.9.");
            }
            int interval = (int) parsePositive(captureIntervalField.getText(), "intervalo de captura");
            int anchorRadius = (int) parsePositive(anchorRadiusField.getText(), "radio de busqueda del ancla");
            config.setAngleRadiusMin(radiusMin);
            config.setAngleRadiusMax(radiusMax);
            config.setMinAngleConfidence(minConfidence);
            config.setCaptureIntervalMs(interval);
            config.setAnchorSearchRadius(anchorRadius);
            captureService.setIntervalMs(interval);
            pipeline.setAnchorSearchRadius(anchorRadius);
            saveConfig();
            log("Opciones de vision/captura aplicadas.");
        } catch (IllegalArgumentException ex) {
            warn(ex.getMessage());
        }
    }

    private void syncFieldsFromConfig() {
        updatingFields = true;
        try {
            gravityField.setText(String.format("%.1f", config.gravity()));
            powerScaleField.setText(String.format("%.3f", config.powerScale()));
            trajectoryOffsetField.setText(String.format("%.1f", config.trajectoryOffsetDeg()));
            radiusMinField.setText(Integer.toString(config.angleRadiusMin()));
            radiusMaxField.setText(Integer.toString(config.angleRadiusMax()));
            confidenceField.setText(String.format("%.2f", config.minAngleConfidence()));
            captureIntervalField.setText(Integer.toString(config.captureIntervalMs()));
            anchorRadiusField.setText(Integer.toString(config.anchorSearchRadius()));
            syncingPower = true;
            powerSlider.setValue((int) Math.round(config.power()));
            powerSpinner.setValue(config.power());
            state.setPower(config.power());
        } finally {
            syncingPower = false;
            updatingFields = false;
        }
    }

    private void saveConfig() {
        SessionState.Snapshot s = state.snapshot();
        config.setPower(s.power());
        config.save(configPath);
    }

    private void refreshUi() {
        SessionState.Snapshot s = state.snapshot();
        if (selectedWindow != null && s.captureRunning()) {
            CaptureEvent latest = captureService.latest();
            if (latest != null) {
                overlay.updateBounds(latest.clientRect());
            }
        }

        captureLabel.setText(s.captureRunning()
                ? "Captura activa (" + s.fps() + " fps)"
                : "Captura detenida");
        captureLabel.setForeground(s.captureRunning()
                ? new Color(20, 120, 60) : new Color(120, 60, 40));

        if (s.captureRunning() && s.syncValid()) {
            syncLabel.setText(String.format("Sync OK (ancla %.0f%%)", s.anchorScore() * 100));
            syncLabel.setForeground(new Color(20, 120, 60));
        } else {
            syncLabel.setText("Sync: " + s.syncReason());
            syncLabel.setForeground(new Color(150, 70, 30));
        }

        if (s.angleValid()) {
            angleValueLabel.setText(String.format("%.1f°", s.gameAngleDeg()));
            angleValueLabel.setForeground(new Color(20, 100, 50));
            angleDetailLabel.setText(String.format(
                    "Pantalla %.1f°  |  confianza %.0f%%  |  %s",
                    s.screenAngleDeg(), s.angleConfidence() * 100,
                    s.clockwiseAngle() ? "sentido horario" : "sentido antihorario"));
            confidenceBar.setValue((int) Math.round(s.angleConfidence() * 100));
            confidenceBar.setString("confianza " + Math.round(s.angleConfidence() * 100) + "%");
        } else {
            angleValueLabel.setText("—");
            angleValueLabel.setForeground(new Color(160, 60, 40));
            String detail = s.angleReason().isBlank() ? "Angulo no disponible" : s.angleReason();
            if (Double.isFinite(s.lastValidGameAngleDeg())) {
                detail += String.format("  |  ultimo valido: %.1f°", s.lastValidGameAngleDeg());
            }
            angleDetailLabel.setText(detail);
            confidenceBar.setValue(0);
            confidenceBar.setString("sin deteccion");
        }

        originLabel.setText(formatScenePoint(s.originScene()));
        targetLabel.setText(formatScenePoint(s.targetScene()));
        suggestedPowerLabel.setText(Double.isFinite(s.suggestedPower())
                ? String.format("%.1f", s.suggestedPower()) : "—");
        trajectoryLabel.setText(s.trajectoryScene().isEmpty()
                ? "Sin trayectoria"
                : (s.trajectoryStale() ? "Desactualizada: pulsa SIMULAR" : s.trajectoryStatus()));
        trajectoryLabel.setForeground(s.trajectoryStale()
                ? new Color(150, 70, 30) : new Color(50, 90, 60));

        if (!syncingPower) {
            syncingPower = true;
            try {
                int sliderValue = (int) Math.round(s.power());
                if (powerSlider.getValue() != sliderValue) {
                    powerSlider.setValue(sliderValue);
                }
                double spinnerValue = ((Number) powerSpinner.getValue()).doubleValue();
                if (Math.abs(spinnerValue - s.power()) > 0.05) {
                    powerSpinner.setValue(s.power());
                }
            } finally {
                syncingPower = false;
            }
        }

        if (!updatingFields) {
            updatingFields = true;
            try {
                setIfNotFocused(gravityField, String.format("%.1f", s.gravity()));
                setIfNotFocused(powerScaleField, String.format("%.3f", s.powerScale()));
                setIfNotFocused(trajectoryOffsetField, String.format("%.1f", s.trajectoryOffsetDeg()));
            } finally {
                updatingFields = false;
            }
        }

        startButton.setEnabled(!s.captureRunning());
        stopButton.setEnabled(s.captureRunning());
        windowCombo.setEnabled(!s.captureRunning());
        refreshButton.setEnabled(!s.captureRunning());
        statusBar.setText(String.format(
                "DPI %.2f  |  ventana: %s  |  %s",
                dpiScale,
                selectedWindow == null ? "ninguna" : selectedWindow.title(),
                s.status()));

        if (!s.angleReason().isBlank() && !s.angleReason().equals(lastLoggedStatus)
                && !s.captureRunning()) {
            lastLoggedStatus = s.angleReason();
            log(s.angleReason());
        }
    }

    private static void setIfNotFocused(JTextField field, String text) {
        if (!field.isFocusOwner() && !field.getText().equals(text)) {
            field.setText(text);
        }
    }

    private String formatScenePoint(Vec2 scene) {
        if (scene == null) {
            return "sin marcar";
        }
        Vec2 local = transform.sceneToLocal(scene);
        return String.format("x=%.0f, y=%.0f", local.x(), local.y());
    }

    private static double parsePositive(String text, String name) {
        double value = parseFinite(text, name);
        if (value <= 0) {
            throw new IllegalArgumentException("El campo '" + name + "' debe ser mayor que 0.");
        }
        return value;
    }

    private static double parseFinite(String text, String name) {
        try {
            double value = Double.parseDouble(text.trim().replace(',', '.'));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor invalido en '" + name + "'.");
        }
    }

    private void warn(String message) {
        state.setStatus(message);
        log("AVISO: " + message);
        JOptionPane.showMessageDialog(this, message, "DragonShot", JOptionPane.WARNING_MESSAGE);
    }

    private void log(String message) {
        String line = "[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator();
        logArea.append(line);
        int lines = logArea.getLineCount();
        if (lines > 300) {
            try {
                logArea.replaceRange("", 0, logArea.getLineStartOffset(lines - 300));
            } catch (Exception ignored) {
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
        System.out.print(line);
    }

    private void updateWindowTitle() {
        // El titulo se mantiene fijo para que FindWindow pueda localizar el
        // HWND al aplicar SetWindowDisplayAffinity.
        setTitle(TITLE);
    }

    private void shutdown() {
        captureService.stop();
        saveConfig();
        overlay.hideOverlay();
        overlay.dispose();
        dispose();
        System.exit(0);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        EventQueue.invokeLater(() -> {
            boolean excluded = windowTracker.excludeFromCapture(TITLE);
            if (!excluded) {
                log("Aviso: el panel de control no se pudo excluir de la captura. "
                        + "Mantenlo fuera del area del juego.");
            }
        });
    }
}
