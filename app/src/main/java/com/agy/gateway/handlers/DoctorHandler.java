package com.agy.gateway.handlers;

import android.content.Context;

import com.agy.BuildConfig;
import com.agy.core.CoreRuntimeState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Health checks and limited safe repairs for the local runtime environment. */
public final class DoctorHandler {

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
