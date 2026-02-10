package com.example.reader;

import android.util.Log;

public class ConfigManager {
    private static ConfigManager instance;
    private Configuration configuration;

    private ConfigManager() {
        // Private constructor
        configuration = new Configuration();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Configuration config) {
        if (config != null) {
            this.configuration = config;
        }
    }

    // Convenience methods for commonly used settings
    public void setSaveCapturedImage(boolean saveImage) {
        configuration.setSaveCapturedImage(saveImage);
        Log.d("@@>> ConfigManager", "📸 Save image flag set to: " + saveImage);
    }

    public boolean shouldSaveCapturedImage() {
        return configuration.isSaveCapturedImage();
    }

    // Reset to defaults
    public void resetToDefaults() {
        configuration = new Configuration();
    }
}