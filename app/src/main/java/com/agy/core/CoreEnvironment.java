package com.agy.core;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import com.agy.runtime.RuntimeManager;

import java.io.File;
import java.util.Map;

/** Builds the environment for the glibc core child process. */
public final class CoreEnvironment {

    private static final String TAG = "CoreEnvironment";

    private CoreEnvironment() {
    }

    public static void apply(RuntimeManager rm, ProcessBuilder pb) {
        File files = rm.appContext().getFilesDir();
        File bin = rm.getBinDir();
        Map<String, String> env = pb.environment();
        // HOME is the app files dir and never a temp dir; it is stable across
        // builds so the core always finds $HOME/.gemini (token, config, chats).
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", rm.appContext().getCacheDir().getAbsolutePath());
        Log.i(TAG, "core env: HOME=" + files.getAbsolutePath()
                + " appDataDir=" + rm.getAppDataDir().getAbsolutePath()
                + " projectsDir=" + rm.getProjectsDir().getAbsolutePath()
                + " TMPDIR=" + rm.appContext().getCacheDir().getAbsolutePath());
        env.put("SSL_CERT_FILE", rm.getCertFile().getAbsolutePath());
        File prefix = new File(files, com.agy.install.EnvPaths.PREFIX_DIR);
        File prefixBin = new File(prefix, "bin");
        String inheritedPath = env.get("PATH");
        // Keep runtime/bin first so glibc language_server resolves Bionic tools
        // (node, python3, git, ...) through their clean LD_LIBRARY_PATH bridges,
        // followed by $PREFIX/bin so newly installed Termux binaries are always on PATH.
        env.put("PATH", bin.getAbsolutePath()
                + ":" + prefixBin.getAbsolutePath()
                + (inheritedPath == null || inheritedPath.isEmpty() ? "" : ":" + inheritedPath));
        // The core's PTY spawner uses $SHELL (or falls back to /bin/bash, which
        // does not exist on Android). Prefer the runtime/bin/bash bridge (which sets
        // Bionic LD_LIBRARY_PATH cleanly), falling back to Android's /system/bin/sh.
        File runtimeBash = new File(bin, "bash");
        if (runtimeBash.isFile() && runtimeBash.canExecute()) {
            env.put("SHELL", runtimeBash.getAbsolutePath());
        } else {
            env.put("SHELL", "/system/bin/sh");
        }
        // PTY children need a terminal type, and the bootstrap prefix ships the
        // terminfo database that resolves it.
        env.put("TERM", "xterm-256color");
        File terminfo = new File(prefix, "share/terminfo");
        if (terminfo.isDirectory()) {
            env.put("TERMINFO", terminfo.getAbsolutePath());
        }
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");

        // Pure-Go resolver keeps DNS independent of Android's missing /etc.
        String godebug = env.get("GODEBUG");
        env.put("GODEBUG", godebug == null || godebug.isEmpty()
                ? "netdns=go" : godebug + ",netdns=go");

        // Mobile Go runtime tuning: keep RSS bounded below Android LMKD thresholds and cap big.LITTLE thrashing.
        if (!env.containsKey("GOMEMLIMIT") || env.get("GOMEMLIMIT").isEmpty()) {
            long memMiB = computeGoMemLimitMiB(queryTotalDeviceMemoryBytes(rm.appContext()));
            env.put("GOMEMLIMIT", memMiB + "MiB");
        }
        if (!env.containsKey("GOGC") || env.get("GOGC").isEmpty()) {
            env.put("GOGC", "75");
        }
        if (!env.containsKey("GOMAXPROCS") || env.get("GOMAXPROCS").isEmpty()) {
            env.put("GOMAXPROCS", String.valueOf(computeGoMaxProcs(Runtime.getRuntime().availableProcessors())));
        }
        // Emits "ANTIGRAVITY_OPEN_URL: <url>" directly to stdout from language_server
        // so OpenUrlWatcher captures the auth URL and opens the external browser reliably.
        env.put("ANTIGRAVITY_VSCODE_HOST", "1");
        // Configure browser and desktop environment so core OAuth/open tools find the browser
        File xdgOpen = new File(bin, "xdg-open");
        env.put("BROWSER", xdgOpen.getAbsolutePath());
        env.put("DISPLAY", ":0");
        env.put("WAYLAND_DISPLAY", "wayland-0");
        env.put("XDG_CURRENT_DESKTOP", "X-Generic");
        env.put("XDG_DATA_DIRS", rm.getRuntimeDir().getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(files, ".local/share").getAbsolutePath());
        env.put("XDG_CONFIG_HOME", new File(files, ".config").getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("AGY_GATEWAY_PORT", String.valueOf(com.agy.gateway.SystemGatewayServer.getActivePort()));
        env.put("AGY_GATEWAY_TOKEN_FILE", new File(files, "logs/gateway.token").getAbsolutePath());
        env.put("AGY_CORE_STATE_FILE", new File(files, CoreProcessSupervisor.CORE_STATE_REL_PATH).getAbsolutePath());
        // The glibc loader resolves libraries via --library-path; keep bionic's out of the way.
        env.remove("LD_LIBRARY_PATH");
        // Do NOT export LD_PRELOAD into the process environment!
        // liblse_compat.so is a GLIBC library; exporting it in env causes Bionic's
        // dynamic linker to crash when executing /system/bin/sh (xdg-open, bridges, termux).
        // RuntimeManager.coreCommand() already preloads it into language_server via the
        // glibc loader's CLI argument: --preload <path>/liblse_compat.so.
        env.remove("LD_PRELOAD");
    }

    /** Computes a bounded Go soft memory limit (384..1024 MiB, ~25% of device RAM, default 512 MiB). */
    public static long computeGoMemLimitMiB(long totalMemBytes) {
        if (totalMemBytes <= 0L) {
            return 512L;
        }
        long quarterMiB = (totalMemBytes / (1024L * 1024L)) / 4L;
        return Math.max(384L, Math.min(1024L, quarterMiB));
    }

    /** Caps GOMAXPROCS between 2 and 6 cores (default 4) to avoid big.LITTLE scheduler contention. */
    public static int computeGoMaxProcs(int availableCpus) {
        if (availableCpus <= 0) {
            return 4;
        }
        return Math.max(2, Math.min(availableCpus, 6));
    }

    private static long queryTotalDeviceMemoryBytes(Context context) {
        if (context == null) {
            return 0L;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi.totalMem;
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
