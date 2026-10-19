package com.dragonshot.sim;

import com.dragonshot.sim.ui.MainFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Punto de entrada de la aplicacion de escritorio. */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Se usa el Look and Feel por defecto.
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Error inesperado: " + error.getClass().getSimpleName()
                    + "\n" + (error.getMessage() == null ? "" : error.getMessage()),
                    "DragonShot", JOptionPane.ERROR_MESSAGE));
        });
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
