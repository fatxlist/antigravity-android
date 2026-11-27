package com.agy.gateway.handlers;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.agy.BuildConfig;
import com.agy.core.CoreRuntimeState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Health checks and limited safe repairs for the local runtime environment. */
public final class DoctorHandler {

    private static final Pattern JSON_PID_PATTERN = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");

    private final Context context;

    public DoctorHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject doctor() {
        JSONObject resp = new JSONObject();
        JSONArray checks = new JSONArray();
        int ok = 0, warn = 0, error = 0;
        try {
            File files = context.getFilesDir();
            File logs = new File(files, "logs");
            File usrBash = new File(files, "usr/bin/bash");
            File runtime = new File(files, "runtime");
            File web = new File(files, "web/index.html");
            File gwPort = new File(logs, "gateway.port");
            File gwToken = new File(logs, "gateway.token");
            boolean coreReady = CoreRuntimeState.serverUrl != null && !CoreRuntimeState.serverUrl.trim().isEmpty();
            long freeMb = files.getUsableSpace() / (1024 * 1024);

            add(checks, "core_ready", coreReady ? "ok" : "warn",
                    coreReady ? "Core engine ready" : (CoreRuntimeState.restarting ? "Core engine restarting" : "Core engine not ready"),
                    CoreRuntimeState.lastError != null
                            ? ("Last error: " + CoreRuntimeState.lastError)
                            : (coreReady ? "Application is ready to handle requests." : "Wait a moment after launch, or inspect core logs if it stays unready."),
                    "Open the app, let startup complete, and check again. Inspect crash/core logs if this persists.");

            add(checks, "usr_env", usrBash.isFile() ? "ok" : "warn",
                    usrBash.isFile() ? "Userspace toolchain installed" : "Userspace toolchain incomplete",
                    usrBash.isFile() ? "Base CLI shell and tools are available." : "Background Bionic env extraction may still be running or deferred.",
                    "Keep the app open briefly for Phase 2 installation to complete.");

            add(checks, "runtime", runtime.isDirectory() ? "ok" : "error",
                    runtime.isDirectory() ? "Runtime directory present" : "Runtime directory missing",
                    runtime.isDirectory() ? "Core runtime components extracted." : "Core runtime assets are missing.",
                    "Reinstall or repair the APK.");

            add(checks, "web", web.isFile() ? "ok" : "error",
                    web.isFile() ? "Web bundle present" : "Web bundle missing",
                    web.isFile() ? "Local Web UI assets are available." : "Web UI assets missing; UI cannot load.",
                    "Reinstall the APK.");

            add(checks, "gateway", (gwPort.isFile() && gwToken.isFile()) ? "ok" : "warn",
                    (gwPort.isFile() && gwToken.isFile()) ? "System Gateway ready" : "System Gateway state incomplete",
                    (gwPort.isFile() && gwToken.isFile()) ? "Gateway port and auth token files exist." : "Gateway port or token file is not yet created.",
                    "Ensure the app foreground service is active and retry.");

            if (freeMb < 200) {
                add(checks, "storage", "error", "Critically low storage",
                        "Approximately " + freeMb + " MB free.", "Free internal storage space and retry.");
            } else if (freeMb < 1024) {
                add(checks, "storage", "warn", "Limited free storage",
                        "Approximately " + freeMb + " MB free.", "Keep at least 1 GB free for packages and workspaces.");
            } else {
                add(checks, "storage", "ok", "Sufficient storage",
                        "Approximately " + freeMb + " MB free.", "");
            }

            add(checks, "logs_dir", (logs.isDirectory() || logs.mkdirs()) ? "ok" : "warn",
                    logs.isDirectory() ? "Logs directory ready" : "Logs directory unavailable",
                    logs.getAbsolutePath(), logs.isDirectory() ? "" : "Verify app storage permissions and relaunch.");

            File pidFile = new File(files, ".gemini/antigravity-app/daemon/daemon.pid");
            long daemonPid = readLiveDaemonPid(pidFile, new File("/proc"));
            add(checks, "daemon", daemonPid > 0 ? "ok" : "warn",
                    daemonPid > 0 ? "Background daemon running" : "Background daemon not running",
                    daemonPid > 0
                            ? ("agy-daemon active (PID " + daemonPid + ").")
                            : "agy-daemon is not active; completion alerts and scheduled tasks may not fire.",
                    daemonPid > 0 ? "" : "Restart Antigravity or run 'agy-daemon start'.");

            boolean notifGranted = hasNotificationPermission();
            add(checks, "notification_permission", notifGranted ? "ok" : "warn",
                    notifGranted ? "Notifications enabled" : "Notification permission missing",
                    notifGranted
                            ? "Android notifications are permitted for this app."
                            : "POST_NOTIFICATIONS or app notifications are disabled; agent completion notifications cannot be shown.",
                    notifGranted ? "" : "Allow Notifications for Antigravity in Android Settings.");

            boolean micGranted = hasMicrophonePermission();
            add(checks, "microphone_permission", micGranted ? "ok" : "warn",
                    micGranted ? "Microphone permission granted" : "Microphone permission not granted",
                    micGranted
                            ? "RECORD_AUDIO runtime permission is granted."
                            : "RECORD_AUDIO has not been granted yet; Android will prompt when starting Voice Mode or Dictation.",
                    micGranted ? "" : "Tap Voice / Dictation or allow Microphone in Android App Permissions.");

            for (int i = 0; i < checks.length(); i++) {
                String lv = checks.getJSONObject(i).optString("level");
                if ("error".equals(lv)) error++;
                else if ("warn".equals(lv)) warn++;
                else ok++;
            }

            resp.put("ok", true);
            resp.put("summary", new JSONObject().put("ok", ok).put("warn", warn).put("error", error));
            resp.put("checks", checks);
            resp.put("app_version", BuildConfig.APP_VERSION_NAME);
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return resp;
    }

    public JSONObject repair(JSONObject payload) {
        JSONObject resp = new JSONObject();
        JSONArray actions = new JSONArray();
        try {
            File files = context.getFilesDir();
            File logs = new File(files, "logs");
            boolean logsOk = logs.isDirectory() || logs.mkdirs();
            actions.put(new JSONObject().put("action", "ensure_logs_dir").put("ok", logsOk)
                    .put("detail", logsOk ? "Logs directory ready." : "Failed to create logs directory."));

            int cleaned = 0;
            File cache = context.getCacheDir();
            if (cache != null && cache.isDirectory()) {
                File[] tmp = cache.listFiles((d, n) -> n != null && (n.endsWith(".tmp") || n.startsWith("tmp")));
                if (tmp != null) {
                    for (File f : tmp) {
                        if (f.isFile() && f.delete()) cleaned++;
                    }
                }
            }
            actions.put(new JSONObject().put("action", "cleanup_cache_tmp").put("ok", true)
                    .put("detail", "Removed temporary cache files: " + cleaned + "."));

            resp.put("ok", true);
            resp.put("actions", actions);
            resp.put("note", "Major repairs (re-extracting runtime or toolchain) occur automatically via APK startup/reinstall.");
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return resp;
    }

    /** Returns live agy-daemon PID if pidFile points to a matching /proc/<pid>/cmdline process, else -1. */
    public static long readLiveDaemonPid(File pidFile, File procRoot) {
        if (pidFile == null || !pidFile.isFile() || procRoot == null || !procRoot.isDirectory()) {
            return -1L;
        }
        String raw = readBoundedUtf8(pidFile, 256).trim();
        if (raw.isEmpty()) {
            return -1L;
        }
        long pid = -1L;
        try {
            if (raw.startsWith("{")) {
                Matcher m = JSON_PID_PATTERN.matcher(raw);
                if (m.find()) {
                    pid = Long.parseLong(m.group(1));
                }
            } else {
                pid = Long.parseLong(raw);
            }
        } catch (Exception ignored) {
            return -1L;
        }
        if (pid <= 0L) {
            return -1L;
        }
        File cmdlineFile = new File(procRoot, pid + "/cmdline");
        if (!cmdlineFile.isFile()) {
            return -1L;
        }
        String cmdline = readBoundedUtf8(cmdlineFile, 512).replace('\0', ' ');
        return cmdline.contains("agy-daemon") ? pid : -1L;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        }
        return true;
    }

    private boolean hasMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static String readBoundedUtf8(File file, int maxBytes) {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[Math.min(maxBytes, 512)];
            int total = 0;
            while (total < maxBytes) {
                int n = in.read(buf, 0, Math.min(buf.length, maxBytes - total));
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void add(JSONArray arr, String id, String level, String title, String detail, String fix) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("level", level);
            o.put("title", title);
            o.put("detail", detail);
            if (fix != null && !fix.isEmpty()) o.put("fix", fix);
            arr.put(o);
        } catch (Exception ignored) {}
    }
}
