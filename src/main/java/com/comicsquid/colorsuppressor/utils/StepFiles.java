package com.comicsquid.colorsuppressor.utils;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

/**
 * Step-checker run-state persistence: the crash/restart resume files under the
 * map folder's _configs directory. Two files, both tiny hand-rolled JSON (the
 * formats are STABLE - files written by older builds must stay readable):
 *   step_checkered_progress.json  {"batch":k,"mapId":m}  - batch in progress + working map id
 *   step_checkered_reset.json     {"scope":c}            - RESET scope pending resume
 * All reads are total-signal-safe: any missing file or parse error yields the
 * documented default, never an exception.
 */
public final class StepFiles {

    private StepFiles() {}

    private static final String PROGRESS_NAME = "step_checkered_progress.json";
    private static final String RESET_NAME = "step_checkered_reset.json";

    private static File configDir(File mapFolder) {
        return new File(mapFolder, "_configs");
    }

    // --- Batch progress (crash/restart resume) ---

    /** Persist "batch k is in progress" with the working map id.
     *  @return true when the file was written. */
    public static boolean saveProgress(File mapFolder, int batch, int mapId) {
        try {
            File file = new File(configDir(mapFolder), PROGRESS_NAME);
            file.getParentFile().mkdirs();
            FileWriter writer = new FileWriter(file);
            writer.write("{\"batch\":" + batch + ",\"mapId\":" + mapId + "}\n");
            writer.close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Read the persisted progress (parse order mirrors the historical format:
     * a readable mapId survives even when the batch field fails to parse).
     * @return {batch, mapId}; batch 0 and mapId -1 when absent/unreadable.
     */
    public static int[] loadProgress(File mapFolder) {
        int batch = 0;
        int mapId = -1;
        if (mapFolder == null) return new int[]{batch, mapId};
        try {
            File file = new File(configDir(mapFolder), PROGRESS_NAME);
            if (!file.exists()) return new int[]{batch, mapId};
            String json = new String(Files.readAllBytes(file.toPath()));
            int batchStart = json.indexOf("\"batch\":") + 8;
            int batchEnd = json.indexOf(",", batchStart);
            if (batchEnd < 0) batchEnd = json.indexOf("}", batchStart);
            int mapStart = json.indexOf("\"mapId\":");
            if (mapStart >= 0) {
                int mapEnd = json.indexOf(",", mapStart);
                if (mapEnd < 0) mapEnd = json.indexOf("}", mapStart);
                mapId = Integer.parseInt(json.substring(mapStart + 8, mapEnd).trim());
            }
            batch = Integer.parseInt(json.substring(batchStart, batchEnd).trim());
        } catch (Exception e) {
            return new int[]{0, mapId};
        }
        return new int[]{batch, mapId};
    }

    public static void clearProgress(File mapFolder) {
        try {
            if (mapFolder != null) new File(configDir(mapFolder), PROGRESS_NAME).delete();
        } catch (Exception ignored) {
        }
    }

    // --- RESET-in-flight scope (disable/crash mid-RESET must resume it) ---

    public static void saveResetPending(File mapFolder, int scopeCol) {
        try {
            File file = new File(configDir(mapFolder), RESET_NAME);
            file.getParentFile().mkdirs();
            FileWriter writer = new FileWriter(file);
            writer.write("{\"scope\":" + scopeCol + "}\n");
            writer.close();
        } catch (Exception ignored) {
        }
    }

    /** @return the persisted RESET scope column, or -1 when absent/unreadable. */
    public static int loadResetPending(File mapFolder) {
        try {
            File file = new File(configDir(mapFolder), RESET_NAME);
            if (!file.exists() || mapFolder == null) return -1;
            String json = new String(Files.readAllBytes(file.toPath()));
            int start = json.indexOf("\"scope\":") + 9;
            int end = json.indexOf("}", start);
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public static void clearResetPending(File mapFolder) {
        try {
            if (mapFolder != null) new File(configDir(mapFolder), RESET_NAME).delete();
        } catch (Exception ignored) {
        }
    }
}
