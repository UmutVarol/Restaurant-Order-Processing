package com.swe312.restaurant;

import com.swe312.restaurant.ui.RestaurantOrderFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class Main {
    public static void main(String[] args) {
        try {
            // Sistemin varsayılan (Windows/Mac/Linux) pencere tasarımını kullanması için.
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            RestaurantOrderFrame app = new RestaurantOrderFrame();
            app.setVisible(true);
        });
    }
}