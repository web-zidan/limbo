/*
 * Limbo Runtime Options
 *
 * Small host-runtime preferences kept separate from the per-VM configuration.
 * Guest vCPU count remains Machine.getCpuNum(); host affinity is independent.
 */
package com.max2idea.android.limbo.main;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RuntimeOptions {
    public static final String FIRMWARE_SEABIOS = "seabios";
    public static final String FIRMWARE_UEFI = "uefi";
    public static final String GRAPHICS_AUTO = "auto";
    public static final String GRAPHICS_SOFTWARE = "software";
    public static final String GRAPHICS_OPENGL = "opengl";
    public static final String GRAPHICS_VULKAN = "vulkan";

    private static final String KEY_FIRMWARE = "runtime_firmware";
    private static final String KEY_GRAPHICS = "runtime_graphics_backend";
    private static final String KEY_HOST_CPUS = "runtime_host_cpu_ids";

    private RuntimeOptions() { }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static String getFirmware(Context context) {
        return prefs(context).getString(KEY_FIRMWARE, FIRMWARE_SEABIOS);
    }

    public static void setFirmware(Context context, String firmware) {
        prefs(context).edit().putString(KEY_FIRMWARE,
                FIRMWARE_UEFI.equals(firmware) ? FIRMWARE_UEFI : FIRMWARE_SEABIOS).apply();
    }

    public static String getGraphicsBackend(Context context) {
        return prefs(context).getString(KEY_GRAPHICS, GRAPHICS_AUTO);
    }

    public static void setGraphicsBackend(Context context, String backend) {
        String value = GRAPHICS_AUTO;
        if (GRAPHICS_SOFTWARE.equals(backend)) value = GRAPHICS_SOFTWARE;
        else if (GRAPHICS_OPENGL.equals(backend)) value = GRAPHICS_OPENGL;
        else if (GRAPHICS_VULKAN.equals(backend)) value = GRAPHICS_VULKAN;
        prefs(context).edit().putString(KEY_GRAPHICS, value).apply();
    }

    /*
     * This Limbo native configuration currently disables QEMU OpenGL and does
     * not link virglrenderer/rutabaga. These modes must not be advertised as
     * working guest-GPU acceleration until those native components are built.
     */
    public static boolean isGuestGraphicsBackendBuilt(String backend) {
        return GRAPHICS_AUTO.equals(backend) || GRAPHICS_SOFTWARE.equals(backend);
    }

    public static int[] getHostCpuIds(Context context) {
        String stored = prefs(context).getString(KEY_HOST_CPUS, "ALL");
        if (stored == null || stored.length() == 0 || "ALL".equals(stored)) return null;
        String[] parts = stored.split(",");
        int[] result = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                int id = Integer.parseInt(part.trim());
                if (id >= 0) result[count++] = id;
            } catch (NumberFormatException ignored) { }
        }
        if (count == 0) return null;
        return Arrays.copyOf(result, count);
    }

    public static void setHostCpuIds(Context context, int[] cpuIds) {
        if (cpuIds == null || cpuIds.length == 0) {
            prefs(context).edit().putString(KEY_HOST_CPUS, "ALL").apply();
            return;
        }
        int[] copy = Arrays.copyOf(cpuIds, cpuIds.length);
        Arrays.sort(copy);
        StringBuilder value = new StringBuilder();
        int previous = -1;
        for (int cpuId : copy) {
            if (cpuId < 0 || cpuId == previous) continue;
            if (value.length() > 0) value.append(',');
            value.append(cpuId);
            previous = cpuId;
        }
        prefs(context).edit().putString(KEY_HOST_CPUS,
                value.length() == 0 ? "ALL" : value.toString()).apply();
    }

    /*
     * Prefer the process's actual Linux affinity set; Android devices may use
     * non-contiguous CPU IDs (and app cpusets can differ from online CPUs).
     */
    public static int[] getAvailableHostCpuIds() {
        int[] allowed = readCpuList("/proc/self/status", "Cpus_allowed_list:");
        if (allowed.length > 0) return allowed;
        int[] online = readCpuList("/sys/devices/system/cpu/online", null);
        if (online.length > 0) return online;
        int count = Math.max(1, Runtime.getRuntime().availableProcessors());
        int[] fallback = new int[count];
        for (int i = 0; i < count; i++) fallback[i] = i;
        return fallback;
    }

    private static int[] readCpuList(String path, String wantedPrefix) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String line;
            while ((line = reader.readLine()) != null) {
                if (wantedPrefix == null) {
                    return parseCpuList(line.trim());
                }
                if (line.startsWith(wantedPrefix)) {
                    return parseCpuList(line.substring(wantedPrefix.length()).trim());
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }
        return new int[0];
    }

    private static int[] parseCpuList(String value) {
        List<Integer> ids = new ArrayList<>();
        for (String token : value.split(",")) {
            String part = token.trim();
            if (part.length() == 0) continue;
            try {
                int dash = part.indexOf('-');
                if (dash < 0) {
                    ids.add(Integer.parseInt(part));
                } else {
                    int first = Integer.parseInt(part.substring(0, dash).trim());
                    int last = Integer.parseInt(part.substring(dash + 1).trim());
                    if (first >= 0 && last >= first && last - first <= 255) {
                        for (int i = first; i <= last; i++) ids.add(i);
                    }
                }
            } catch (NumberFormatException ignored) { }
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
        return result;
    }

    public static int getRecommendedGuestRamMb(Context context) {
        long mib = 1024L * 1024L;
        long totalMb = 0;
        long availableMb = 0;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            if (manager != null) {
                manager.getMemoryInfo(info);
                totalMb = info.totalMem / mib;
                availableMb = info.availMem / mib;
            }
        } catch (Exception ignored) { }

        if (totalMb <= 0) totalMb = 2048;
        if (availableMb <= 0) availableMb = totalMb / 2;

        // Reserve memory for Android, the emulator process, and native libraries.
        long byTotal = totalMb * 45L / 100L;
        long byAvailable = availableMb * 70L / 100L;
        long recommended = Math.min(byTotal, byAvailable);
        recommended = Math.max(128, Math.min(8192, recommended));
        recommended = (recommended / 8) * 8;
        return (int) recommended;
    }
}
