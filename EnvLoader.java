package com.eventsphere.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public class EnvLoader {
    private static final Map<String, String> envMap = new HashMap<>();

    static {
        loadEnv();
    }

    private static void loadEnv() {
        File envFile = new File(".env");
        if (!envFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    envMap.put(key, value);
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading .env file: " + e.getMessage());
        }
    }

    public static String get(String key, String defaultValue) {
        String sysEnv = System.getenv(key);
        if (sysEnv != null && !sysEnv.trim().isEmpty()) {
            return sysEnv.trim();
        }
        return envMap.getOrDefault(key, defaultValue);
    }
}
