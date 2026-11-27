package com.agy.core;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.agy.runtime.RuntimeManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Foreground host for the patched core (language_server). Holds a wake lock,
 * streams the process log to files/logs/core.log, and publishes serverUrl once
 * ready.
 */
public final class CoreServerService extends Service {

    private static final String TAG = "CoreServer";

    private RuntimeManager runtime;
    private WakeLockHolder wakeLock;
    private OpenUrlWatcher openUrlWatcher;
    private CoreEventWatcher coreEventWatcher;
    private CoreProcessSupervisor supervisor;
    private com.agy.log.LogRingBuffer logBuffer;
    private com.agy.gateway.SystemGatewayServer gatewayServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        ForegroundNotification.start(this);
        CoreRuntimeState.serviceActive = true;
        runtime = new RuntimeManager(this);
        logBuffer = new com.agy.log.LogRingBuffer();
        gatewayServer = new com.agy.gateway.SystemGatewayServer(this, logBuffer, com.agy.BuildConfig.GATEWAY_PORT);
        gatewayServer.start();

        Log.i(TAG, "state paths: HOME=" + getFilesDir().getAbsolutePath()
                + " appData=" + runtime.getAppDataDir().getAbsolutePath()
                + " projects=" + runtime.getProjectsDir().getAbsolutePath()
                + " runtime=" + runtime.getRuntimeDir().getAbsolutePath());
        CoreRuntimeState.httpsPort = runtime.getHttpsPort();
        CoreRuntimeState.csrfToken = runtime.getCsrfToken();
        CoreRuntimeState.restarting = false;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.serverUrl = null;

        wakeLock = new WakeLockHolder();
        wakeLock.acquire(this);

        openUrlWatcher = new OpenUrlWatcher(this);
        openUrlWatcher.start();

        coreEventWatcher = new CoreEventWatcher(this);

        supervisor = new CoreProcessSupervisor(this, runtime, openUrlWatcher, coreEventWatcher, logBuffer,
                this::onSupervisorTerminated);
        supervisor.start();

        startBackgroundDaemon();
    }

    /**
     * Populates the full native linker and userspace environment required to run
     * Python and {@code agy-daemon} directly from {@code files/usr}.
     */
    public static void configureDaemonProcess(ProcessBuilder pb, File filesDir,
                                              String packageName, int gatewayPort) {
        File prefixDir = new File(filesDir, "usr");
        File binDir = new File(prefixDir, "bin");
        File libDir = new File(prefixDir, "lib");
        File tmpDir = new File(prefixDir, "tmp");
        tmpDir.mkdirs();

        Map<String, String> env = pb.environment();
        env.put("HOME", filesDir.getAbsolutePath());
        env.put("PREFIX", prefixDir.getAbsolutePath());
        env.put("TMPDIR", tmpDir.getAbsolutePath());

        String inheritedPath = env.get("PATH");
        if (inheritedPath == null || inheritedPath.isEmpty()) {
            inheritedPath = System.getenv("PATH");
        }
        String basePath = binDir.getAbsolutePath();
        if (inheritedPath != null && !inheritedPath.isEmpty()) {
            env.put("PATH", basePath + ":" + inheritedPath);
        } else {
            env.put("PATH", basePath + ":/system/bin:/vendor/bin");
        }

        String inheritedLd = env.get("LD_LIBRARY_PATH");
        if (inheritedLd != null && !inheritedLd.isEmpty()) {
            env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + inheritedLd);
        } else {
            env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        }

        File preloadLib = new File(libDir, "libtermux-exec.so");
        if (preloadLib.isFile()) {
            env.put("LD_PRELOAD", preloadLib.getAbsolutePath());
        }

        File certPem = new File(prefixDir, "etc/tls/cert.pem");
        if (certPem.isFile()) {
            env.put("SSL_CERT_FILE", certPem.getAbsolutePath());
        }

        if (packageName != null && !packageName.isEmpty()) {
            env.put("AGY_APPLICATION_ID", packageName);
        }
        if (gatewayPort > 0) {
            env.put("AGY_GATEWAY_PORT", String.valueOf(gatewayPort));
        }
    }

    /** Builds the argv array to invoke {@code agy-daemon} with explicit {@code python3} when present. */
    public static String[] buildDaemonCommand(File filesDir, String action) {
        File prefixDir = new File(filesDir, "usr");
        File daemonBin = new File(prefixDir, "bin/agy-daemon");
        File python3 = new File(prefixDir, "bin/python3");
        if (python3.isFile() && python3.canExecute()) {
            return new String[]{python3.getAbsolutePath(), daemonBin.getAbsolutePath(), action};
        }
        return new String[]{daemonBin.getAbsolutePath(), action};
    }

    private int resolveGatewayPort() {
        return gatewayServer != null ? gatewayServer.getPort() : com.agy.BuildConfig.GATEWAY_PORT;
    }

    private static String readBoundedProcessOutput(Process p, boolean allowWait) {
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int total = 0;
            while (total < 4096) {
                int avail = in.available();
                if (avail <= 0 && (!allowWait || total > 0)) {
                    break;
                }
                int maxRead = avail > 0 ? Math.min(avail, 4096 - total) : Math.min(buf.length, 4096 - total);
                int n = in.read(buf, 0, maxRead);
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void startBackgroundDaemon() {
        new Thread(() -> {
            // Env install (phase 2) can take longer on first run; retry until the
            // daemon binary appears or the service is destroyed.
            final int maxAttempts = 20;
            final long delayMs = 3000;
            for (int attempt = 1; attempt <= maxAttempts && CoreRuntimeState.serviceActive && !destroyed; attempt++) {
                try {
                    Thread.sleep(attempt == 1 ? 1500 : delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                File daemonBin = new File(getFilesDir(), "usr/bin/agy-daemon");
                if (!daemonBin.isFile()) {
                    continue;
                }
                if (!daemonBin.canExecute() && !daemonBin.setExecutable(true, false)) {
                    Log.w(TAG, "agy-daemon exists but is not executable");
                    continue;
                }
                File python3 = new File(getFilesDir(), "usr/bin/python3");
                if (!python3.isFile()) {
                    continue;
                }
                try {
                    Log.i(TAG, "Starting autonomous background daemon (attempt " + attempt + ")...");
                    ProcessBuilder pb = new ProcessBuilder(buildDaemonCommand(getFilesDir(), "start"));
                    pb.directory(getFilesDir());
                    pb.redirectErrorStream(true);
                    configureDaemonProcess(pb, getFilesDir(), getPackageName(), resolveGatewayPort());
                    Process p = pb.start();
                    boolean finished = p.waitFor(5000, TimeUnit.MILLISECONDS);
                    if (!finished) {
                        Log.w(TAG, "agy-daemon start timed out after 5000ms (attempt " + attempt + ")");
                        p.destroyForcibly();
                        continue;
                    }
                    int rc = p.exitValue();
                    String output = readBoundedProcessOutput(p, rc != 0);
                    if (rc == 0) {
                        Log.i(TAG, "agy-daemon started successfully"
                                + (output.isEmpty() ? "" : ": " + output));
                        return;
                    }
                    Log.w(TAG, "agy-daemon start exited with code " + rc
                            + " (attempt " + attempt + ")"
                            + (output.isEmpty() ? "" : ": " + output));
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start agy-daemon (attempt " + attempt + "): " + e.getMessage());
                }
            }
            if (CoreRuntimeState.serviceActive && !destroyed) {
                Log.w(TAG, "agy-daemon not started: binary missing or failed after retries");
            }
        }, "AgyDaemonStarter").start();
    }

    private void stopBackgroundDaemon() {
        try {
            File daemonBin = new File(getFilesDir(), "usr/bin/agy-daemon");
            if (daemonBin.isFile()) {
                Log.i(TAG, "Stopping autonomous background daemon...");
                ProcessBuilder pb = new ProcessBuilder(buildDaemonCommand(getFilesDir(), "stop"));
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                configureDaemonProcess(pb, getFilesDir(), getPackageName(), resolveGatewayPort());
                Process p = pb.start();
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "agy-daemon stop timed out after 1500ms; destroying forcibly");
                    p.destroyForcibly();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop agy-daemon: " + e.getMessage());
        }
    }

    private void onSupervisorTerminated() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                CoreRuntimeState.serverUrl = null;
                CoreRuntimeState.restarting = false;
                CoreProcessSupervisor.clearCoreStateFile(getFilesDir());
                if (CoreRuntimeState.lastError == null) {
                    CoreRuntimeState.lastError = "core supervisor stopped unexpectedly";
                }
                if (wakeLock != null) {
                    wakeLock.release();
                }
                if (runtime != null) {
                    runtime.stopNetworkMonitor();
                }
                if (openUrlWatcher != null) {
                    openUrlWatcher.stop();
                }
                ForegroundNotification.update(CoreServerService.this,
                        "Core stopped — open app to retry");
            }
        });
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        CoreRuntimeState.serviceActive = false;
        stopBackgroundDaemon();
        if (gatewayServer != null) {
            gatewayServer.stop();
        }
        mainHandler.removeCallbacksAndMessages(null);
        // Intentional stop: the watchdog must not treat the exit as a crash.
        if (supervisor != null) {
            supervisor.stop();
        }
        if (openUrlWatcher != null) {
            openUrlWatcher.stop();
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
        if (runtime != null) {
            runtime.stopNetworkMonitor();
        }
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.restarting = false;
        CoreProcessSupervisor.clearCoreStateFile(getFilesDir());
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
